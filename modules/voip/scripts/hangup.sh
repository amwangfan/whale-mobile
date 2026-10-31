#!/system/bin/sh
# RingReceiver is android:exported="false" — root-only trigger via `su -c`
# (production entry is signaling, not broadcast; see ring.sh header).
export PATH=/system/bin:/system/xbin:${PATH}
su -c "/system/bin/am broadcast -a com.wangfanlab.whale.voip.ACTION_HANGUP \
  -n com.wangfanlab.whale.voip/.RingReceiver"
echo "HANGUP sent"
