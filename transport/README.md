# transport/ — 传输层

> 协议事实源：`contracts/v2/`（信封/消息 schema/能力枚举）。
> 本目录文档描述 whale-mobile 端实现。
>
> **v1 协议栈已整体下线（feat/contracts-v2）**：v1 的 WssClient/Envelope/
> OfflineQueue/QueueCipher/Backoff 与 runtime/NodeRuntime/NodeService/
> BootReceiver 已删除；被 v2 复用的实现（加密离线队列、重连退避、序号器、
> 队列加密接口）已迁入 `transport/v2/` 包。

## 1. 组成与代码位置

```
app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/
├── DeviceIdentity.kt      设备身份（Ed25519 + Keystore AES/GCM 保护；两版共用）
├── BridgeDataQueries.kt   本地数据查询面实现（notificationInbox.*/capture.*/
│                          favorites.*/backup.*/getLaunchRoute，v1/v2 桥共享）
├── TransportBridge.kt     `whale/transport` 桥（瘦身版：数据查询面 + 模块配置面）
└── v2/                    contracts/v2 协议栈（唯一控制面）
    ├── ProtocolV2.kt      v2 信封编解码/ID 生成（IdsV2/ULID）等纯 JVM 件
    ├── WssClientV2.kt     WSS 客户端：握手/心跳/重连/action 分发
    ├── ReceiptDispatcherV2.kt + ActionDedupStoreV2（action 幂等/超时/终态机）
    ├── EventOutboxV2.kt   experience.event 上行队列（§9/§16）
    ├── OfflineQueue.kt    上行队列底座（自 v1 迁入；payload AES/GCM 加密）
    ├── QueueCipher.kt     队列加解密接口 + Keystore 实现（自 v1 迁入）
    ├── Backoff.kt         重连退避（自 v1 迁入）
    ├── SeqCounter.kt      每连接单调序号（自 v1 迁入）
    ├── LegacyActionExecutor.kt  既有模块执行器契约（自 v1 WssClient.ActionExecutor 抽出）
    └── TransportBridgeV2.kt     `whale/transport_v2` 桥（控制面 + 同一数据查询面）

app/lib/transport/transport_client.dart     `whale/transport` Dart 封装（模块配置面）
app/lib/transport/transport_client_v2.dart  `whale/transport_v2` Dart 封装（控制面）
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

- `device_id = SHA-256(公钥)[:16].hex()`（32 小写 hex 字符）；
- 配对：v2 设备上屏配对（`getPairingInfoV2`），公钥报 core 可信管理端登记；
- 主密钥失效（恢复出厂等）→ 重新生成身份，device_id 变化，需重新配对（日志告警）。

## 3. 运行时

- `runtime/v2/NodeRuntimeV2`：Application 级单例（`WhaleApp.onCreate` 创建并 start），
  WSS 长连/上行队列/模块执行器/采集管线的唯一所有者；
  `runtime/v2/NodeServiceV2` 为前台载体；`runtime/v2/BootReceiverV2` 为开机恢复入口。
- `runtime/` 下保留的公共件（v2 复用，与协议版本无关）：
  `RecoveryPolicy`（恢复决策）、`SensorQueryDispatch`（sensor.query 分发链）、
  `UiEventHub`（UI 事件扇出）、`PauseFlags`（whale_modules/all_paused 一键暂停读取点）。

详细协议语义见 `contracts/v2/` 与各文件头注释。
