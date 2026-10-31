#!/bin/bash
# 安全重启 dev core 实例（避免 pkill 匹配自身命令行）
PAT="whale_core.cli.main"
for p in $(pgrep -f "$PAT"); do
  [ "$p" != "$$" ] && kill "$p" 2>/dev/null
done
sleep 1
systemctl restart whale-core-dev8190
sleep 4
systemctl is-active whale-core-dev8190
