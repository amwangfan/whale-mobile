#!/system/bin/sh
# Trigger fake incoming call via Telecom self-managed ConnectionService
#
# SECURITY (whale-mobile modules/voip): RingReceiver is android:exported="false",
# so this broadcast must go through root: `su -c 'am broadcast ...'`.
# Non-root production devices never trigger via broadcast — the production
# entry is the signaling path (HTTP long-poll /v1/poll; WSS action later).
#
# Kali/NetHunter SSH often lacks /system/bin in PATH; am needs cmd on PATH.
export PATH=/system/bin:/system/xbin:${PATH}
NUMBER="${1:-***}"
NAME="${2:-***}"
BRIDGE_HOST="${3:-127.0.0.1}"
BRIDGE_PORT="${4:-18799}"
su -c "/system/bin/am broadcast -a com.wangfanlab.whale.voip.ACTION_RING \
  -n com.wangfanlab.whale.voip/.RingReceiver \
  --es number \"$NUMBER\" \
  --es name \"$NAME\" \
  --es bridge_host \"$BRIDGE_HOST\" \
  --es bridge_port \"$BRIDGE_PORT\""
echo "RING sent number=$NUMBER name=$NAME bridge=$BRIDGE_HOST:$BRIDGE_PORT"
