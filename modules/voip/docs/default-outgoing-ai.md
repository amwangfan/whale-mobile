# 拨 1 不弹账户选择器

## 根因
`dumpsys telecom` 曾显示 `defaultOutgoing: none`，同时存在两个启用的 CallProvider（Telephony「紧急呼救」+ Nova AI），均支持 `tel:`。AOSP Dialer 在无默认拨出账户时会弹出账户/SIM 选择器；该弹窗发生在 `NEW_OUTGOING_CALL` 之前，故仅 abort 再 `placeCall` 无法去掉选卡 UI。

## 修复
1. APK v1.4：`placeOutgoingAi` 始终带 `EXTRA_PHONE_ACCOUNT_HANDLE=nova-ai-out`；尝试反射 `setUserSelectedOutgoingPhoneAccount`（无 MODIFY_PHONE_STATE 会失败）。
2. Root：写 `phone-account-registrar-state.xml` 的 **version=9** 格式 `default_outgoing` → Nova AI，然后 **reboot**（不要 kill system_server）。

## 验证
`dumpsys telecom | grep defaultOutgoing` 应类似：
`defaultOutgoing: ComponentInfo{com.nova.fakecall/...}, nova-ai-out, ...`
拨号盘 → 1 → 拨打 → 无选卡 → 系统 InCall。
