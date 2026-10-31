#!/usr/bin/env bash
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
cd "$DIR"
mkdir -p "$DIR"
PIDF="$DIR/ring_hub.pid"
LOG="$DIR/ring_hub.log"
if [[ -f "$PIDF" ]] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "ring_hub already running pid=$(cat "$PIDF")"
  exit 0
fi
export RING_BRIDGE_HOST="${RING_BRIDGE_HOST:-127.0.0.1}"
export RING_BRIDGE_PORT="${RING_BRIDGE_PORT:-18799}"
export RING_HUB_PORT="${RING_HUB_PORT:-18800}"
nohup python3 "$DIR/ring_hub.py" >>"$LOG" 2>&1 &
echo $! >"$PIDF"
echo "started ring_hub pid=$(cat "$PIDF") log=$LOG"
