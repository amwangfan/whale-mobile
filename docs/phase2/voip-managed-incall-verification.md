# VoIP 路A（managed 来电走原厂 InCallUI）+ 拨号重定向 — 真机验证预案

> 状态：**预案已就绪，未执行**。真机验证窗（用户解锁+常亮）**等 Tob 令**才执行。
> 实现：`app/android/app/src/main/java/com/wangfanlab/whale/voip/`（CallAccountHelper /
> FakeConnectionService / WhaleCallRedirectionService）+ `modules/voip/CallRoutingLogic.kt`（纯逻辑）。
> 参考：`E:\WhaleIO\tmp\design-refs\voip-handoff\HANDOFF.md`（路A）、RESOURCE-MAP.md。

## 0. 设备与纪律

| 项 | 值 |
| --- | --- |
| 目标机 | Honor BKQ-AN10（Magic8 Pro）/ MagicOS 11，adb 序列号/网络 adb 地址 `<见内部账本>` |
| 多机纪律 | 多机在场 **每条 adb 必 `-s` 指定并核型号**；nova 地址 `<见内部账本>` 仅对照用 |
| 禁止 | 不 reboot 修媒体崩溃；不 root 注入；本预案之外不做设备写操作 |
| 签名 | 装机包须用 `E:\WhaleIO\signing\` 现有同签（保数据，否则卸载重装丢数据） |
| 触发条件 | Tob 下令 + 用户在场解锁常亮 |

## 1. 前置准备（装机后、验证前）

```powershell
# 核机型（必须 BKQ-AN10）
adb -s <Magic8序列号见内部账本> shell getprop ro.product.model

# 启用 managed 承接账户 whale-in-managed（注册即 DISABLED，需显式启用）
adb -s <Magic8序列号见内部账本> shell cmd telecom set-phone-account-enabled com.wangfanlab.whale/com.wangfanlab.whale.voip.FakeConnectionService whale-in-managed 0
# 读回确认 enabled=true
adb -s <Magic8序列号见内部账本> shell dumpsys telecom | findstr /i "whale-in-managed"
```

whale 模块开关：`whale_modules/voip_enabled=true`（模块卡或下发）；运行时权限
RECORD_AUDIO 已授予。

## 2. 三案验证步骤

### 案一：incoming managed → 原厂动画+铃声
1. 触发来电（WSS 下行 call.invite，或 action.request `phone.call.ring`）。
2. **观察点**：
   - 屏幕出现**荣耀原厂来电界面**（GLSL 波纹动画，非 whale 自绘深蓝界面）；
   - 响铃为**系统默认铃声**（Go_Beyond 系列），非 whale 内置 nova_ring；
   - whale 侧 **不再出现** `nova_incoming_call` 全屏通知（managed 路径不发 FSI）。
3. 接听 → 应进系统通话界面，`AudioBridge` 启动（logcat `WhaleVoip: outgoing/incoming ACTIVE bridge=…`）；
   挂断 → `call.ended` 事件上行。
4. 日志取证：`adb -s <Magic8序列号见内部账本> shell "run-as com.wangfanlab.whale cat files/nova.log"`
   应含 `addNewIncomingCall … handle=whale-in-managed managedUi=true` 与
   `incoming STATE_RINGING … managedUi=true`（FileLog 落盘 files/nova.log，专为荣耀
   logcat 加密场景）。
5. **回落核对**：`set-phone-account-enabled … whale-in-managed 1`（停用）后再触发来电
   → 应回落 whale 自绘界面+内置铃（`managedUi=false`）。验证完恢复启用（0）。

### 案二：CALL tel:10086 → dumpsys connectionService=whale
1. **披露留痕操作（测试期专用 override）**：
   ```powershell
   adb -s <Magic8序列号见内部账本> shell cmd telecom set-test-call-redirection-app com.wangfanlab.whale
   ```
   > 该命令把 whale 设为测试期呼叫重定向应用，属**披露留痕操作**：只在验证窗内开启，
   > 验证完毕立即复位（见 §3）。正式形态 `RoleManager.ROLE_CALL_REDIRECTION` 授权
   > **本阶段不做**，需 Tob/用户批准后另行实施。
2. 发起拨号：`adb -s <Magic8序列号见内部账本> shell am start -a android.intent.action.CALL -d tel:10086`
   （用户在场确认拨号盘行为；如弹呼叫确认属正常 confirmFirst 场景外的系统行为）。
3. **观察点**：`adb -s <Magic8序列号见内部账本> shell dumpsys telecom` 中该呼叫的
   `connectionService` 应为 **com.wangfanlab.whale**（而非 com.android.phone）；
   界面为原厂通话 UI；whale 日志含 `redirectCall → whale-in-managed`。
4. 反向核对：whale 自身「拨 1 打 AI」路径（placeOutgoingAi）**不应**被重定向拦截
   （initiatedByWhale → placeCallUnmodified）。

### 案三：ENDCALL 结束
1. `adb -s <Magic8序列号见内部账本> shell input keyevent KEYCODE_ENDCALL`
   （或系统 UI 挂断）。
2. **观察点**：呼叫结束，`dumpsys telecom` 无残留 whale 连接；whale 日志含
   `Connection.onDisconnect` / `hangupAll done`；上行 `call.ended`（reason=local）。
3. 再核 `adb -s <Magic8序列号见内部账本> shell dumpsys telecom | findstr /i whale` 应无活跃呼叫行。

## 3. 验证后复位（必做）

```powershell
# 撤销测试期重定向 override（留痕关闭）
adb -s <Magic8序列号见内部账本> shell cmd telecom set-test-call-redirection-app ""
# 复位确认：dumpsys telecom 的 DefaultCallRedirectionApp 应不再指向 whale
adb -s <Magic8序列号见内部账本> shell dumpsys telecom | findstr /i "redirection"
```

## 4. 失败处置

- 原厂 InCallUI 不接管（仍 whale 自绘）：检查 `whale-in-managed` enabled 状态与
  `managedUi=true` 是否落日志；仍异常则保留证据（logcat + whale_voip.log + dumpsys），
  **不 reboot**，报 Tob 裁决是否启用路线 B（资源 graft，staging 在
  `E:\WhaleIO\tmp\incallui-staging\`，敏感件不入仓）。
- 重定向后呼叫失败/无声：先 `set-test-call-redirection-app ""` 复位保障正常拨号，再排查。
- 媒体崩溃类：**不 reboot 修复**，先确认（HANDOFF 三节纪律）。
