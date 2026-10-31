#!/usr/bin/env bash
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
PIDF="$DIR/bridge.pid"
LOG="$DIR/bridge.log"
PORT=${BRIDGE_PORT:-18799}
HOST=${BRIDGE_HOST:-127.0.0.1}
MODE=${BRIDGE_MODE:-agent}

if [[ -f "$PIDF" ]] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "already running pid=$(cat "$PIDF")"
  exit 0
fi
# PCM capture disabled by default; opt in via BRIDGE_CAPTURE_DIR.

# Prefer project venv (websocket-client for omni)
if [[ -x "$DIR/.venv/bin/python" ]]; then
  PY="$DIR/.venv/bin/python"
else
  PY=python3
fi

# do not echo keys
if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "warn: DASHSCOPE_API_KEY not set; agent ASR/TTS and omni realtime will fail" >&2
fi
if [[ -z "${AGENTSCOPE_API_KEY:-}" ]]; then
  echo "warn: AGENTSCOPE_API_KEY not set; agent mode LLM will fail" >&2
fi
if [[ "$MODE" == "omni" && -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "warn: BRIDGE_MODE=omni requires DASHSCOPE_API_KEY" >&2
fi

nohup "$PY" "$DIR/server.py" --host "$HOST" --port "$PORT" --mode "$MODE" >>"$LOG" 2>&1 &
echo $! >"$PIDF"
sleep 0.4
if kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "started pid=$(cat "$PIDF") py=$PY listen=$HOST:$PORT mode=$MODE log=$LOG"
else
  echo "failed to start; see $LOG" >&2
  exit 1
fi
