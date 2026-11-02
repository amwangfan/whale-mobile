#!/usr/bin/env python3
"""ASR/TTS via Alibaba Bailian (DashScope); LLM via AgentScope. Never logs API keys."""
from __future__ import annotations

import base64
import io
import json
import os
import struct
import urllib.error
import urllib.request
import wave
from typing import Any

# --- providers ---
DASHSCOPE_BASE = os.environ.get(
    "DASHSCOPE_BASE", "https://dashscope.aliyuncs.com/api/v1"
).rstrip("/")
AGENTSCOPE_BASE = os.environ.get(
    "AGENTSCOPE_BASE", "https://platform.agentscope.io/v1"
).rstrip("/")

# ASR: fun-asr-flash sync + base64 (bills by audio seconds). Override with BRIDGE_ASR_MODEL.
ASR_MODEL = os.environ.get("BRIDGE_ASR_MODEL", "fun-asr-flash-2026-06-15")
# TTS: CosyVoice on DashScope
TTS_MODEL = os.environ.get("BRIDGE_TTS_MODEL", "cosyvoice-v3-flash")
TTS_VOICE = os.environ.get("BRIDGE_TTS_VOICE", "longanhuan")
# LLM stays on AgentScope; qwen3.8-flash is typically quicker for short phone replies.
# Override with BRIDGE_LLM_MODEL=deepseek-v4.1-flash (or other) if needed.
LLM_MODEL = os.environ.get("BRIDGE_LLM_MODEL", "qwen3.8-flash")
SYSTEM_PROMPT = os.environ.get(
    "BRIDGE_SYSTEM_PROMPT",
    "你是电话助手。只用极短中文口语回复，最多一两句、合计约20字以内，不要解释、不要列表、不要反问长句。",
)
SAMPLE_RATE = 16000

# last call usage (characters / duration) for smoke / debug; never includes keys
LAST_ASR_USAGE: dict[str, Any] = {}
LAST_TTS_USAGE: dict[str, Any] = {}


def _dashscope_key() -> str:
    key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not key:
        raise RuntimeError("DASHSCOPE_API_KEY not set")
    return key


def _agentscope_key() -> str:
    key = os.environ.get("AGENTSCOPE_API_KEY", "").strip()
    if not key:
        raise RuntimeError("AGENTSCOPE_API_KEY not set")
    return key


def _redact(s: str) -> str:
    for env_name in ("DASHSCOPE_API_KEY", "AGENTSCOPE_API_KEY"):
        key = os.environ.get(env_name, "")
        if key and key in s:
            s = s.replace(key, "***")
    return s


def _post(
    base: str,
    path: str,
    body: dict[str, Any],
    bearer: str,
    extra_headers: dict[str, str] | None = None,
    timeout: float = 60.0,
) -> dict[str, Any]:
    headers = {
        "Authorization": f"Bearer {bearer}",
        "Content-Type": "application/json",
    }
    if extra_headers:
        headers.update(extra_headers)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", "replace")[:400]
        raise RuntimeError(f"HTTP {e.code} {path}: {_redact(err_body)}") from None
    except Exception as e:
        raise RuntimeError(f"request failed {path}: {_redact(str(e))}") from None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"bad JSON from {path}: {raw[:200]}") from e


def pcm_to_wav_bytes(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()


def wav_bytes_to_pcm(wav_bytes: bytes, target_rate: int = SAMPLE_RATE) -> bytes:
    """Extract PCM from WAV; tolerate oversized 'data' chunk sizes from TTS CDN."""
    if len(wav_bytes) < 44 or wav_bytes[:4] != b"RIFF":
        return wav_bytes
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            ch, sw, rate, nframes = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            if nframes > 0 and nframes < 50_000_000 and sw == 2 and ch in (1, 2):
                frames = w.readframes(nframes)
                pcm = _to_mono16(frames, ch, sw)
                if rate != target_rate:
                    pcm = _resample_linear(pcm, rate, target_rate)
                return pcm
    except Exception:
        pass

    i = 12
    fmt_ch, fmt_sw, fmt_rate = 1, 2, target_rate
    while i + 8 <= len(wav_bytes):
        cid = wav_bytes[i : i + 4]
        csz = struct.unpack_from("<I", wav_bytes, i + 4)[0]
        payload_start = i + 8
        if cid == b"fmt " and csz >= 16:
            _af, ch, rate, _br, _ba, bits = struct.unpack_from("<HHIIHH", wav_bytes, payload_start)
            fmt_ch, fmt_sw, fmt_rate = ch, bits // 8, rate
        elif cid == b"data":
            end = payload_start + csz
            if csz > len(wav_bytes) - payload_start or csz > 100_000_000:
                pcm_raw = wav_bytes[payload_start:]
            else:
                pcm_raw = wav_bytes[payload_start:end]
            if len(pcm_raw) % 2:
                pcm_raw = pcm_raw[:-1]
            pcm = _to_mono16(pcm_raw, fmt_ch, fmt_sw)
            if fmt_rate != target_rate:
                pcm = _resample_linear(pcm, fmt_rate, target_rate)
            return pcm
        step = 8 + csz + (csz & 1)
        if step <= 0:
            break
        i += step
    raise RuntimeError("WAV has no data chunk")


def _to_mono16(frames: bytes, channels: int, sampwidth: int) -> bytes:
    if sampwidth != 2:
        raise RuntimeError(f"unsupported sampwidth {sampwidth}")
    if channels == 1:
        return frames
    if channels != 2:
        raise RuntimeError(f"unsupported channels {channels}")
    n = len(frames) // 4
    out = bytearray(n * 2)
    for i in range(n):
        l, r = struct.unpack_from("<hh", frames, i * 4)
        struct.pack_into("<h", out, i * 2, max(-32768, min(32767, (l + r) // 2)))
    return bytes(out)


def _resample_linear(pcm: bytes, src: int, dst: int) -> bytes:
    if src == dst or not pcm:
        return pcm
    n = len(pcm) // 2
    samples = struct.unpack(f"<{n}h", pcm)
    out_n = max(1, int(round(n * dst / src)))
    out = bytearray(out_n * 2)
    for i in range(out_n):
        x = i * (n - 1) / max(1, out_n - 1)
        j = int(x)
        t = x - j
        a = samples[j]
        b = samples[min(j + 1, n - 1)]
        struct.pack_into("<h", out, i * 2, int(a + (b - a) * t))
    return bytes(out)


def _extract_asr_text(resp: dict[str, Any]) -> str:
    # Fun-ASR-Flash / multimodal shape
    out = resp.get("output") or {}
    if isinstance(out, dict):
        text = (out.get("text") or "").strip()
        if text:
            return text
        sent = out.get("sentence") or {}
        if isinstance(sent, dict):
            text = (sent.get("text") or "").strip()
            if text:
                return text
        nested = out.get("output") or {}
        if isinstance(nested, dict):
            text = (nested.get("text") or "").strip()
            if text:
                return text
            sent2 = nested.get("sentence") or {}
            if isinstance(sent2, dict):
                text = (sent2.get("text") or "").strip()
                if text:
                    return text
    return (resp.get("text") or "").strip()


def asr_wav(wav_bytes: bytes) -> str:
    """DashScope sync ASR with base64 Data URI (no public URL needed)."""
    b64 = base64.b64encode(wav_bytes).decode("ascii")
    body = {
        "model": ASR_MODEL,
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_audio",
                            "input_audio": {"data": f"data:audio/wav;base64,{b64}"},
                        }
                    ],
                }
            ]
        },
        "parameters": {
            "format": "wav",
            "sample_rate": str(SAMPLE_RATE),
            "language_hints": ["zh", "en"],
        },
    }
    resp = _post(
        DASHSCOPE_BASE,
        "/services/aigc/multimodal-generation/generation",
        body,
        _dashscope_key(),
        extra_headers={"X-DashScope-SSE": "disable"},
        timeout=90.0,
    )
    usage = resp.get("usage") or {}
    if not usage and isinstance(resp.get("output"), dict):
        usage = (resp["output"].get("usage") or {}) if isinstance(resp["output"].get("usage"), dict) else {}
    LAST_ASR_USAGE.clear()
    if isinstance(usage, dict):
        LAST_ASR_USAGE.update(usage)
    return _extract_asr_text(resp)


def asr_pcm(pcm: bytes) -> str:
    return asr_wav(pcm_to_wav_bytes(pcm))


def llm_chat(messages: list[dict[str, str]], model: str | None = None) -> str:
    """messages include system + user/assistant turns. Returns assistant text. AgentScope only."""
    body = {
        "model": model or LLM_MODEL,
        "messages": messages,
        "max_tokens": 64,
        "temperature": 0.5,
    }
    resp = _post(
        AGENTSCOPE_BASE,
        "/chat/completions",
        body,
        _agentscope_key(),
        timeout=60.0,
    )
    choices = resp.get("choices") or []
    if not choices:
        raise RuntimeError("LLM empty choices")
    msg = choices[0].get("message") or {}
    text = (msg.get("content") or msg.get("stringContent") or "").strip()
    if not text:
        resp2 = _post(
            AGENTSCOPE_BASE,
            "/chat/completions",
            {**body, "temperature": 0.3},
            _agentscope_key(),
            timeout=60.0,
        )
        choices2 = resp2.get("choices") or []
        if choices2:
            msg2 = choices2[0].get("message") or {}
            text = (msg2.get("content") or msg2.get("stringContent") or "").strip()
    if not text:
        raise RuntimeError("LLM empty content")
    return text


def tts_to_pcm(text: str, voice: str | None = None) -> bytes:
    """DashScope CosyVoice SpeechSynthesizer → 16 kHz PCM."""
    body = {
        "model": TTS_MODEL,
        "input": {
            "text": text,
            "voice": voice or TTS_VOICE,
            "format": "wav",
            "sample_rate": SAMPLE_RATE,
        },
    }
    resp = _post(
        DASHSCOPE_BASE,
        "/services/audio/tts/SpeechSynthesizer",
        body,
        _dashscope_key(),
        timeout=60.0,
    )
    usage = resp.get("usage") or {}
    LAST_TTS_USAGE.clear()
    if isinstance(usage, dict):
        LAST_TTS_USAGE.update(usage)
    audio = (resp.get("output") or {}).get("audio") or {}
    data_b64 = audio.get("data") or ""
    if data_b64:
        raw = base64.b64decode(data_b64)
        if raw[:4] == b"RIFF":
            return wav_bytes_to_pcm(raw)
        return raw
    url = audio.get("url") or ""
    if not url:
        raise RuntimeError("TTS returned no audio url/data")
    if url.startswith("http://"):
        url = "https://" + url[len("http://") :]
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            wav_bytes = r.read()
    except Exception as e:
        raise RuntimeError(f"TTS download failed: {_redact(str(e))}") from None
    return wav_bytes_to_pcm(wav_bytes)


def apology_pcm(msg: str = "抱歉，刚才出了点问题，请再说一次。") -> bytes:
    try:
        return tts_to_pcm(msg)
    except Exception:
        return b""
