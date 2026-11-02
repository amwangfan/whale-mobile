# call-bridge（VoIP 来电）× Android VoIP / Telecom 接口评估

日期：2026-10-27（Asia/Shanghai）  
设备：Root Nova Android 9 Phh-Treble / NetHunter（<TAILSCALE_IP>）  
APK：`com.nova.fakecall`（源码已至 v1.3-dial1 / versionCode 4，stub 未装机）  
默认拨号：`com.android.dialer`（AOSP Dialer 19.0，`InCallServiceImpl`）

## 结论（一行）

**部分能 → 拨「1」打 AI 且要原生电话界面：能，但须改成托管 CallProvider（不能继续只用 SelfManaged）；媒体仍走 TCP 18799；Android 9 用 NEW_OUTGOING_CALL 拦截「1」，不要指望 SipManager / CallRedirectionService。**

| 诉求 | 判定 |
|------|------|
| 用 VoIP/Telecom 接口 | ✅ 已在用（自管） |
| 原生拨号盘 InCall UI 显示 VoIP 来电/AI 通话 | ❌ 当前自管路径做不到（见机上证据） |
| 拨号盘拨「1」→ 原生界面 → Omni 音频 | ✅ 可行（改托管账户 + 拦截「1」） |
| 用系统接口替换 TCP 媒体 | ❌ Telecom 无媒体管道 |

---

## 机上铁证（为何现在没有原生界面）

`dumpsys telecom` Analytics：

| 通话 | ConnectionService | inCallServices |
|------|-------------------|----------------|
| 真拨出（Telephony） | `com.android.phone/…TelephonyConnectionService` | **`com.android.dialer/…InCallServiceImpl`** ← 原生 UI |
| Nova VoIP 来电（现行） | `com.nova.fakecall/.FakeConnectionService` | **空 `[]`** ← 系统拨号不接管 |

原因：`PhoneAccount.CAPABILITY_SELF_MANAGED` + `Connection.PROPERTY_SELF_MANAGED` 规定 **App 自带 UI**（`onShowIncomingCallUi`），默认拨号的 `InCallService` **不会**绑定。

`defaultOutgoing: none`；SIM `gsm.sim.state=READY`，但曾出现 `Phone is null, OUT_OF_SERVICE`（无可用蜂窝时真拨号会失败）。

---

## 「voip 电话接口」分层（易混）

| 层级 | API | 现状 / 对本需求 |
|------|-----|----------------|
| A. 信令 | `TelecomManager` + `ConnectionService` | ✅ 已有 |
| B. 自管 UI | `CAPABILITY_SELF_MANAGED` | ✅ VoIP 来电在用；**无原生 InCall** |
| C. 音频模式 | `setAudioModeIsVoip` / `MODE_IN_COMMUNICATION` | ⚠️ 半吊子；Phase 音频加强仍要做 |
| D. SipManager | `android.net.sip` | ❌ 机上无 SIP 框架 |
| E. **托管 + 系统 InCall** | `CAPABILITY_CALL_PROVIDER`、Connection **不**标 SelfManaged | ⭐ **拨「1」+ 原生 UI 必选** |
| F. 拨出拦截 | `ACTION_NEW_OUTGOING_CALL`（API≤28）/ `CallRedirectionService`（API≥29） | 本机 API 28 → 用 F 旧广播 |

**媒体永远由 App 采播；18799 TCP PCM 保留。**

---

## 拨号「1」→ AI（重点）

### 目标 UX

```text
用户打开系统拨号盘 → 输入 1 → 拨打
  → 原生 InCall UI（com.android.dialer）
  → 接通后麦克风/听筒 ↔ AudioBridge ↔ box:18799 Omni
```

### 拦截方案对比（Android 9 / API 28）

| 方案 | 能否进原生 InCall | 本机可行性 | 说明 |
|------|-------------------|------------|------|
| 继续 SelfManaged + `placeCall` | ❌ | 高但不满足 UI | 机上已证 inCallServices 为空 |
| **托管 CallProvider + `placeCall(tel:1)`** | ✅ | **高（推荐）** | 与真通话同一套 Dialer UI |
| 仅靠拨号盘选 Calling account | ✅ | 中 | 每次可能要选账户；可设 defaultOutgoing（会吃掉所有拨号，需慎） |
| `ACTION_NEW_OUTGOING_CALL` 改号/取消再 `placeCall` | ✅（配合托管账户） | **高** | API 28 仍可用；需 `PROCESS_OUTGOING_CALLS`；API 29+ 才是 CallRedirectionService |
| CallRedirectionService | ✅ | ❌ | 需 API 29，本机 Android 9 没有 |
| 替换/魔改系统 Dialer | ✅ | 低（过重） | root 可做，维护成本高 |
| Xposed/Dialer hook | ✅ | 中 | 脆；不如 Telecom 正规路径 |
| SipManager | — | ❌ | 无栈 |

**SelfManaged 的 `tel:` PhoneAccount 不会被系统拨号盘当成「拨出账户」来路由 `tel:1`；拨「1」会走 Telephony，无网则失败。**

### 推荐实现路径（本机最小三步）

1. **APK：增加托管账户（与现有自管账户并存）**  
   - 新 `PhoneAccount` id 如 `nova-ai-out`：`CAPABILITY_CALL_PROVIDER`（**不要** SelfManaged）  
   - 同一 `FakeConnectionService`：`onCreateOutgoingConnection` 若号码为 `1`（可再加别名）→ `setDialing()` → 立刻/短延迟 `setActive()` → `AudioBridge.start(host, 18799)`；其它号码 → `setDisconnected(ERROR)`  
   - Connection **不要** `PROPERTY_SELF_MANAGED`；可设 `setAudioModeIsVoip(true)` + 显示名「AI」  
   - 保留现有自管账户 + `ring.sh` VoIP 来电（两套不打架）

2. **启用账户 + 拦截「1」**  
   - 用户：设置 → 通话 → 呼叫账户 → 启用「Nova AI」；或 root 写 telecom 注册表启用  
   - 注册 `BroadcastReceiver`：`android.intent.action.NEW_OUTGOING_CALL` + 权限 `PROCESS_OUTGOING_CALLS`  
   - `number` 归一化为 `1` 时：`setResultData(null)` 取消蜂窝拨出，再 `TelecomManager.placeCall(Uri.fromParts("tel","1",null), extras)`，extras 带上托管 `PhoneAccountHandle`  
   - （备选）不做广播、把托管账户设为 `defaultOutgoing`：则所有拨号进我们 CS——非 `1` 必须明确失败或转发，**易误伤紧急/真实号码，不推荐作默认**

3. **音频仍接现桥**  
   - 接通 = `AudioBridge` → `<TAILSCALE_IP>:18799`（协议不变）  
   - 同步做 `MODE_IN_COMMUNICATION` + AudioFocus（听筒/焦点）  
   - box 侧 `BRIDGE_MODE=omni` 不用改

### 与现有 VoIP 来电 APK / 18799 的关系

```text
[保留] ring.sh / 自管来电 / 自画 IncomingCallActivity
       → SelfManaged PhoneAccount → AudioBridge → :18799

[新增] 拨号盘输入 1
       → NEW_OUTGOING_CALL 拦下
       → placeCall → CallProvider PhoneAccount
       → 原生 com.android.dialer InCall UI
       → 同一 AudioBridge → :18799 Omni
```

VoIP 来电演示与「拨 1 打 AI」可并存；**不要删 TCP 桥**。

### 风险（拨 1 专用）

1. **CallProvider 账户默认可能未启用** — 须设置里打开或 root 启用，否则 `placeCall` 失败。  
2. **`NEW_OUTGOING_CALL` 在部分 ROM 被收紧** — Phh AOSP 一般可用；若被禁则退化为「拨号后选手动选 Nova AI 账户」或 root 设账户。  
3. **紧急号码** — 拦截逻辑必须 **永不** 取消 `112`/`911`/`110` 等；仅精确匹配 `1`。  
4. **无 SIM / OUT_OF_SERVICE** — 正是要避开 Telephony；拦截失败时用户仍会看到「无法连接移动网络」。  
5. **自管与托管两账户** — 勿把拨出 Connection 标成 SelfManaged，否则又变回无原生 UI。

### 可选 stub（尚未装机；实现时照抄）

```java
// 概念 stub — 托管账户注册
PhoneAccount out = new PhoneAccount.Builder(outHandle, "Nova AI")
    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
    .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL))
    .setShortDescription("拨 1 打 AI")
    .build();
tm.registerPhoneAccount(out);

// NEW_OUTGOING_CALL：仅当 number 为 "1"
// setResultData(null);
// tm.placeCall(Uri.fromParts("tel", "1", null), extrasWith(outHandle));

// onCreateOutgoingConnection：
// if (!"1".equals(num)) { conn.setDisconnected(...); return conn; }
// conn.setDialing(); conn.setActive(); AudioBridge.start(...);
```

Manifest 增量（实现时）：`PROCESS_OUTGOING_CALLS`；receiver `NEW_OUTGOING_CALL`（`priority` 可偏高）；CallProvider 账户无需 `BIND_TELECOM` 之外的新 permission（已有 `CALL_PHONE`）。

---

## 能力矩阵（更新）

| 能力 | 当前 SelfManaged | +托管 CallProvider 拨 1 | SipManager |
|------|------------------|-------------------------|------------|
| 系统 InCall 原生 UI | ❌ | ✅ | — |
| 拨号盘拨 1 → AI | ❌（走蜂窝） | ✅ | ❌ |
| VoIP 来电自画 UI | ✅ | ✅（可保留） | — |
| Omni TCP 18799 | ✅ | ✅ 复用 | 需另协议 |
| Android 9 root 可行 | ✅ | ✅ | ❌ |

---

## 分阶段计划

### Phase 0 — 认知对齐 ✅

自管 ≠ 原生拨号 UI；要原生界面必须托管 CallProvider。

### Phase 1 — 拨「1」→ 原生 UI → 18799（**用户优先，推荐先做**）

- [ ] 注册托管 `PhoneAccount`（`nova-ai-out`）  
- [ ] 完善 `onCreateOutgoingConnection`（仅 `1` 接通 + AudioBridge）  
- [ ] `NEW_OUTGOING_CALL` 拦截「1」→ `placeCall`  
- [ ] root/设置启用账户；冒烟：拨 1 → 见 AOSP InCall → 说话听 Tina  
- [ ] 顺带：`AudioBridge` 加 `MODE_IN_COMMUNICATION` + focus  

**不**重写整包；VoIP 来电路径可暂不动。

### Phase 2 — 体验（可选）

- VoIP 来电也改为托管，则来电也走原生 InCall（可砍自画 Activity）  
- mute/hold/蓝牙：`onCallAudioStateChanged`  
- 近距熄屏  

### Phase 3 — 明确不做

- SipManager / 自建 SIP  
- CallRedirectionService（等升到 API 29+）  
- 替换系统 Dialer / WebRTC 换桥（无新需求前）

---

## 推荐下一构建步骤

**立刻改 APK：可以开做 v1.3「拨 1」最小切片**（托管账户 + outgoing Connection + NEW_OUTGOING_CALL + 复用 AudioBridge）；先不要大拆自管来电。  
验证通过后再考虑 VoIP 来电也迁托管 UI。

冒烟清单：

1. box：`BRIDGE_MODE=omni` 监听 18799  
2. 启用 Nova AI 呼叫账户  
3. 拨号盘拨 `1` → 原生通话界面 → 双向 Omni  
4. 拨其它号码仍走蜂窝（或不被我们误接）  
5. `112` 等紧急号不被拦截  

---

## 数据流对照

```text
[现有 VoIP 来电]
  ring.sh → SelfManaged → 自画 UI → AudioBridge → :18799

[目标：拨 1]
  Dialer "1" → NEW_OUTGOING_CALL 取消蜂窝
            → placeCall(tel:1, CallProvider handle)
            → FakeConnectionService.onCreateOutgoingConnection
            → 原生 Dialer InCall UI
            → setActive → AudioBridge → :18799 → Omni
```

## 给用户的一句话

可以：在 Android 9 上把「拨 1」拦下来，用**托管** Telecom 账户打进**系统自带电话界面**，音频仍走现在的 Omni 桥；现在的自管 VoIP 来电故意不用系统界面，所以要加一条拨出路径，而不是再找一层 SIP。


---

## 拨号 1 → AI（机上复访结论 · 与上文对齐）

机上 SSH 复核（`root@<TAILSCALE_IP>`，密钥 `~/.ssh/<ssh_key>`，Asia/Shanghai 2026-10-27）：

| 项 | 结果 |
|----|------|
| 默认拨号 | `com.android.dialer` / `DialtactsActivity`；DefaultDialerCache=`com.android.dialer` |
| CALL tel:1 | → Telecom `UserCallActivity` → **Telephony** CallProvider |
| 蜂窝 | `OUT_OF_SERVICE`（真拨号会「无法连接到移动网络」） |
| Nova 账户 | SelfManaged + `tel` 已启用 `[X]`；**analytics 中 nova 通话 `inCallServices: []`** |
| Dialer InCall | `com.android.dialer/.InCallServiceImpl` 仅绑 **非** SelfManaged 通话 |
| `NEW_OUTGOING_CALL` | 现仅 GMS + Dialer Undemote；`PROCESS_OUTGOING_CALLS` 仍为 dangerous，API 28 可授予 |
| AOSP 9 账户规则 | SelfManaged **自动启用**；CallProvider **新注册默认 disabled**，需设置「呼叫账户」或 root 启用；`enablePhoneAccount` 为 @SystemApi |

### 路径排序（要「原生通话界面」时）

| 排名 | 路径 | 用户看到的 UI | 成本 | 判定 |
|------|------|---------------|------|------|
| **1 ★** | `NEW_OUTGOING_CALL` 拦 `1` → `placeCall` 到 **`CAPABILITY_CALL_PROVIDER`（nova-ai-out）** → Connection **不**标 SelfManaged → Dialer `InCallServiceImpl` | **系统拨号盘 + 系统 InCall** | 1 天级（含启用账户冒烟） | **推荐**；与上文 Phase 1 一致 |
| 2 | 同上但账户仍 SelfManaged + 自画 `InCallActivity` | 系统拨号盘 + **我们的**通话页 | &lt;0.5 天 | 快速兜底；**不是**原生 InCall |
| 3 | 抢默认拨号 / 自带 InCallService | 我们的拨号+通话 | 2+ 天 | 偏离「系统拨号盘」 |
| 4 | Xposed hook Dialer/Telecom | 可伪装 | 高维护 | 不推荐主路径 |

**TCP 18799：所有路径都要保留。**

### 推荐步骤（路径 1）

1. 注册双账户：保留自管（VoIP 来电）+ 新增 `nova-ai-out` CallProvider。  
2. Manifest：`PROCESS_OUTGOING_CALLS` + `OutgoingCallReceiver`（已写）。  
3. 拦 `1`：`setResultData(null)` → `placeOutgoingAi()`（优先 CallProvider handle；未启用则回落自管）。  
4. `onCreateOutgoingConnection`：托管路径不设 `PROPERTY_SELF_MANAGED`、不拉 `InCallActivity`，`setActive` + `AudioBridge`；自管回落则拉我们的 UI。  
5. 启用 `Nova AI` 呼叫账户：设置 → 通话 → 呼叫账户，或 `am start -a android.telecom.action.CHANGE_PHONE_ACCOUNTS`；root 可改 registrar 状态 / 调 hidden `enablePhoneAccount`。  
6. 冒烟：拨 `1` → **AOSP InCall** → Omni；其它号不误拦；`112` 等永不取消。

### Stub 状态（本轮已写，已本地 build，**未装机**）

- `OutgoingCallReceiver.java` — 拦 `1`  
- `CallAccountHelper` — 双账户 + `placeOutgoingAi`（managed 优先）  
- `FakeConnectionService.onCreateOutgoingConnection` — managed / 自管分支  
- Manifest `v1.3-dial1` versionCode 4；APK：`build/nova-fakecall.apk`（本机构建，不入库）  

装机后须：`pm grant … PROCESS_OUTGOING_CALLS`，打开 Bootstrap 注册账户，**启用 Nova AI 呼叫账户**，再测拨 1。

### UI 诚实说明

- **要「和系统电话一样的通话中界面」→ 必须路径 1（托管 CallProvider）**；自管永远不会绑定 `InCallServiceImpl`（机上已证）。  
- 路径 2 只保证「从系统拨号盘按下 1」；通话页是 App 仿造。  
- 媒体始终 `AudioBridge` → `<TAILSCALE_IP>:18799`。

