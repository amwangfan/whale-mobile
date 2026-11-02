#!/system/bin/sh
# Session-only SIM cosmetics (gsm.* usually reset on reboot)
setprop gsm.sim.state READY
setprop gsm.sim.state.2 ABSENT
setprop gsm.operator.alpha "中国移动"
setprop gsm.operator.numeric 46000
setprop gsm.operator.iso-country cn
setprop gsm.operator.isroaming false
setprop gsm.sim.operator.alpha "中国移动"
setprop gsm.sim.operator.numeric 46000
setprop gsm.sim.operator.iso-country cn
setprop gsm.network.type LTE
setprop persist.sys.nova.fake_sim 1
setprop persist.sys.nova.fake_sim.alpha "中国移动"
setprop persist.sys.nova.fake_sim.numeric 46000
echo "SIM cosmetics applied"
getprop gsm.sim.state
getprop gsm.operator.alpha
getprop gsm.sim.operator.numeric
