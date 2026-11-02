#!/usr/bin/env python3
"""Nova ↔ box PCM audio bridge (TCP, Tailscale).

Protocol (bidirectional):
  frame = uint32 BE length + uint8 type + payload
  type 0 = PCM s16le mono 16 kHz
  type 1 = control UTF-8 text

Modes:
  echo  — mic loopback + optional answer beep
  agent — VAD → DashScope ASR → AgentScope LLM → DashScope TTS → PCM (cascade fallback)
  omni  — DashScope Qwen-Omni Realtime (native multimodal; Manual + local EnergyVAD)
"""
from __future__ import annotations

import argparse
import math
import os
import socket
import hmac
from uuid import uuid4
import struct
import sys
import threading
import time
import traceback
from pathlib import Path

from agent_api import (
    SYSTEM_PROMPT,
    apology_pcm,
    asr_pcm,
    llm_chat,
    tts_to_pcm,
)
from omni_realtime import (
    DEFAULT_VOICE as OMNI_DEFAULT_VOICE,
    OmniRealtimeSession,
)
from bridge_tools import (
    TOOL_SCHEMAS,
    execute as tools_execute,
    match_intent,
    speakable_from_tool,
    tools_enabled,
    tools_instructions_addon,
)

def append_persona_card_instructions(base_instructions: str, store=None) -> str:
    """Append the selected Whale persona to Omni instructions.

    If the shared SQLite store is configured, imported cards and the persisted
    selection are available. Without it, built-in cards can still be selected
    with WHALE_PERSONA_CARD. Failure is non-fatal: keep the existing prompt.
    """
    try:
        core_src = Path(__file__).resolve().parents[3] / "whale-core" / "src"
        if core_src.is_dir() and str(core_src) not in sys.path:
            sys.path.insert(0, str(core_src))
        from whale_core.memory.store import InMemoryStore
        from whale_core.ui.persona_cards import active_card

        selected_store = store if store is not None else InMemoryStore()
        card = active_card(selected_store)
        persona = card.render_persona()
        constraints = "\n".join(f"- {rule}" for rule in persona.taboos)
        addition = (
            "\n\n[Whale persona card: expression settings only; no permission changes]\n"
            + persona.system_preamble
            + "\nGlobal constraints (not overridable by the card):\n"
            + constraints
        )
        return base_instructions.rstrip() + addition
    except Exception as exc:
        # Do not break a live call if the optional shared persona store is unavailable.
        print(f"[omni] persona card unavailable: {type(exc).__name__}", flush=True)
        return base_instructions


TYPE_PCM = 0
TYPE_CTRL = 1
SAMPLE_RATE = 16000
BYTES_PER_MS = SAMPLE_RATE * 2 // 1000  # 32
TOKEN = os.environ.get("BRIDGE_TOKEN", "")

# VAD defaults (overridable via env)
VAD_RMS_THRESH = float(os.environ.get("BRIDGE_VAD_RMS", "600"))
VAD_SILENCE_MS = int(os.environ.get("BRIDGE_VAD_SILENCE_MS", "900"))  # 700–1200
VAD_MIN_SPEECH_MS = int(os.environ.get("BRIDGE_VAD_MIN_SPEECH_MS", "280"))
VAD_MAX_UTTER_MS = int(os.environ.get("BRIDGE_VAD_MAX_UTTER_MS", "12000"))
PCM_CHUNK_MS = int(os.environ.get("BRIDGE_PCM_CHUNK_MS", "40"))
HISTORY_TURNS = int(os.environ.get("BRIDGE_HISTORY_TURNS", "6"))  # user+asst pairs capped


def make_beep(ms: int = 350, freq: float = 880.0, vol: float = 0.25) -> bytes:
    n = int(SAMPLE_RATE * ms / 1000)
    out = bytearray(n * 2)
    for i in range(n):
        env = 1.0
        if i < 200:
            env = i / 200
        elif i > n - 400:
            env = max(0.0, (n - i) / 400)
        s = int(vol * env * 32767 * math.sin(2 * math.pi * freq * i / SAMPLE_RATE))
        struct.pack_into("<h", out, i * 2, s)
    return bytes(out)


def make_tick(ms: int = 80, freq: float = 660.0, vol: float = 0.12) -> bytes:
    """Soft short tick for thinking progress while ASR/LLM/TTS run."""
    return make_beep(ms=ms, freq=freq, vol=vol)


def is_socket_closed_error(exc: BaseException) -> bool:
    if isinstance(exc, (BrokenPipeError, ConnectionError, ConnectionResetError)):
        return True
    if isinstance(exc, OSError):
        # EBADF=9, EPIPE=32, ECONNRESET=104
        if getattr(exc, "errno", None) in (9, 32, 104):
            return True
        msg = str(exc).lower()
        if "bad file descriptor" in msg or "broken pipe" in msg:
            return True
    return False


def pcm_rms(pcm: bytes) -> float:
    n = len(pcm) // 2
    if n <= 0:
        return 0.0
    # sample every other sample for speed on large chunks
    step = 2 if n > 640 else 1
    acc = 0.0
    count = 0
    for i in range(0, n, step):
        s = struct.unpack_from("<h", pcm, i * 2)[0]
        acc += s * s
        count += 1
    return math.sqrt(acc / max(1, count))


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed")
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock: socket.socket) -> tuple[int, bytes]:
    hdr = recv_exact(sock, 5)
    length = struct.unpack("!I", hdr[:4])[0]
    typ = hdr[4]
    if length > 1_000_000:
        raise ValueError(f"frame too large: {length}")
    payload = recv_exact(sock, length) if length else b""
    return typ, payload


def write_frame(sock: socket.socket, typ: int, payload: bytes, lock: threading.Lock) -> None:
    with lock:
        try:
            sock.sendall(struct.pack("!IB", len(payload), typ) + payload)
        except OSError as e:
            if is_socket_closed_error(e):
                raise ConnectionError(f"peer closed during send: {e}") from None
            raise


def send_pcm_chunked(
    sock: socket.socket,
    pcm: bytes,
    lock: threading.Lock,
    stats: "Stats",
    chunk_ms: int = PCM_CHUNK_MS,
    playing_flag: threading.Event | None = None,
) -> None:
    if playing_flag is not None:
        playing_flag.set()
    try:
        chunk = max(BYTES_PER_MS * chunk_ms, 640)
        # align to 2 bytes
        if chunk % 2:
            chunk += 1
        i = 0
        t_start = time.monotonic()
        sent_ms = 0.0
        while i < len(pcm):
            piece = pcm[i : i + chunk]
            if len(piece) % 2:
                piece = piece[:-1]
            if not piece:
                break
            write_frame(sock, TYPE_PCM, piece, lock)
            stats.add_tx(len(piece))
            i += len(piece)
            sent_ms += len(piece) / BYTES_PER_MS
            # pace roughly real-time so phone buffer stays smooth
            target = t_start + sent_ms / 1000.0 * 0.85  # slight lead
            now = time.monotonic()
            if target > now:
                time.sleep(min(0.05, target - now))
    finally:
        if playing_flag is not None:
            playing_flag.clear()


class Stats:
    def __init__(self) -> None:
        self.rx = 0
        self.tx = 0
        self.t0 = time.time()
        self.lock = threading.Lock()

    def add_rx(self, n: int) -> None:
        with self.lock:
            self.rx += n

    def add_tx(self, n: int) -> None:
        with self.lock:
            self.tx += n

    def snapshot(self) -> str:
        with self.lock:
            dt = max(0.001, time.time() - self.t0)
            return f"rx={self.rx} ({self.rx/dt:.0f} B/s) tx={self.tx} ({self.tx/dt:.0f} B/s) t={dt:.1f}s"


class EnergyVAD:
    """Simple energy VAD: buffer until silence after speech."""

    def __init__(
        self,
        rms_thresh: float = VAD_RMS_THRESH,
        silence_ms: int = VAD_SILENCE_MS,
        min_speech_ms: int = VAD_MIN_SPEECH_MS,
        max_utter_ms: int = VAD_MAX_UTTER_MS,
    ) -> None:
        self.rms_thresh = rms_thresh
        self.silence_ms = silence_ms
        self.min_speech_ms = min_speech_ms
        self.max_utter_ms = max_utter_ms
        self.reset()

    def reset(self) -> None:
        self.buf = bytearray()
        self.in_speech = False
        self.speech_ms = 0
        self.silence_run_ms = 0
        self.utter_ms = 0

    def feed(self, pcm: bytes) -> bytes | None:
        """Return completed utterance PCM, or None."""
        if not pcm:
            return None
        if len(pcm) % 2:
            pcm = pcm[:-1]
        ms = len(pcm) / BYTES_PER_MS
        rms = pcm_rms(pcm)
        speech = rms >= self.rms_thresh

        if speech:
            if not self.in_speech:
                self.in_speech = True
                self.speech_ms = 0
                self.silence_run_ms = 0
                # keep a little pre-roll (~120ms) already in buf if any; else start fresh
                if len(self.buf) > BYTES_PER_MS * 200:
                    self.buf = self.buf[-(BYTES_PER_MS * 120) :]
            self.buf.extend(pcm)
            self.speech_ms += ms
            self.utter_ms += ms
            self.silence_run_ms = 0
        else:
            if self.in_speech:
                self.buf.extend(pcm)
                self.utter_ms += ms
                self.silence_run_ms += ms
                if self.silence_run_ms >= self.silence_ms:
                    return self._finish()
            else:
                # keep short ring buffer for pre-roll
                self.buf.extend(pcm)
                max_preroll = BYTES_PER_MS * 200
                if len(self.buf) > max_preroll:
                    self.buf = self.buf[-max_preroll:]

        if self.in_speech and self.utter_ms >= self.max_utter_ms:
            return self._finish()
        return None

    def _finish(self) -> bytes | None:
        pcm = bytes(self.buf)
        speech_ok = self.speech_ms >= self.min_speech_ms
        self.reset()
        if not speech_ok or len(pcm) < BYTES_PER_MS * self.min_speech_ms:
            return None
        return pcm


def handle_echo(conn, addr, capture_dir: Path, echo: bool, beep: bool) -> None:
    peer = f"{addr[0]}:{addr[1]}"
    print(f"[+] accept {peer} mode=echo", flush=True)
    send_lock = threading.Lock()
    stats = Stats()
    capture_fp = None
    stop = threading.Event()
    try:
        if capture_dir:
            capture_dir.mkdir(parents=True, exist_ok=True)
            ts = time.strftime("%Y%m%d-%H%M%S")
            path = capture_dir / f"nova-mic-{ts}.pcm"
            capture_fp = open(path, "wb")
            print(f"[*] capture -> {path}", flush=True)

        write_frame(conn, TYPE_CTRL, b"HELLO box mode=echo sample=16000", send_lock)
        if beep:
            tone = make_beep()
            write_frame(conn, TYPE_PCM, tone, send_lock)
            stats.add_tx(len(tone))
            print(f"[*] sent answer beep {len(tone)} bytes", flush=True)

        def ticker():
            while not stop.wait(2.0):
                print(f"[stats {peer}] {stats.snapshot()}", flush=True)

        threading.Thread(target=ticker, daemon=True).start()

        while not stop.is_set():
            typ, payload = read_frame(conn)
            if typ == TYPE_CTRL:
                text = payload.decode("utf-8", "replace")
                print(f"[ctrl {peer}] {text}", flush=True)
                if TOKEN and text.startswith("AUTH "):
                    if text[5:].strip() != TOKEN:
                        write_frame(conn, TYPE_CTRL, b"AUTH_FAIL", send_lock)
                        break
                    write_frame(conn, TYPE_CTRL, b"AUTH_OK", send_lock)
                continue
            if typ != TYPE_PCM:
                continue
            stats.add_rx(len(payload))
            if capture_fp:
                capture_fp.write(payload)
            if echo and payload:
                write_frame(conn, TYPE_PCM, payload, send_lock)
                stats.add_tx(len(payload))
    except Exception as e:
        print(f"[-] {peer} end: {e}", flush=True)
    finally:
        stop.set()
        try:
            conn.close()
        except Exception:
            pass
        if capture_fp:
            capture_fp.close()
        print(f"[*] closed {peer} final {stats.snapshot()}", flush=True)


def handle_agent(conn, addr, capture_dir: Path, beep: bool) -> None:
    peer = f"{addr[0]}:{addr[1]}"
    print(f"[+] accept {peer} mode=agent", flush=True)
    send_lock = threading.Lock()
    stats = Stats()
    capture_fp = None
    stop = threading.Event()
    playing = threading.Event()
    vad = EnergyVAD()
    # The cascade agent is a fallback: it does not publish memory events yet.
    # Never allocate an unused transcript worker or SQLite connection here.
    sys_prompt = SYSTEM_PROMPT
    if tools_enabled():
        sys_prompt = SYSTEM_PROMPT.rstrip() + " " + tools_instructions_addon()
    history: list[dict[str, str]] = [{"role": "system", "content": sys_prompt}]
    process_lock = threading.Lock()

    try:
        if capture_dir:
            capture_dir.mkdir(parents=True, exist_ok=True)
            ts = time.strftime("%Y%m%d-%H%M%S")
            path = capture_dir / f"nova-mic-{ts}.pcm"
            capture_fp = open(path, "wb")
            print(f"[*] capture -> {path}", flush=True)

        tools_flag = b" tools=1" if tools_enabled() else b""
        write_frame(conn, TYPE_CTRL, b"HELLO box mode=agent sample=16000" + tools_flag, send_lock)
        if beep:
            tone = make_beep(ms=250, freq=880.0, vol=0.2)
            send_pcm_chunked(conn, tone, send_lock, stats, playing_flag=None)
            print(f"[*] sent answer beep {len(tone)} bytes", flush=True)

        def ticker():
            while not stop.wait(2.0):
                print(f"[stats {peer}] {stats.snapshot()} playing={playing.is_set()}", flush=True)

        threading.Thread(target=ticker, daemon=True).start()

        def process_utterance(pcm: bytes) -> None:
            if not process_lock.acquire(blocking=False):
                print(f"[agent {peer}] skip overlapping utterance", flush=True)
                return
            tick_stop = threading.Event()
            # Hold playing for whole pipeline so VAD won't re-trigger; TCP read loop stays alive.
            playing.set()
            try:
                dur_ms = len(pcm) / BYTES_PER_MS
                print(f"[agent {peer}] utterance {len(pcm)} B ~{dur_ms:.0f}ms → thinking+ASR", flush=True)

                # Immediate comfort beep so user hears progress before ASR/LLM/TTS (~6–8s).
                try:
                    comfort = make_beep(ms=180, freq=740.0, vol=0.18)
                    send_pcm_chunked(conn, comfort, send_lock, stats, playing_flag=None)
                    print(f"[agent {peer}] sent thinking beep {len(comfort)} B", flush=True)
                except Exception as e:
                    if is_socket_closed_error(e):
                        print(f"[agent {peer}] peer closed before thinking beep", flush=True)
                        return
                    print(f"[agent {peer}] thinking beep fail: {e}", flush=True)

                def tick_loop() -> None:
                    # Soft repeating tick every ~1.5s while still processing.
                    while not tick_stop.wait(1.5):
                        if stop.is_set():
                            return
                        try:
                            tick = make_tick()
                            send_pcm_chunked(conn, tick, send_lock, stats, playing_flag=None)
                        except Exception as e:
                            if is_socket_closed_error(e):
                                return
                            print(f"[agent {peer}] tick fail: {e}", flush=True)
                            return

                tick_thread = threading.Thread(target=tick_loop, daemon=True)
                tick_thread.start()

                try:
                    text = asr_pcm(pcm)
                except Exception as e:
                    tick_stop.set()
                    print(f"[agent {peer}] ASR fail: {e}", flush=True)
                    try:
                        write_frame(conn, TYPE_CTRL, b"ERROR asr", send_lock)
                        ap = apology_pcm("抱歉，我没听清，请再说一次。")
                        if ap:
                            send_pcm_chunked(conn, ap, send_lock, stats, playing_flag=None)
                    except Exception as e2:
                        if is_socket_closed_error(e2):
                            print(f"[agent {peer}] peer closed during ASR apology", flush=True)
                        else:
                            print(f"[agent {peer}] apology fail: {e2}", flush=True)
                    return

                if not text:
                    tick_stop.set()
                    print(f"[agent {peer}] ASR empty, skip", flush=True)
                    return
                print(f"[agent {peer}] ASR: {text}", flush=True)

                # Hybrid whitelist: keyword intent → execute → TTS (skip LLM)
                if tools_enabled():
                    intent = match_intent(text)
                    if intent:
                        ok, result = tools_execute(intent)
                        reply = speakable_from_tool(result)
                        print(
                            f"[agent {peer}] tool={intent} ok={ok} reply={reply[:80]}",
                            flush=True,
                        )
                        history.append({"role": "user", "content": text})
                        history.append({"role": "assistant", "content": reply})
                        max_msgs = 1 + HISTORY_TURNS * 2
                        if len(history) > max_msgs:
                            history[:] = [history[0]] + history[-(max_msgs - 1):]
                        tick_stop.set()
                        try:
                            tick_thread.join(timeout=0.2)
                        except Exception:
                            pass
                        try:
                            out_pcm = tts_to_pcm(reply)
                            print(
                                f"[agent {peer}] TTS pcm {len(out_pcm)} B "
                                f"~{len(out_pcm)/BYTES_PER_MS:.0f}ms",
                                flush=True,
                            )
                            send_pcm_chunked(conn, out_pcm, send_lock, stats, playing_flag=None)
                        except Exception as e:
                            if is_socket_closed_error(e):
                                print(f"[agent {peer}] peer closed during tool TTS", flush=True)
                                return
                            print(f"[agent {peer}] tool TTS fail: {e}", flush=True)
                        vad.reset()
                        return

                history.append({"role": "user", "content": text})
                # keep system + last N user/assistant messages
                max_msgs = 1 + HISTORY_TURNS * 2
                if len(history) > max_msgs:
                    history[:] = [history[0]] + history[-(max_msgs - 1):]

                try:
                    reply = llm_chat(history)
                except Exception as e:
                    tick_stop.set()
                    print(f"[agent {peer}] LLM fail: {e}", flush=True)
                    if history and history[-1]["role"] == "user":
                        history.pop()
                    try:
                        write_frame(conn, TYPE_CTRL, b"ERROR llm", send_lock)
                        ap = apology_pcm("抱歉，我刚才走神了，请再说一次。")
                        if ap:
                            send_pcm_chunked(conn, ap, send_lock, stats, playing_flag=None)
                    except Exception as e2:
                        if is_socket_closed_error(e2):
                            print(f"[agent {peer}] peer closed during LLM apology", flush=True)
                        else:
                            print(f"[agent {peer}] apology fail: {e2}", flush=True)
                    return

                print(f"[agent {peer}] LLM: {reply}", flush=True)
                history.append({"role": "assistant", "content": reply})

                try:
                    out_pcm = tts_to_pcm(reply)
                except Exception as e:
                    tick_stop.set()
                    print(f"[agent {peer}] TTS fail: {e}", flush=True)
                    try:
                        write_frame(conn, TYPE_CTRL, b"ERROR tts", send_lock)
                        ap = apology_pcm("抱歉，语音合成失败了。")
                        if ap:
                            send_pcm_chunked(conn, ap, send_lock, stats, playing_flag=None)
                    except Exception as e2:
                        if is_socket_closed_error(e2):
                            print(f"[agent {peer}] peer closed during TTS apology", flush=True)
                        else:
                            print(f"[agent {peer}] apology fail: {e2}", flush=True)
                    return

                # Stop ticks before sending the real reply
                tick_stop.set()
                try:
                    tick_thread.join(timeout=0.2)
                except Exception:
                    pass

                print(f"[agent {peer}] TTS pcm {len(out_pcm)} B ~{len(out_pcm)/BYTES_PER_MS:.0f}ms", flush=True)
                try:
                    send_pcm_chunked(conn, out_pcm, send_lock, stats, playing_flag=None)
                except Exception as e:
                    if is_socket_closed_error(e):
                        print(f"[agent {peer}] peer closed before/during TTS playback (dropped {len(out_pcm)} B)", flush=True)
                        return
                    raise
                # drain: after playback, reset VAD so leftover echo isn't treated as speech
                vad.reset()
            except Exception as e:
                if is_socket_closed_error(e):
                    print(f"[agent {peer}] peer closed mid-pipeline: {e}", flush=True)
                else:
                    print(f"[agent {peer}] process error: {e}", flush=True)
                    traceback.print_exc()
            finally:
                tick_stop.set()
                playing.clear()
                process_lock.release()

        while not stop.is_set():
            typ, payload = read_frame(conn)
            if typ == TYPE_CTRL:
                text = payload.decode("utf-8", "replace")
                print(f"[ctrl {peer}] {text}", flush=True)
                if TOKEN and text.startswith("AUTH "):
                    if text[5:].strip() != TOKEN:
                        write_frame(conn, TYPE_CTRL, b"AUTH_FAIL", send_lock)
                        break
                    write_frame(conn, TYPE_CTRL, b"AUTH_OK", send_lock)
                continue
            if typ != TYPE_PCM:
                continue
            stats.add_rx(len(payload))
            if capture_fp:
                capture_fp.write(payload)

            # MVP: ignore mic during TTS playback (no barge-in)
            if playing.is_set():
                continue

            utter = vad.feed(payload)
            if utter:
                threading.Thread(target=process_utterance, args=(utter,), daemon=True).start()
    except Exception as e:
        print(f"[-] {peer} end: {e}", flush=True)
    finally:
        stop.set()
        try:
            conn.close()
        except Exception:
            pass
        if capture_fp:
            capture_fp.close()
        print(f"[*] closed {peer} final {stats.snapshot()}", flush=True)



def handle_omni(conn, addr, capture_dir: Path, beep: bool) -> None:
    """Native multimodal via Bailian Qwen-Omni Realtime (Manual + local EnergyVAD)."""
    peer = f"{addr[0]}:{addr[1]}"
    voice = os.environ.get("BRIDGE_OMNI_VOICE", OMNI_DEFAULT_VOICE).strip() or OMNI_DEFAULT_VOICE
    print(f"[+] accept {peer} mode=omni voice={voice}", flush=True)
    send_lock = threading.Lock()
    stats = Stats()
    capture_fp = None
    stop = threading.Event()
    playing = threading.Event()
    vad = EnergyVAD()
    process_lock = threading.Lock()
    sess: OmniRealtimeSession | None = None
    play_q: list[bytes] = []
    play_q_lock = threading.Lock()
    play_wake = threading.Event()
    # Optional nonblocking memory relay (same as handle_agent). None when
    # WHALE_MEMORY_DB unset or whale_core unavailable; on_omni_event checks it.
    transcript_recorder = None
    _memory_store = None
    call_session_id = uuid4().hex
    memory_path = os.environ.get("WHALE_MEMORY_DB", "").strip()
    if memory_path:
        try:
            _core_src = Path(__file__).resolve().parents[3] / "whale-core" / "src"
            if _core_src.is_dir() and str(_core_src) not in sys.path:
                sys.path.insert(0, str(_core_src))
            from whale_core.memory.sqlite_store import SqliteMemoryStore
            from whale_core.core.companion import RealtimeTranscriptRecorder
            _memory_store = SqliteMemoryStore(memory_path)
            transcript_recorder = RealtimeTranscriptRecorder(_memory_store)
        except Exception as ex:
            print(f"[omni] memory relay unavailable: {type(ex).__name__}", flush=True)
            transcript_recorder = None

    def enqueue_audio(pcm16: bytes) -> None:
        if not pcm16:
            return
        playing.set()
        with play_q_lock:
            play_q.append(pcm16)
        play_wake.set()

    def player_loop() -> None:
        """Drain omni audio deltas to phone at roughly realtime pace."""
        # Only reset VAD on playing→idle transition. Resetting every empty-queue
        # poll (previous bug) wiped EnergyVAD mid-utterance so Manual mode never
        # reached commit/response.create — user heard beep only, never Omni TTS.
        ever_played = False
        while not stop.is_set():
            play_wake.wait(timeout=0.2)
            play_wake.clear()
            drained = False
            while not stop.is_set():
                with play_q_lock:
                    if not play_q:
                        break
                    piece = play_q.pop(0)
                drained = True
                ever_played = True
                try:
                    # piece is already 16 kHz; send without clearing playing mid-stream
                    chunk = max(BYTES_PER_MS * PCM_CHUNK_MS, 640)
                    if chunk % 2:
                        chunk += 1
                    i = 0
                    t_start = time.monotonic()
                    sent_ms = 0.0
                    while i < len(piece) and not stop.is_set():
                        part = piece[i : i + chunk]
                        if len(part) % 2:
                            part = part[:-1]
                        if not part:
                            break
                        write_frame(conn, TYPE_PCM, part, send_lock)
                        stats.add_tx(len(part))
                        i += len(part)
                        sent_ms += len(part) / BYTES_PER_MS
                        target = t_start + sent_ms / 1000.0 * 0.85
                        now = time.monotonic()
                        if target > now:
                            time.sleep(min(0.05, target - now))
                except Exception as e:
                    if is_socket_closed_error(e):
                        stop.set()
                        return
                    print(f"[omni {peer}] play fail: {e}", flush=True)
                    return
            # Clear playing only after we actually drained audio and model is idle.
            if not drained:
                continue
            with play_q_lock:
                empty = not play_q
            if empty and sess is not None and not sess.responding.is_set():
                time.sleep(0.15)
                with play_q_lock:
                    empty2 = not play_q
                if empty2 and not sess.responding.is_set():
                    playing.clear()
                    if ever_played:
                        vad.reset()
                        ever_played = False

    try:
        if capture_dir:
            capture_dir.mkdir(parents=True, exist_ok=True)
            ts = time.strftime("%Y%m%d-%H%M%S")
            path = capture_dir / f"nova-mic-{ts}.pcm"
            capture_fp = open(path, "wb")
            print(f"[*] capture -> {path}", flush=True)

        tools_tag = " tools=1" if tools_enabled() else ""
        write_frame(
            conn,
            TYPE_CTRL,
            f"HELLO box mode=omni sample=16000 voice={voice}{tools_tag}".encode(),
            send_lock,
        )

        use_tools = tools_enabled()
        if use_tools:
            print(f"[omni {peer}] BRIDGE_TOOLS=1 native+keyword whitelist", flush=True)

        def handle_omni_function_call(name: str, call_id: str, arguments: str) -> None:
            """Native / spoken-XML tool call → whitelist execute → speak result."""
            nonlocal sess
            args: dict = {}
            if arguments and arguments.strip():
                try:
                    import json as _json
                    parsed = _json.loads(arguments)
                    if isinstance(parsed, dict):
                        args = parsed
                except Exception:
                    args = {}
            ok, result = tools_execute(name, args)
            spoken = speakable_from_tool(result)
            kind = "spoken_xml" if call_id.startswith("call_spoken_") else "native"
            print(f"[omni {peer}] {kind}_tool={name} ok={ok}", flush=True)
            if sess is None:
                return
            try:
                if call_id.startswith("call_spoken_"):
                    # No real function_call item on server — cancel + speak result.
                    try:
                        sess._send({"type": "response.cancel"})
                    except Exception:
                        pass
                    sess._pending_calls.pop(call_id, None)
                    playing.set()
                    sess.speak_text(spoken)
                else:
                    sess.submit_tool_output(call_id, spoken)
            except Exception as e:
                print(f"[omni {peer}] tool reply fail: {e}", flush=True)
                playing.clear()
                if sess is not None:
                    sess.responding.clear()

        keyword_handled = threading.Event()

        def on_omni_event(ev: dict) -> None:
            """Hybrid keyword router: Chinese/English intent → whitelist → speak.

            Prefer keyword path for known triggers (reliable). Native Omni tools
            still handle paraphrases that miss the keyword list.
            """
            nonlocal sess
            t = ev.get("type") or ""
            if transcript_recorder is not None:
                if t == "conversation.item.input_audio_transcription.completed":
                    transcript_recorder.offer(call_session_id, "user", str(ev.get("transcript") or ""))
                elif t == "response.audio_transcript.done":
                    transcript_recorder.offer(call_session_id, "assistant", str(ev.get("transcript") or ""))
            if not use_tools or sess is None:
                return
            if t != "conversation.item.input_audio_transcription.completed":
                return
            user_tx = (ev.get("transcript") or "").strip()
            if not user_tx:
                return
            intent = match_intent(user_tx)
            if not intent:
                return
            if keyword_handled.is_set():
                return
            keyword_handled.set()

            def _run() -> None:
                try:
                    if sess is None:
                        return
                    # Cancel in-flight model reply / native tool turn; keyword wins.
                    try:
                        sess._send({"type": "response.cancel"})
                    except Exception:
                        pass
                    # Drop any pending native call ids so response.done clears cleanly
                    try:
                        sess._pending_calls.clear()
                    except Exception:
                        pass
                    ok, result = tools_execute(intent)
                    spoken = speakable_from_tool(result)
                    print(f"[omni {peer}] keyword_tool={intent} ok={ok}", flush=True)
                    playing.set()
                    sess.speak_text(spoken)
                except Exception as e:
                    print(f"[omni {peer}] keyword tool fail: {e}", flush=True)
                    playing.clear()
                    if sess is not None:
                        sess.responding.clear()
                finally:
                    time.sleep(0.15)
                    keyword_handled.clear()

            threading.Thread(target=_run, daemon=True, name="omni-kw-tool").start()

        omni_instructions = os.environ.get("BRIDGE_OMNI_INSTRUCTIONS", "").strip()
        if not omni_instructions:
            from omni_realtime import DEFAULT_INSTRUCTIONS as _DI
            omni_instructions = _DI
        omni_instructions = append_persona_card_instructions(omni_instructions, store=_memory_store)
        if use_tools and tools_instructions_addon() not in omni_instructions:
            omni_instructions = omni_instructions.rstrip() + " " + tools_instructions_addon()

        try:
            sess = OmniRealtimeSession(
                voice=voice,
                instructions=omni_instructions,
                tools=TOOL_SCHEMAS if use_tools else None,
                on_audio=enqueue_audio,
                on_event=on_omni_event,
                on_function_call=handle_omni_function_call if use_tools else None,
                log=lambda m: print(m if m.startswith("[omni]") else f"[omni {peer}] {m}", flush=True),
            )
            sess.connect(timeout=20.0)
        except Exception as e:
            print(f"[omni {peer}] connect fail: {e}", flush=True)
            try:
                write_frame(conn, TYPE_CTRL, b"ERROR omni_connect", send_lock)
            except Exception:
                pass
            return

        threading.Thread(target=player_loop, daemon=True, name="omni-play").start()

        if beep:
            try:
                # Louder/longer so earpiece hears it even if Omni greeting is slow.
                tone = make_beep(ms=400, freq=880.0, vol=0.35)
                send_pcm_chunked(conn, tone, send_lock, stats, playing_flag=None)
                print(f"[*] sent answer beep {len(tone)} bytes", flush=True)
            except Exception as e:
                if is_socket_closed_error(e):
                    return
                print(f"[omni {peer}] beep fail: {e}", flush=True)

        # Immediate greeting so user hears Omni TTS without waiting for VAD.
        try:
            if sess is not None:
                sess.responding.set()
                playing.set()
                sess._send({
                    "type": "response.create",
                    "response": {
                        "modalities": ["text", "audio"],
                        "instructions": "用很短的中文口语打招呼，只说：喂，你好。",
                    },
                })
                print(f"[omni {peer}] requested greeting response", flush=True)
        except Exception as e:
            print(f"[omni {peer}] greeting fail: {e}", flush=True)
            playing.clear()
            if sess is not None:
                sess.responding.clear()

        def ticker():
            while not stop.wait(2.0):
                resp = sess.responding.is_set() if sess else False
                print(
                    f"[stats {peer}] {stats.snapshot()} playing={playing.is_set()} responding={resp}",
                    flush=True,
                )

        threading.Thread(target=ticker, daemon=True).start()

        def on_utterance_end(utter: bytes) -> None:
            """Append completed local-VAD utterance, then Manual commit + response.create."""
            if sess is None:
                return
            if not process_lock.acquire(blocking=False):
                print(f"[omni {peer}] skip overlapping commit", flush=True)
                return
            playing.set()  # block mic until reply finishes (or fails)
            keyword_handled.clear()
            try:
                dur_ms = len(utter) / BYTES_PER_MS
                print(f"[omni {peer}] VAD end {len(utter)} B ~{dur_ms:.0f}ms → append+commit+response", flush=True)
                chunk = 3200
                for i in range(0, len(utter), chunk):
                    if stop.is_set():
                        return
                    sess.append_pcm(utter[i : i + chunk])
                sess.commit_and_respond()
            except Exception as e:
                print(f"[omni {peer}] commit fail: {e}", flush=True)
                playing.clear()
            finally:
                process_lock.release()

        while not stop.is_set():
            typ, payload = read_frame(conn)
            if typ == TYPE_CTRL:
                text = payload.decode("utf-8", "replace")
                print(f"[ctrl {peer}] {text}", flush=True)
                if TOKEN and text.startswith("AUTH "):
                    if text[5:].strip() != TOKEN:
                        write_frame(conn, TYPE_CTRL, b"AUTH_FAIL", send_lock)
                        break
                    write_frame(conn, TYPE_CTRL, b"AUTH_OK", send_lock)
                continue
            if typ != TYPE_PCM:
                continue
            stats.add_rx(len(payload))
            if capture_fp:
                capture_fp.write(payload)

            # MVP: ignore mic during playback / in-flight response (no barge-in)
            if playing.is_set() or (sess is not None and sess.responding.is_set()):
                continue

            utter = vad.feed(payload)
            if utter:
                threading.Thread(target=on_utterance_end, args=(utter,), daemon=True).start()
    except Exception as e:
        print(f"[-] {peer} end: {e}", flush=True)
    finally:
        stop.set()
        play_wake.set()
        if sess is not None:
            try:
                sess.close()
            except Exception:
                pass
        try:
            conn.close()
        except Exception:
            pass
        if capture_fp:
            capture_fp.close()
        if transcript_recorder is not None:
            try:
                transcript_recorder.close()
            except Exception as ex:
                print(f"[omni {peer}] memory relay close fail: {type(ex).__name__}", flush=True)
        if _memory_store is not None:
            try:
                _memory_store.close()
            except Exception:
                pass
        print(f"[*] closed {peer} final {stats.snapshot()}", flush=True)


def handle_client(conn, addr, capture_dir: Path | None, mode: str, beep: bool, echo: bool) -> None:
    try:
        # Authenticate before HELLO, model access or PCM processing.
        if TOKEN:
            conn.settimeout(8)
            typ, payload = read_frame(conn)
            if typ != TYPE_CTRL or not payload.startswith(b"AUTH "):
                raise PermissionError("audio authentication required")
            if not hmac.compare_digest(payload[5:].decode("utf-8", "replace"), TOKEN):
                raise PermissionError("audio authentication failed")
            conn.settimeout(None)
            conn.sendall(struct.pack("!IB", 7, TYPE_CTRL) + b"AUTH_OK")
        if mode == "echo":
            handle_echo(conn, addr, capture_dir, echo=echo, beep=beep)
        elif mode == "omni":
            handle_omni(conn, addr, capture_dir, beep=beep)
        else:
            handle_agent(conn, addr, capture_dir, beep=beep)
    except Exception as e:
        print(f"[-] handler crash {addr}: {e}", flush=True)
        traceback.print_exc()
        try:
            conn.close()
        except Exception:
            pass


def main() -> int:
    default_mode = os.environ.get("BRIDGE_MODE", "agent").strip().lower() or "agent"
    ap = argparse.ArgumentParser(description="Nova fake-call audio bridge")
    ap.add_argument("--host", default=os.environ.get("BRIDGE_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("BRIDGE_PORT", "18799")))
    ap.add_argument("--mode", choices=("echo", "agent", "omni"), default=default_mode)
    ap.add_argument("--no-echo", action="store_true", help="echo mode: do not loopback mic")
    ap.add_argument("--no-beep", action="store_true")
    ap.add_argument("--capture-dir", default=os.environ.get("BRIDGE_CAPTURE_DIR", ""), help="Opt-in PCM capture directory; disabled by default")
    args = ap.parse_args()
    # TCP PCM is not encrypted. Use loopback/USB reverse or a private tunnel.
    if args.host not in ("127.0.0.1", "::1", "localhost") and not TOKEN:
        ap.error("BRIDGE_TOKEN required for non-loopback bind")
    capture_dir = Path(args.capture_dir) if args.capture_dir else None

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.host, args.port))
    sock.listen(4)
    print(
        f"[*] audio-bridge listen {args.host}:{args.port} mode={args.mode} "
        f"echo={not args.no_echo if args.mode=='echo' else 'n/a'} "
        f"tools={'1' if tools_enabled() else '0'}",
        flush=True,
    )

    while True:
        try:
            conn, addr = sock.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            t = threading.Thread(
                target=handle_client,
                args=(conn, addr, capture_dir, args.mode, not args.no_beep, not args.no_echo),
                daemon=True,
            )
            t.start()
        except Exception as e:
            print(f"[-] accept loop error: {e}", flush=True)
            time.sleep(0.2)


if __name__ == "__main__":
    try:
        sys.exit(main() or 0)
    except KeyboardInterrupt:
        print("bye", flush=True)
        sys.exit(0)
