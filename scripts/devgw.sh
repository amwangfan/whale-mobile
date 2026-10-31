#!/bin/bash
# dev-gateway 管理脚本（避免 pkill 自杀问题）
GW=/opt/whale/whale-mobile/server/dev_gateway.py
PIDFILE=/tmp/devgw.pid
case "${1:-}" in
  start)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null; then echo "already running $(cat $PIDFILE)"; exit 0; fi
    cd /opt/whale/whale-mobile/server
    nohup python3 "$GW" serve --host "${GW_HOST:-0.0.0.0}" --port "${GW_PORT:-8765}" > /tmp/devgw.log 2>&1 &
    echo $! > "$PIDFILE"
    sleep 2
    echo "started $(cat $PIDFILE)";;
  stop)
    [ -f "$PIDFILE" ] && kill "$(cat $PIDFILE)" 2>/dev/null && rm -f "$PIDFILE" && echo stopped;;
  status)
    [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null && echo "running $(cat $PIDFILE)" || echo stopped;;
  *) echo "usage: devgw {start|stop|status}";;
esac
