#!/usr/bin/env python3
"""DashScope Qwen-Omni Realtime WebSocket helper (Bailian, not OpenAI).

Endpoint (no WorkspaceId):
  wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime
Auth: Authorization: Bearer $DASHSCOPE_API_KEY

Never logs API keys.
"""
from __future__ import annotations

import base64
import json
import os
import struct
import threading
import time
from typing import Any, Callable

import websocket

DEFAULT_MODEL = os.environ.get(
    "BRIDGE_OMNI_MODEL", "qwen3.8-omni-flash-realtime"
).strip() or "qwen3.8-omni-flash-realtime"
DEFAULT_WS_URL = os.environ.get(
    "BRIDGE_OMNI_WS_URL",
    "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
).rstrip("/")
DEFAULT_VOICE = os.environ.get("BRIDGE_OMNI_VOICE", "Tina").strip() or "Tina"
DEFAULT_INSTRUCTIONS = os.environ.get(
    "BRIDGE_OMNI_INSTRUCTIONS",
    "你是电话助手。只用极短中文口语回复，最多一两句、合计约20字以内，不要解释、不要列表。"
    "需要时可调用白名单工具：查桥状态、打 VoIP 来电、看时间、列工作区、看桥日志、重启桥；"
    "有工具结果时用一两句口语转告用户。",
)
INPUT_RATE = 16000
OUTPUT_RATE = 24000  # pcm24 from server; downsample before phone


def _dashscope_key() -> str:
    key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not key:
        raise RuntimeError("DASHSCOPE_API_KEY not set")
    return key


def _redact(s: str) -> str:
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if key and key in s:
        s = s.replace(key, "***")
    return s


def resample_pcm16_mono(pcm: bytes, src_rate: int, dst_rate: int) -> bytes:
    """Linear resample s16le mono."""
    if src_rate == dst_rate or not pcm:
        return pcm
    if len(pcm) % 2:
        pcm = pcm[:-1]
    n = len(pcm) // 2
    if n <= 0:
        return b""
    samples = struct.unpack(f"<{n}h", pcm)
    out_n = max(1, int(round(n * dst_rate / src_rate)))
    out = bytearray(out_n * 2)
    denom = max(1, out_n - 1)
    for i in range(out_n):
        x = i * (n - 1) / denom
        j = int(x)
        t = x - j
        a = samples[j]
        b = samples[min(j + 1, n - 1)]
        struct.pack_into("<h", out, i * 2, int(a + (b - a) * t))
    return bytes(out)


class OmniRealtimeSession:
    """Synchronous websocket-client session for one phone call.

    Prefer Manual turn_detection (turn_detection=null) so the bridge can reuse
    local EnergyVAD; call commit_and_respond() on utterance end.
    """

    def __init__(
        self,
        *,
        model: str = DEFAULT_MODEL,
        voice: str = DEFAULT_VOICE,
        instructions: str = DEFAULT_INSTRUCTIONS,
        ws_base: str = DEFAULT_WS_URL,
        tools: list[dict[str, Any]] | None = None,
        on_audio: Callable[[bytes], None] | None = None,
        on_event: Callable[[dict[str, Any]], None] | None = None,
        on_function_call: Callable[[str, str, str], None] | None = None,
        log: Callable[[str], None] | None = None,
    ) -> None:
        self.model = model
        self.voice = voice
        self.instructions = instructions
        self.ws_base = ws_base
        self.tools = tools or []
        self.on_audio = on_audio
        self.on_event = on_event
        # (name, call_id, arguments_json) — execute locally then submit_tool_output
        self.on_function_call = on_function_call
        self.log = log or (lambda m: print(m, flush=True))
        self._ws: websocket.WebSocket | None = None
        self._recv_thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._send_lock = threading.Lock()
        self._ready = threading.Event()
        self._error: str | None = None
        self.session_id: str | None = None
        self.output_rate = OUTPUT_RATE
        self.responding = threading.Event()
        self._user_transcript = ""
        self._asst_transcript = ""
        self._pending_calls: dict[str, dict[str, Any]] = {}
        self._response_gen = 0
        self._active_response_id: str | None = None

    def connect(self, timeout: float = 20.0) -> None:
        key = _dashscope_key()
        url = f"{self.ws_base}?model={self.model}"
        self.log(f"[omni] connecting model={self.model} voice={self.voice}")
        try:
            self._ws = websocket.create_connection(
                url,
                header=[f"Authorization: Bearer {key}"],
                timeout=timeout,
            )
        except Exception as e:
            raise RuntimeError(f"omni WS connect failed: {_redact(str(e))}") from None

        # First event should be session.created
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            ev = self._recv_one(timeout=max(0.1, deadline - time.monotonic()))
            if ev is None:
                continue
            if ev.get("type") == "session.created":
                sess = ev.get("session") or {}
                self.session_id = sess.get("id")
                # detect output rate hint
                fmt = sess.get("output_audio_format") or ""
                if "24" in str(fmt):
                    self.output_rate = 24000
                elif "16" in str(fmt):
                    self.output_rate = 16000
                self.log(
                    f"[omni] session.created id={self.session_id} "
                    f"modalities={sess.get('modalities')} voice_default={sess.get('voice')} "
                    f"out={sess.get('output_audio_format')}"
                )
                break
            if ev.get("type") == "error":
                raise RuntimeError(f"omni error before session.created: {_redact(json.dumps(ev.get('error') or ev, ensure_ascii=False))}")
        else:
            raise RuntimeError("omni timeout waiting for session.created")

        self._update_session_manual()
        self._stop.clear()
        self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True, name="omni-recv")
        self._recv_thread.start()
        if not self._ready.wait(timeout=timeout):
            err = self._error or "timeout waiting for session.updated"
            raise RuntimeError(f"omni session.update failed: {err}")

    def _update_session_manual(self) -> None:
        # Flat fields match dashscope.aliyuncs.com session.created shape.
        # Manual: turn_detection null → local EnergyVAD + commit + response.create.
        sess: dict[str, Any] = {
            "modalities": ["text", "audio"],
            "voice": self.voice,
            "instructions": self.instructions,
            "input_audio_format": "pcm16",
            "output_audio_format": "pcm24",
            "turn_detection": None,
        }
        if self.tools:
            # Native Omni Function Calling; incompatible with enable_search.
            sess["tools"] = self.tools
            sess["enable_search"] = False
        self._send({"type": "session.update", "session": sess})
        ntools = len(self.tools)
        self.log(f"[omni] session.update Manual voice={self.voice} tools={ntools}")

    def _send(self, obj: dict[str, Any]) -> None:
        if self._ws is None:
            raise RuntimeError("omni WS not connected")
        data = json.dumps(obj, ensure_ascii=False)
        with self._send_lock:
            try:
                self._ws.send(data)
            except Exception as e:
                raise RuntimeError(f"omni send failed: {_redact(str(e))}") from None

    def _recv_one(self, timeout: float = 30.0) -> dict[str, Any] | None:
        if self._ws is None:
            return None
        self._ws.settimeout(timeout)
        try:
            raw = self._ws.recv()
        except Exception:
            return None
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            self.log(f"[omni] bad JSON len={len(raw) if isinstance(raw, (bytes, str)) else '?'}")
            return None

    def _recv_loop(self) -> None:
        while not self._stop.is_set() and self._ws is not None:
            try:
                ev = self._recv_one(timeout=1.0)
            except Exception as e:
                if not self._stop.is_set():
                    self._error = _redact(str(e))
                    self.log(f"[omni] recv error: {self._error}")
                break
            if ev is None:
                continue
            try:
                self._handle_event(ev)
            except Exception as e:
                self.log(f"[omni] handle event error: {_redact(str(e))}")

    def _handle_event(self, ev: dict[str, Any]) -> None:
        t = ev.get("type") or ""
        if self.on_event:
            try:
                self.on_event(ev)
            except Exception:
                pass

        if t == "session.updated":
            sess = ev.get("session") or {}
            self._ready.set()
            self.log(
                f"[omni] session.updated voice={sess.get('voice')} "
                f"turn_detection={sess.get('turn_detection')} "
                f"out={sess.get('output_audio_format')}"
            )
        elif t == "error":
            err = ev.get("error") or ev
            msg = _redact(json.dumps(err, ensure_ascii=False))
            self._error = msg
            self._ready.set()  # unblock connect waiters with error
            self.responding.clear()
            self.log(f"[omni] error: {msg}")
        elif t == "response.created":
            self.responding.set()
            self._asst_transcript = ""
            self._active_response_id = (ev.get("response") or {}).get("id") or ev.get("response_id")
        elif t == "response.audio.delta":
            b64 = ev.get("delta") or ""
            if b64 and self.on_audio:
                try:
                    pcm24 = base64.b64decode(b64)
                except Exception:
                    return
                pcm16 = resample_pcm16_mono(pcm24, self.output_rate, INPUT_RATE)
                if pcm16:
                    self.on_audio(pcm16)
        elif t == "response.audio_transcript.delta":
            d = ev.get("delta") or ""
            self._asst_transcript += d
        elif t == "response.audio_transcript.done":
            self._asst_transcript = ev.get("transcript") or self._asst_transcript
            if self._asst_transcript:
                self.log(f"[omni] asst: {self._asst_transcript}")
            # Some Omni builds speak tool XML instead of function_call events.
            if self.on_function_call and self._asst_transcript:
                self._maybe_parse_spoken_tool(self._asst_transcript)
        elif t == "response.text.delta":
            d = ev.get("delta") or ""
            if d and not self._asst_transcript:
                self._asst_transcript += d
        elif t == "conversation.item.input_audio_transcription.completed":
            self._user_transcript = ev.get("transcript") or ""
            if self._user_transcript:
                self.log(f"[omni] user: {self._user_transcript}")
        elif t == "conversation.item.input_audio_transcription.delta":
            # streaming preview; ignore spam in default log
            pass
        elif t == "response.function_call_arguments.delta":
            # streaming args; wait for .done
            pass
        elif t == "response.function_call_arguments.done":
            name = ev.get("name") or ""
            call_id = ev.get("call_id") or ""
            arguments = ev.get("arguments") or "{}"
            self.log(f"[omni] function_call name={name} call_id={call_id}")
            self._pending_calls[call_id] = {
                "name": name,
                "call_id": call_id,
                "arguments": arguments,
            }
            if self.on_function_call and name and call_id:
                try:
                    self.on_function_call(name, call_id, arguments)
                except Exception as e:
                    self.log(f"[omni] on_function_call error: {_redact(str(e))}")
        elif t == "response.done":
            resp = ev.get("response") or {}
            st = resp.get("status")
            rid = resp.get("id")
            self.log(f"[omni] response.done status={st} id={rid}")
            # Ignore stale/cancelled dones after a newer response.create (tool speak).
            if rid and self._active_response_id and rid != self._active_response_id:
                self.log(f"[omni] ignore stale response.done id={rid}")
            elif self._pending_calls:
                pass  # wait for tool output + continuation
            else:
                self.responding.clear()
        elif t in (
            "input_audio_buffer.committed",
            "input_audio_buffer.cleared",
            "input_audio_buffer.speech_started",
            "input_audio_buffer.speech_stopped",
            "conversation.item.created",
            "response.output_item.added",
            "response.output_item.done",
            "response.content_part.added",
            "response.content_part.done",
            "rate_limits.updated",
            "output_audio_buffer.started",
            "output_audio_buffer.stopped",
            "output_audio_buffer.cleared",
        ):
            pass
        else:
            # keep unknown types visible once
            if t and t not in ("session.created",):
                self.log(f"[omni] event {t}")

    def append_pcm(self, pcm: bytes) -> None:
        """Append 16 kHz s16le mono PCM (raw) to input buffer."""
        if not pcm:
            return
        if len(pcm) % 2:
            pcm = pcm[:-1]
        if not pcm:
            return
        b64 = base64.b64encode(pcm).decode("ascii")
        self._send({"type": "input_audio_buffer.append", "audio": b64})

    def commit_and_respond(self) -> None:
        """Manual mode: commit buffer then request a response."""
        self._send({"type": "input_audio_buffer.commit"})
        self._send({"type": "response.create"})
        self.responding.set()

    def _maybe_parse_spoken_tool(self, transcript: str) -> None:
        """If model emitted <tool>{"name":...}</tool> in speech, synthesize a call."""
        import re as _re
        import uuid as _uuid
        m = _re.search(r"<tool>\s*(\{.*?\})\s*</tool>", transcript, _re.I | _re.S)
        if m:
            try:
                obj = json.loads(m.group(1))
            except Exception:
                return
            name = str(obj.get("name") or "")
            args = json.dumps(obj.get("arguments") or {}, ensure_ascii=False)
        else:
            m2 = _re.search(
                r'\{\s*"name"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*(\{.*?\})\s*\}',
                transcript,
                _re.S,
            )
            if not m2:
                return
            name, args = m2.group(1), m2.group(2)
        if not name or not self.on_function_call:
            return
        call_id = "call_spoken_" + _uuid.uuid4().hex[:12]
        self.log(f"[omni] spoken_tool_xml name={name}")
        self._pending_calls[call_id] = {"name": name, "call_id": call_id, "arguments": args}
        try:
            self.on_function_call(name, call_id, args)
        except Exception as e:
            self.log(f"[omni] spoken tool handler error: {_redact(str(e))}")

    def submit_tool_output(self, call_id: str, output: str) -> None:
        """Return function_call_output then request spoken continuation."""
        self._pending_calls.pop(call_id, None)
        self._send({
            "type": "conversation.item.create",
            "item": {
                "type": "function_call_output",
                "call_id": call_id,
                "output": output[:2000],
            },
        })
        self._response_gen += 1
        self._active_response_id = None
        self._send({"type": "response.create"})
        self.responding.set()
        self.log(f"[omni] submitted tool output call_id={call_id}")

    def speak_text(self, text: str) -> None:
        """Force a short spoken reply (hybrid keyword path / inject tool result)."""
        text = (text or "").strip()
        if not text:
            return
        self._response_gen += 1
        self._active_response_id = None  # filled on response.created
        self.responding.set()
        self._send({
            "type": "response.create",
            "response": {
                "modalities": ["text", "audio"],
                "instructions": (
                    f"用一两句极短中文口语把下面结果告诉用户，不要解释工具名："
                    f"{text[:300]}"
                ),
            },
        })
        self.log("[omni] requested speak_text response")

    def close(self) -> None:
        self._stop.set()
        ws = self._ws
        self._ws = None
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass
        if self._recv_thread and self._recv_thread.is_alive():
            self._recv_thread.join(timeout=2.0)
        self.responding.clear()
        self.log("[omni] WS closed")


def smoke_once(
    pcm_in: bytes,
    *,
    out_path: str | None = None,
    voice: str | None = None,
    timeout: float = 45.0,
) -> dict[str, Any]:
    """Offline Manual turn: send PCM, collect reply audio+transcripts. No phone."""
    audio_out = bytearray()
    events: list[str] = []
    done = threading.Event()
    user_tx = ""
    asst_tx = ""

    def on_audio(pcm16: bytes) -> None:
        # smoke stores 16k for phone compatibility; also keep note of rate
        audio_out.extend(pcm16)

    def on_event(ev: dict[str, Any]) -> None:
        nonlocal user_tx, asst_tx
        t = ev.get("type") or ""
        events.append(t)
        if t == "conversation.item.input_audio_transcription.completed":
            user_tx = ev.get("transcript") or user_tx
        elif t == "response.audio_transcript.done":
            asst_tx = ev.get("transcript") or asst_tx
        elif t == "response.done":
            done.set()
        elif t == "error":
            done.set()

    sess = OmniRealtimeSession(
        voice=voice or DEFAULT_VOICE,
        on_audio=on_audio,
        on_event=on_event,
        log=lambda m: print(m, flush=True),
    )
    t0 = time.time()
    sess.connect(timeout=20.0)
    # stream in ~100ms chunks
    chunk = 3200
    for off in range(0, len(pcm_in), chunk):
        sess.append_pcm(pcm_in[off : off + chunk])
        time.sleep(0.01)
    sess.commit_and_respond()
    if not done.wait(timeout=timeout):
        sess.close()
        raise RuntimeError("omni smoke timeout waiting for response.done")
    # allow trailing audio deltas
    time.sleep(0.3)
    err = sess._error
    sess.close()
    if err and not audio_out:
        raise RuntimeError(f"omni smoke error: {err}")
    if out_path:
        Path = __import__("pathlib").Path
        p = Path(out_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(bytes(audio_out))
    return {
        "user": user_tx,
        "assistant": asst_tx,
        "audio_bytes": len(audio_out),
        "elapsed_s": round(time.time() - t0, 2),
        "voice": voice or DEFAULT_VOICE,
        "model": DEFAULT_MODEL,
        "out_path": out_path,
        "error": err,
    }


if __name__ == "__main__":
    import sys
    from pathlib import Path

    captures = Path(__file__).resolve().parent / "captures"
    src = captures / "smoke-reply.pcm"
    if not src.exists():
        print(f"FAIL: missing {src}", file=sys.stderr)
        raise SystemExit(1)
    pcm = src.read_bytes()[: int(16000 * 2.5 * 2)]
    out = captures / "omni-smoke-reply.pcm"
    try:
        result = smoke_once(pcm, out_path=str(out))
        print("SMOKE_OK", json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(f"FAIL: {_redact(str(e))}", file=sys.stderr)
        raise SystemExit(1)
