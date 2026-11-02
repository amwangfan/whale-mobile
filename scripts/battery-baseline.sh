#!/bin/bash
# 24h 耗电基线采集（Gate A §6）：每 10 分钟记录 leaf5 的电池与目标 App 耗电。
# 用法：./scripts/battery-baseline.sh [时长小时数，默认24]（需 leaf5 adb 在线）
ADB="/opt/android-sdk/platform-tools/adb -s 100.92.91.85:5555"
HOURS="${1:-24}"
OUT=/opt/whale/whale-mobile/docs/measurements/battery-$(date +%Y%m%d-%H%M).log
mkdir -p "$(dirname "$OUT")"
echo "# battery baseline start $(date -Is), hours=$HOURS" >> "$OUT"
END=$((SECONDS + HOURS * 3600))
while [ $SECONDS -lt $END ]; do
  {
    echo "=== $(date -Is)"
    $ADB shell dumpsys battery 2>/dev/null | grep -E "level|status|voltage"
    $ADB shell dumpsys batterystats --charged 2>/dev/null | grep -E "Estimated capacity|Discharge" | head -3
    $ADB shell dumpsys batterystats 2>/dev/null | grep -A2 -E "com.wangfanlab.whale|com.fqyw.screen_memo" | head -8
  } >> "$OUT" 2>&1
  sleep 600
done
echo "# done $(date -Is)" >> "$OUT"
