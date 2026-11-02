# 非 Root 手机 × VoIP/Telecom → AI 音频桥评估

日期：2026-10-27（Asia/Shanghai）  
对照机：Root Nova（Android 9 Phh，`<TAILSCALE_IP>`）— **本评估不触碰**  
目标机：笔记本 ADB 所见非 Root 随身机（见下）  
现有栈：`com.nova.fakecall` v1.4 + box TCP PCM `<TAILSCALE_IP>:18799` Omni  
关联：`infra/fg-monitor/` user flavor（非 Root 监测先例）

## 一行结论

**部分能。** 非 Root 下：自管来电/自管拨出 + App UI + AudioBridge→18799 **能打能接**；系统拨号盘拨「1」进原生 InCall、免选卡、无 root 写 defaultOutgoing — **现阶段难/仅半自动**。最小落地：新/分叉非 Root 包（自管双路径 + 桥），不依赖 SSH/ring.sh/杀 system_server。

---

## 1. 设备现状（只读探测）

| 项 | 结果 |
|----|------|
| USB | `VID_339B` Honor；MTP 名 **荣耀Magic8 Pro**；型号 **BKQ-AN10** |
| 序列号 | `<serial>` |
| 笔记本 | `<LAPTOP_HOST>`（machineId `<MACHINE_ID>`） |
| ADB | **unauthorized**（需手机点「允许 USB 调试」）→ 本轮 **未能** `getprop` / `dumpsys telecom` |
| Root | 预期无；未跑 `su`（unauthorized） |
| 系统（公开资料） | Magic8 Pro 出厂 MagicOS 10；2026 中已有 MagicOS 10.0.0 小版本（160 / 170），MagicOS 11 尝鲜中 → 估计 **Android 15 级 API（35 左右）**，以授权后 `ro.build.version.*` 为准 |
| 多卡 / 默认拨号 | **未测**（需授权后 `gsm.sim.*` + `dumpsys telecom`） |
| 装普通 APK | **能**（用户态 `adb install`，无需 root；授权后即可） |

> 授权后建议只读补采：`getprop ro.build.version.{release,sdk}`、`dumpsys telecom`（账户/defaultOutgoing/InCall）、`cmd role get …`、是否已装 Tailscale。

---

## 2. 可行性矩阵（非 Root）

| 路径 | 非 Root | UX | 说明 |
|------|---------|-----|------|
| **自管 Incoming** `CAPABILITY_SELF_MANAGED` + `addNewIncomingCall` + App UI + AudioRecord/Track→TCP | ✅ 高 | App 全屏来电 | WhatsApp 类正规路径；触发靠 App 按钮/通知/同机广播，**无** Nova 的 SSH `ring.sh` |
| **自管 Outgoing** `placeCall` + 自画 InCallActivity | ✅ 高 | App 通话页 | 一键「打给 AI」即可；不拦拨号盘 |
| **托管 CallProvider** + 系统 InCall | ⚠️ 中 | 原生通话 UI | 账户**默认 disabled**；须用户在「设置→通话→呼叫账户」手动启用；无 root 无法写 registrar / 反射 `setUserSelectedOutgoingPhoneAccount`（要 `MODIFY_PHONE_STATE`） |
| 拨号盘拨「1」→ AI | ⚠️ 低～中 | 理想 UX | Nova 用 `NEW_OUTGOING_CALL`（API≤28）。本机约 API 35：广播已收紧；应改 **CallRedirectionService（API 29+）** + 用户授呼叫重定向角色。MagicOS 是否放行 **未证实** |
| 免选卡 / defaultOutgoing | ❌ 无 root | — | Nova 靠 root 写 `phone-account-registrar-state.xml`；非 Root 只能：App 内 `placeCall` **带死** `EXTRA_PHONE_ACCOUNT_HANDLE`，或用户手选默认账户 |
| 杀 system_server / 改系统 Dialer | ❌ 不做 | — | 非 Root 不可；亦无必要 |
| SipManager | ❌ | — | 与 Nova 结论同：不依赖 |
| 媒体走 Telecom | ❌ | — | Telecom 无媒体管；**必须保留 18799 PCM** |

**接听：** 自管 `addNewIncomingCall` → **能**（App UI）。托管来电进系统 InCall → 需已启用 CallProvider + OEM 行为未测，不作首发依赖。  
**拨打：** App 内拨出 → **能**；拨号盘「1」→ **待 MagicOS 实机验证**，首发不赌。

---

## 3. 与 Nova 方案差在哪

| | Root Nova | 本机（Magic8 Pro 非 Root） |
|--|-----------|---------------------------|
| 触发来电 | SSH + `ring.sh` / am broadcast | App 内按钮 / 通知 / 日后 FCM；或笔记本经局域网 HTTP 调 App |
| 拨「1」 | `NEW_OUTGOING_CALL` + 托管账户 + root 启账户/defaultOutgoing | 无 root 启账户/写 default；拦截 API 需升到 CallRedirection |
| 通话 UI | 自管自画 **或** 系统 Dialer InCall（已通） | 首发自管自画；系统 InCall 仅当用户手动启用账户 |
| 装包/权限 | `pm install -g` + root grant | 普通安装 + 运行时授权（麦、电话、可能全屏通知） |
| 网到 box | 机上 Tailscale → `<TAILSCALE_IP>:18799` | 见下节；**未必**已装 Tailscale |
| 改 telecom XML / 杀 system_server | 可用（已弃用杀进程，改 reboot） | **不能也不该** |

---

## 4. 网络路径（手机 → 桥）

桥监听：box `0.0.0.0:18799`，Nova 默认连 `<TAILSCALE_IP>`。

| 方案 | 条件 | 推荐 |
|------|------|------|
| A. 手机装 Tailscale，直连 `<TAILSCALE_IP>:18799` | 用户同意装客户端 | ★ 长期随身 |
| B. USB `adb reverse tcp:18799 tcp:18799`，桥 host=`127.0.0.1` | ADB 已授权且线连笔记本；笔记本须能到 box（本机已有 Tailscale） | ★ 开发期最小 |
| C. 笔记本本地 TCP 代理/转发 | 同 B，额外脚本 | 备选 |
| D. 公网/FRP 暴露 18799 | 安全与运维成本高 | 暂不做 |

`AudioBridge.DEFAULT_HOST` 非 Root 包应可配置（设置页 / Intent extra），勿写死仅 Tailscale IP。

---

## 5. 推荐判定与最小落地

**判定：部分能**（打：App 内能；接：自管能；拨号盘原生「1」：现阶段难）

**最小路径（推荐）：新包或 product flavor，勿污染 Root Nova 专用脚本假设**

1. **包**：`com.nova.fakecall.user`（或现包加 `user` flavor），复用 `FakeConnectionService` / `AudioBridge` / 来电·通话 Activity。  
2. **接**：自管账户 + Bootstrap「模拟来电」→ `addNewIncomingCall` → 接听 → TCP 桥。  
3. **打**：Bootstrap「打给 AI」→ `placeCall` + 钉死 handle → 自管 InCallActivity（若用户已启用 CallProvider 则优先系统 UI）。  
4. **桥**：设置里填 host/port；开发默认 `127.0.0.1` + 文档写清 `adb reverse`；随身改 Tailscale IP。  
5. **权限**：`MANAGE_OWN_CALLS`、`RECORD_AUDIO`、`CALL_PHONE`；targetSdk 按 MagicOS 实测抬到 34/35，去掉对 root/`PROCESS_OUTGOING_CALLS` 的强依赖（拨号拦截改 Phase 2）。  
6. **明确不做（首发）**：SSH ring、写 registrar、杀 system_server、默认劫持全部拨号。

**Phase 2（可选）**：`CallRedirectionService` + 用户授角色；引导开启「Nova AI」呼叫账户；再测拨号盘「1」+ 系统 InCall。

**冒烟（授权 ADB 后）**

1. 用户点允许 USB 调试 → 只读补全 SDK/SIM/telecom。  
2. `adb reverse tcp:18799 tcp:18799`；box `BRIDGE_MODE=omni`。  
3. 装 user APK → 授麦/电话 → 点「打给 AI」/「模拟来电」→ 双向 Tina。  
4. （可选）设置启用呼叫账户 → 再测系统 InCall。

---

## 6. 阻塞项（需用户）

1. 手机弹出 **允许 USB 调试**（并勾选始终允许）→ 才能 dumpsys / install。  
2. 确认是否装 Tailscale，或接受开发期 USB reverse。  
3. 是否接受「首发只有 App 内打/接，没有拨号盘拨 1」。

