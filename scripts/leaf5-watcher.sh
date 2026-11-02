#!/bin/bash
# leaf5 回线自动续测：等 ssh 通 → 开 adb → 装包 → 启动 → 查在线状态
set -u
ADB="/opt/android-sdk/platform-tools/adb -s 100.64.85.85:5555"
APK=/opt/whale/whale-mobile/app/build/app/outputs/flutter-apk/app-debug.apk
LOG=/tmp/leaf5-watcher.log
echo "$(date -Is) watcher start" > "$LOG"
for i in $(seq 1 2880); do
  if timeout 10 ssh -o BatchMode=yes -o ConnectTimeout=6 leaf5 'echo ok' >/dev/null 2>&1; then
    echo "$(date -Is) leaf5 back" >> "$LOG"
    timeout 20 ssh -o BatchMode=yes leaf5 '/system/bin/setprop service.adb.tcp.port 5555; /system/bin/stop adbd; sleep 1; /system/bin/start adbd' >> "$LOG" 2>&1
    sleep 4
    /opt/android-sdk/platform-tools/adb connect 100.64.85.85:5555 >> "$LOG" 2>&1
    sleep 3
    $ADB install -r "$APK" >> "$LOG" 2>&1
    $ADB shell am force-stop com.wangfanlab.whale >> "$LOG" 2>&1
    $ADB shell monkey -p com.wangfanlab.whale -c android.intent.category.LAUNCHER 1 >> "$LOG" 2>&1
    sleep 12
    TOKEN=$(cat /tmp/whale-dev/api_token)
    curl -s http://127.0.0.1:8190/api/v1/devices -H "Authorization: Bearer $TOKEN" >> "$LOG" 2>&1
    echo "" >> "$LOG"
    echo "$(date -Is) watcher done" >> "$LOG"
    exit 0
  fi
  sleep 30
done
echo "$(date -Is) gave up after 2h" >> "$LOG"
