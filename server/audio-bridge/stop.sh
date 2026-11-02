#!/usr/bin/env bash
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
PIDF="$DIR/bridge.pid"
if [[ ! -f "$PIDF" ]]; then
  echo "not running (no pid file)"
  # also kill stray
  pkill -f "audio-bridge/server.py" 2>/dev/null || true
  exit 0
fi
PID=$(cat "$PIDF")
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID" || true
  sleep 0.3
  kill -9 "$PID" 2>/dev/null || true
  echo "stopped $PID"
else
  echo "stale pid $PID"
fi
rm -f "$PIDF"
