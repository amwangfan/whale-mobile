#!/usr/bin/env bash
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
PIDF="$DIR/ring_hub.pid"
if [[ -f "$PIDF" ]]; then
  pid=$(cat "$PIDF")
  kill "$pid" 2>/dev/null || true
  rm -f "$PIDF"
  echo "stopped $pid"
else
  echo "not running"
fi
pkill -f 'ring_hub.py' 2>/dev/null || true
