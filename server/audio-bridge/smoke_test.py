#!/usr/bin/env python3
"""Offline smoke: TTS → ASR → LLM → TTS (no phone). Never prints API key."""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from agent_api import (
    ASR_MODEL,
    LAST_ASR_USAGE,
    LAST_TTS_USAGE,
    LLM_MODEL,
    SYSTEM_PROMPT,
    TTS_MODEL,
    TTS_VOICE,
    asr_pcm,
    llm_chat,
    pcm_to_wav_bytes,
    tts_to_pcm,
)


def main() -> int:
    print(f"providers: ASR/TTS=DashScope ASR={ASR_MODEL} TTS={TTS_MODEL} voice={TTS_VOICE} LLM=AgentScope {LLM_MODEL}")
    phrase = "你好，世界。"
    print(f"[1] TTS synthesize: {phrase!r}")
    t0 = time.time()
    pcm = tts_to_pcm(phrase)
    print(f"    pcm_bytes={len(pcm)} dur_ms≈{len(pcm)/32:.0f} elapsed={time.time()-t0:.2f}s usage={LAST_TTS_USAGE}")

    print("[2] ASR from TTS pcm")
    t0 = time.time()
    text = asr_pcm(pcm)
    print(f"    ASR text={text!r} elapsed={time.time()-t0:.2f}s usage={LAST_ASR_USAGE}")
    if not text:
        print("FAIL: empty ASR")
        return 1

    print("[3] LLM reply")
    t0 = time.time()
    reply = llm_chat(
        [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ]
    )
    print(f"    LLM reply={reply!r} elapsed={time.time()-t0:.2f}s")

    print("[4] TTS reply")
    t0 = time.time()
    out = tts_to_pcm(reply)
    out_path = Path(__file__).resolve().parent / "captures" / "smoke-reply.pcm"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(out)
    wav_path = out_path.with_suffix(".wav")
    wav_path.write_bytes(pcm_to_wav_bytes(out))
    print(f"    reply_pcm={len(out)} -> {out_path.name} / {wav_path.name} elapsed={time.time()-t0:.2f}s usage={LAST_TTS_USAGE}")
    print("OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"FAIL: {e}", file=sys.stderr)
        raise SystemExit(1)
