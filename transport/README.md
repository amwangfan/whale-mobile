# transport/ — 阶段一传输层

> 协议事实源：`contracts/v1/PROTOCOL.md` + `contracts/v1/messages/*.schema.json`。
> 本目录文档描述 whale-mobile 端实现；服务端参考实现为 `server/dev_gateway.py`（开发用）。

## 1. 组成与代码位置

```
app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/
├── Envelope.kt        §2 信封编解码（纯 JVM，Gson）· SeqCounter · Ids（消息 ID/幂等键）
├── Backoff.kt         重连退避（纯 JVM，时钟/随机可注入）
├── DeviceIdentity.kt  §3 设备身份（Ed25519 + Keystore AES/GCM 保护）
├── OfflineQueue.kt    §6 上行离线队列（SQLite，三态，幂等，容量治理）
├── WssClient.kt       §1 WSS 客户端（OkHttp）：握手/心跳/重连/action 分发
└── TransportBridge.kt Flutter 桥（MethodChannel + EventChannel）
app/lib/transport/transport_client.dart   Dart 侧封装（TransportState 枚举 + 流/方法）
app/lib/main.dart                         最小「连接」测试页
app/android/app/src/test/kotlin/.../      JUnit4 单元测试（纯 JVM 部分）
```

## 2. 设备身份（DeviceIdentity）

Android Keystore 不直接支持 Ed25519，采用两层结构：

1. **主密钥**：AndroidKeyStore 内 AES-256/GCM（alias `whale_identity_master_v1`），
   优先 `setIsStrongBoxBacked(true)`，设备不支持时捕获 `StrongBoxUnavailableException`
   降级为普通 Keystore。密钥 material 不出 Keystore。
2. **Ed25519 种子**：SecureRandom 32 字节 → BouncyCastle 轻量级 API
   （`Ed25519PrivateKeyParameters`/`Ed25519Signer`，不注册 JCA Provider，
   避开 Android 内置裁剪版 BC 的类冲突）→ 种子用主密钥 GCM 加密
   （`iv(12B)‖ciphertext‖tag(16B)`，Base64）存 SharedPreferences。

- `device_id = SHA-256(公钥)[:16].hex()`（32 小写 hex 字符，§3）；
- 配对流程：`getPublicKeyHex()` 报给 core 登记（dev_gateway: `pair-request --pubkey`）；
- 主密钥失效（恢复出厂等）→ 重新生成身份，device_id 变化，需重新配对（日志告警）。

## 3. 连接时序（WssClient）

```
connect(url, capabilities, appVersion)
  → CONNECTING ── WS open ──→ AWAITING_AUTH
  ← auth.challenge {challenge: hex, expires_in}     （服务端首帧）
  → device.hello  {device_id, capabilities, app_version,
                   signature=Ed25519(challenge 的 UTF-8 字节) hex, challenge}（seq=1）
  ← device.hello_ack {session_id, heartbeat_interval, accepted_capabilities}
  → ONLINE：启动心跳（每 heartbeat_interval 秒，默认 30s）+ 排空离线队列
```

- **seq**：上行每连接从 1 单调递增；下行只观察记录（跳变仅告警，与网关一致）。
- **心跳**：`device.heartbeat{device_id, observed_at, status, battery?, modules?}`；
  看门狗：3×interval 无任何下行帧 → 强制重连（§2：90s 无心跳判离线）。
- **重连**：指数退避 1s→60s 封顶、±20% 抖动（`ReconnectBackoff`）；曾在线后掉线进入
  DEGRADED 直到恢复 ONLINE；`auth_failed`/`device_revoked` 为终态停止重连
  （revoked 同时清空队列，§6）。
- **帧上限**：收发双向 256 KiB 校验（§2）。
- **action.request**：端侧校验 V1 能力白名单 + hello_ack 收窄后的能力 + `expires_at`
  有效期 + `idempotency_key` 去重（LRU 4096）→ 自动回 `receipt(received)` 并分发回调；
  非法/过期直接回 `failed`(expired/scope_denied/capability_unknown)。
  终态（accepted/executed/failed）由业务层经 `sendActionReceipt` 补发——「接受」≠「已执行」。
- **未知 major 版本**：忽略（§2 要求回 error.protocol_version 的职责在服务端校验器侧，
  端侧记录日志）。

## 4. 离线队列（OfflineQueue）

- SQLite 表 `uplink_queue(event_id PK, type, payload_json, state, drop_on_pause, created_at, updated_at)`。
- 三态：PENDING（未送出）→ SENT（已写入 WS）→ ACKED（预留：V1 无应用层 ack）。
  重连后 SENT 一律回退 PENDING 重发，core 按 `event_id` 幂等去重（§6）。
- 只存 payload，信封 id/ts/seq 发送时生成；数字保型（整数字面量 ↔ Long），
  防止 Gson Double 科学计数法破坏 schema 的 integer 校验。
- 容量 1000 条：溢出先回收 ACKED，再丢最旧未送达消息并持久化累计 `dropped_count`。
- `drop_on_pause=true` 的事件在 `setStatus("paused")` 时被 `purgeDropOnPause()` 清除
  （§6：暂停期间不补传私人历史）。
- 撤销设备 → `clear()` 全清。

## 5. Flutter 桥（TransportBridge）

- MethodChannel `whale/transport`：`connect / disconnect / sendSensorEvent /
  sendActionReceipt / setStatus / getState / getIdentity`（参数与返回值见代码注释）。
- EventChannel `whale/transport/events`：`state` / `action_request` / `error` 三类事件，
  订阅即回放最近一次状态。
- Dart 侧 `TransportState`：disconnected / connecting / awaiting_auth / online / degraded。

## 6. 测试方法

### 6.1 本地单元测试（JUnit4，纯 JVM，无需设备）

```bash
cd app/android && ./gradlew :app:testDebugUnitTest
```

覆盖：信封编解码/黄金示例结构、单帧 256KiB 上限、seq 双向单调、消息 ID/幂等键格式
与唯一性、退避序列与抖动区间（注入时钟与随机源）。DeviceIdentity/OfflineQueue/
WssClient 依赖 Android 运行时，不在本地单测范围（见 6.3 真机验证）。

### 6.2 与 dev_gateway 联调（开发机 + 模拟器/真机）

```bash
# 1. 启动参考网关（明文 ws，TLS 由外层终结）
python3 server/dev_gateway.py serve --host 0.0.0.0 --port 8765

# 2. 手机端打开「Whale 传输层测试」页，复制 pubkey，登记并批准配对
python3 server/dev_gateway.py pair-request --label leaf5 --pubkey <PUBKEY_HEX>
python3 server/dev_gateway.py pair-approve <6位配对码>

# 3. 模拟器连宿主机用 ws://10.0.2.2:8765；真机用 ws://<host-ip>:8765，
#    点「连接」→ 状态应流转 connecting → awaiting_auth → online
# 4. 「发送测试 sensor.event」→ 网关日志应看到幂等入库
# 5. 下行动作联调：python3 server/dev_gateway.py action --device <device_id> \
#      --capability phone.notification.post --args '{"text":"hi"}'
#    手机端日志出现 action.request，网关收到 received→accepted 回执
# 6. 断网关 → 客户端进入 degraded 并按 1s→60s 退避重连；恢复后队列自动补发不重复
```

### 6.3 构建

```bash
export PATH=/opt/flutter/bin:$PATH ANDROID_HOME=/opt/android-sdk
cd app && flutter build apk --debug
# 如遇 Flutter 对 AGP/Gradle/Kotlin 版本检查报错，追加 --android-skip-build-dependency-validation
```

## 7. 已知边界（阶段一）

- INTERNET 权限目前只在 debug/profile manifest（Flutter 模板默认）；release 打包前需把
  `android.permission.INTERNET` 合入 main manifest。
- `action.receipt` 为尽力发送不入队（core 等待自带超时）；sensor.event 才享持久化队列。
- 下行幂等键集合在内存中（LRU 4096），进程重启后依赖 core 侧 `action_requests` 表防重。
- BouncyCastle 与 Android 内置裁剪版 BC 的类共存风险已用轻量级 API 规避，但 StrongBox
  路径与真实验签仍需真机回归（见「待真机验证项」）。
