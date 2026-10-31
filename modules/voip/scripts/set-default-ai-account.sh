#!/system/bin/sh
# Set Nova AI as default outgoing PhoneAccount (XML v9 format).
# Prefer: run AFTER a normal reboot (or ask user to reboot once).
# Avoid kill system_server if possible — use reboot.
set -e
export PATH=/system/bin:/system/xbin:$PATH
XML=/data/user_de/0/com.android.server.telecom/files/phone-account-registrar-state.xml
[ -f "$XML" ] || { echo "missing $XML" >&2; exit 1; }
cp "$XML" "$XML.bak.$(date +%s)"

# Host-side: use python on Kali then nsenter-write. On-device Android may lack python.
# This script expects to be run from Kali chroot with nsenter:
#   nsenter -t 1 -m sh /data/local/tmp/nova-fake-call/set-default-ai-account.sh

BLOCK='<default_outgoing>
<default_outgoing_phone_account_handle>
<user_serial_number>0</user_serial_number>
<group_id></group_id>
<account_handle>
<phone_account_handle>
<component_name>com.wangfanlab.whale.voip/com.wangfanlab.whale.voip.FakeConnectionService</component_name>
<id>nova-ai-out</id>
<user_serial_number>0</user_serial_number>
</phone_account_handle>
</account_handle>
</default_outgoing_phone_account_handle>
</default_outgoing>'

python3 - <<PY
import re, pathlib, os
p = pathlib.Path("$XML")
text = p.read_text()
block = '''$BLOCK'''
text2, n = re.subn(r"<default_outgoing\s*/>|<default_outgoing>.*?</default_outgoing>", block, text, count=1, flags=re.S)
assert n == 1, n
p.write_text(text2)
print("patched default_outgoing → nova-ai-out (v9)")
PY
chmod 600 "$XML"
chown system:system "$XML" 2>/dev/null || true
grep -A20 '<default_outgoing>' "$XML" | head -25
echo "OK. Reboot device (reboot) so PhoneAccountRegistrar reloads. Do NOT kill system_server."
