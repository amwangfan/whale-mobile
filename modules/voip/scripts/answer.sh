#!/system/bin/sh
# Answer active VoIP call and open InCall UI (starts audio bridge)
# RingReceiver is android:exported="false" — root-only trigger via `su -c`
# (production entry is signaling, not broadcast; see ring.sh header).
export PATH=/system/bin:/system/xbin:${PATH}
su -c "/system/bin/am broadcast -a com.wangfanlab.whale.voip.ACTION_ANSWER \
  -n com.wangfanlab.whale.voip/.RingReceiver"
echo "ANSWER sent"
