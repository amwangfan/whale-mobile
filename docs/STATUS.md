# WM 任务状态跟踪

> 按 ROADMAP §5 格式，每次合并更新。状态：not_started / in_progress / blocked / unit-tested / integration-tested / device-tested / merged。

### WM-01 — 仓库迁移、现状审计、单 APK Host 基线
- 状态：**device-tested**（leaf5 实测 2026-10-31 晚）
- 仓库：whale-mobile（本仓，全新无历史，用户决策 S2）
- 具体完成：三仓审计；骨架；CI 骨架；A1 模块生命周期状态机+总览页+一键暂停（flutter test 59/59、Kotlin 15/15）；release 正式签名链路（PKCS12 同密坑已录）
- **leaf5 真机 UI 验证**：模块总览/徽章/开关正常；一键暂停→传输断开+状态明示；恢复全部→自动重连→检索正常；sensor.ack 实测队列 SENT→ACKED（a2）停发
- 尚未实现：24h 耗电（采集脚本已挂测中）、厂商兼容性矩阵
- 协议版本与兼容影响：contracts/v1 `1.0-dev`（sensor.result +paused；sensor.ack 新增）
- 测试：契约 18/18；app 59/59；Kotlin 30+15；gateway 10/10
- 性能与电量：已实测（见 docs/BASELINE.md §5；24h 耗电待 leaf5 回线挂测）
- 风险与回滚：无（全新仓库）
- 下一依赖任务：WM-07 Gate A

### WM-02 — 端云 v1 契约、身份配对、认证通信、回执
- 状态：**device-tested**（参考服务端对接；whale-core 真实端点待 D1/D2 接口落地）
- 仓库：whale-mobile（contracts + transport + server/dev_gateway）；whale-core 侧为后续接口 PR
- 具体完成：PROTOCOL.md + 15 schema + 14 示例 + 校验器（17/17 含负例）；transport 客户端（Keystore Ed25519/OfflineQueue/WssClient，JUnit 18/18）；dev-gateway（pytest 10/10）
- **Leaf5 真机实测（2026-10-31）**：配对码审批 → Ed25519 挑战验签 → 在线心跳 → sensor.event 上行（断线重连重放被 event_id 幂等去重）→ action.request 下行 received 回执 → sensor.query 回环 fail-closed unavailable
- 尚未实现：core 侧 DeviceRegistry/WSS 端点（docs/integration/whale-core-device-api.md D1-D6）；release 走 wss://（TLS 由 tailscale serve 终结，debug 暂用明文 ws）
- 测试：契约 17/17；transport JUnit 18/18；gateway pytest 10/10；真机 E2E ✓
- 下一依赖任务：WM-04/06/07

### WM-03 — ScreenMemo MCP 路径引用/隐私出口加固
- 状态：**device-tested**
- 仓库：ScreenMemo fork 分支 `whale/hardening`（/opt/whale/ScreenMemo，4 提交：7862d4a/0b8d1b7/82ab5f8/6be7adc）
- 具体完成：opaque evidence_id（HMAC+DB 回查+根目录三重校验）；MCP 输出剥离 file_path/database.path/lanIp；常量时间 Bearer；默认绑 127.0.0.1（LAN 需显式开关+确认）；文档 docs/wm03-screenmemo-hardening.md
- **Leaf5 真机实测（2026-10-31）**：旧版 shot:base64 任意路径读取→拒绝；伪造 v2 HMAC 引用→拒绝；无 token→401；status 无敏感字段；无障碍截屏服务已绑定
- **采集→OCR→检索→证据 全链实测**：开启截屏后番茄小说真实截图入库 → `search_screenshots("番茄")` 命中（OCR 默认隐藏、ref 为 opaque `shot:v2.h*`、无 file_path）→ 合法 ref 经 `get_evidence_images` 正常取图（296KB webp）
- 测试：gradlew testDebugUnitTest（mcp.*）21/21；真机安全回归 ✓
- 剩余风险：见 wm03 文档（分片合并后旧 h 引用失效、MCP 无 TLS 沿用上游）

### WM-04 — ScreenMemo MCP Adapter + 三能力
- 状态：**device-tested**
- 仓库：whale-mobile（modules/screenmemo 设计 + app/lib/modules/screenmemo 实现）
- 具体完成：MCP 客户端（Bearer/超时/错误映射）、三能力映射、递归剥离 file_path/lanIp、滑动窗口限流（search 10/min、evidence 5/min）、Keystore 加密存 token、deadline 兜底必回包、证据图帧预算压缩
- **Leaf5 真机实测（2026-10-31）**：gateway `screen.history.search{"番茄"}` → 5 条真实命中（app/时间/opaque ref/privacy_class，OCR 默认隐藏）；`screen.evidence.read` → 296KB 原图压缩为 228KB base64 回传（帧预算内，标注 downscaled）；伪造 ref 被 ScreenMemo 拒（WM-03）
- 联调三修：Content-Length（服务端不支持 chunked）、证据图帧预算、管理通道流上限
- 测试：flutter test 31/31、analyze 0 issue、契约 17/17
- 剩余：全链路时延分布未测（Gate A 性能基线）；生产 wss 待正式机

### WM-05 — whale-core 屏幕权限 scopes 接线
- 状态：**device-tested**（2026-10-31 晚，真 core 实例实测）
- 仓库：whale-core-dev HEAD 6d2004c（设备面已由并行会话合并提交）
- 具体完成：DeviceRegistry/配对审批 REST/WSS 端点/screen.*+phone.* 九 scope 进 resolver（群聊硬拒）/sensor.event→perception；whale-core-dev 全量 1451 绿
- **真 core 实测**：leaf5 WSS 首连验签通过（online:true）；REST query→手机 ScreenMemo→真实命中返回；action→通知 executed；幂等键重复→409 duplicate；evidence 压缩图回传
- 尚未实现：D4 proof_method=device（QQ sudo 走设备确认）、D5 Studio 设备卡、生产实例（8180）部署
- 测试：registry 15 + ws 26 + scopes 9 新增用例
- 下一依赖任务：WM-07 Gate A、生产部署协调

### WM-06 — Android Host 权限、状态、通知、VoIP 兼容层
- 状态：**device-tested**（VoIP 在 nova、通知在 leaf5；Host 状态机 UI 尚简）
- 具体完成：modules/voip/（迁移+收口+nova 全链实测）；通知模块（校验链/频控/执行器注册表/权限向导）leaf5 实测 post/update/cancel/幂等/频控全绿
- **nova VoIP 实测（2026-10-31）**：来电响铃→接听→鉴权 PCM 桥（echo，85.2s/双向各 2.7MB）→挂断释放麦克风；chroot/nsenter 运维要点沉淀
- **leaf5 通知实测（2026-10-31）**：action.post→executed（通知栏可见 tag=gate-a-1）；同幂等键→duplicate 拒绝；频控 6/min 触发；cancel→executed
- 尚未实现：Host 模块状态机完整 UI（A1 项，当前为最小测试页）、通知点击事件上行
- 下一依赖任务：WM-07 Gate A

### WM-07 — Phase I 真机验收（Gate A）
- 状态：not_started（依赖 WM-04..06；设备矩阵见 docs/ARCHITECTURE.md §5）

### BG-01（Issue #2）— WSS/设备身份/离线队列脱离 Flutter Activity：NodeService
- 状态：**device-tested**（2026-11-01，leaf5 真 core）
- 具体完成：`WhaleApp`（Application）持有 `NodeRuntime` 进程级单例（WssClient/DeviceIdentity/OfflineQueue/通知·VoIP·safe_action 执行器唯一所有者，二次创建直接抛错）；`NodeService` 前台服务（dataSync 类型、whale_status 常驻通知 + 「停止」动作即断开）；`TransportBridge` 瘦身为 UI 客户端（Engine 销毁只注销监听/委托，不拆连接）；sensor.query 处理器链 paused→Dart 委托→fail-closed unavailable；新增 `setBgServiceEnabled`（whale_modules/bg_service_enabled，默认 true）
- **leaf5 实测**：UI BACK 销毁 Activity 后进程存活、NodeService 前台运行、core `online:true`；无 UI 时 `phone.notification.post` → **executed**（通知栏可见）；无 UI 时 `screen.history.search` → **unavailable**（source=none，无静默 accepted）；重进 UI → 状态补发 + Dart 委托自动恢复 → `screen.history.search` ok 命中
- 测试：JUnit 92/92（新增 runtime.SensorQueryDispatchTest/UiEventHubTest 10 例）；flutter 81/81；flutter analyze 0 issue
- 尚未实现：Doze/重启/网络切换恢复矩阵（BG-02）；Android 15 dataSync 6h 上限的降级提示（BG-02）
- 协议版本与兼容影响：无（contracts/v1 未动）

### BG-02（Issue #3）— 长期后台存活、重启恢复、网络恢复与任务调度
- 状态：**device-tested**（2026-11-01，leaf5 + nova 真 core）
- 具体完成：`RecoveryPolicy`（曾连接&&未暂停&&未撤销 才恢复，纯逻辑可单测）；`BootReceiver`（BOOT_COMPLETED→评估→重连）；`NodeRuntime.recoverIfNeeded`（process_start/service_restart/boot 三入口）；默认网络回调（恢复即重置退避立即重连）；`WssClient.reconnectNow` + `last_disconnect_reason` 诊断；getStateInfo 增 network_available/was_connected/battery_opt_ignored/node_service_running；毒丸防护（event_id<16 队列条目拦截丢弃，修复旧版 sm-N 短 id 随重连无限重发被 core schema 拒绝的问题）
- **leaf5 实测**：kill -9 → START_STICKY 拉起 → 自动重连 online；服务端（dev core）重启 → 双机 55s 内自动恢复；wifi 断开/恢复 → 自动回线；**整机重启** → BootReceiver 评估发起重连 → FGS 前台 → online（logcat 原文存档）
- **nova 实测**：写入 all_paused=true 后杀进程 → 进程被拉起但恢复评估明确「不恢复（paused=true）」，core 侧保持 offline——一键暂停优先于一切后台恢复
- 诚实边界：force-stop 后任何应用都不能自动恢复（Android 语义，不绕过）；Android 15 不允许开机拉起 dataSync FGS（届时进程内连接降级）；Doze/OEM 白名单差异待 Magic8 Pro（#7）
- 测试：JUnit 97/97（新增 RecoveryPolicyTest 5 例）；flutter 81/81；analyze 0
- 协议版本与兼容影响：无

---

## 阶段二（B 系列，Gate A 后启动）
WM-08..WM-15：not_started

### WM-09（B1 部分提前）— 增量事件管线
- 状态：**device-tested**（2026-11-01 凌晨，leaf5 + 真 core）
- 具体完成：ScreenMemo 事件出口（outbox/poll_events/三挂钩点/护栏，whale/hardening 67ce5e0，72/72）+ whale-mobile 轮询上传（游标幂等/退避/暂停遵守，68/68）
- **真机全链**：番茄/京东/Whale 切换与 OCR 产生真实事件 → 端侧轮询 → 真 core `device:event:*` 持久化（幂等键）
- 联调修复：event_id minLength 16 对齐、心跳活性看门狗修正
- 尚未实现：screen.topic_changed、simhash/时间窗合并、core 侧事件→initiative 消费（WM-10）

### WM-12（B4 前半提前）— VoIP 信令入宿主 + 真 core 全链
- 状态：**device-tested**（2026-11-01 凌晨，nova）
- 具体完成：VoIP 并入宿主（call.invite/hangup WSS 分发、phone.call.ring 执行器、校验链、模块卡片）；call.invite schema 增可选 number/name；契约校验 18/18
- **nova 实测（真 core）**：REST action phone.call.ring → 来电响铃 → 接听（intent 路径）→ call.state/call.answered 事件入 core（号码脱敏 ***0086）→ 挂断 → call.ended(duration_sec=123)
- **多设备**：leaf5 + nova 同时在线真 core，各自独立身份/scope
- 遗留：接听/挂断盲按坐标未命中（nova 无 uiautomator），走 intent 路径验证；桥地址 SharedPreferences 缓存坑（先读后写需重启 App）；来电时 PCM 桥对端为 127.0.0.1（桥配置缓存未生效所致，独立 APK 到 Tokyo 已证）
- 待真机补充：leaf5 无 telecom（已知）；Magic8 Pro 稍后按手册验收

### WM-12（B4 后半 Realtime 语音）— 进展
- 状态：**device-tested**（2026-11-01，nova↔东京 omni 桥）
- 具体完成：东京节点 echo→omni（Realtime 模型+6 语音工具）；统一宿主 Realtime 通话 50s 全链
- 尚未实现：ASR→LLM→TTS 后备链路的降级显示、通话中工具调用的 scope 审批联动、眼镜上镜

### WM-11（B3 前半）— 通知监听 + 受控设备动作
- 状态：**safe_action device-tested**；**notification.read unit-tested（设备绑定受阻）**
- nova 真 core 实测（2026-11-01）：launch_app 白名单内 executed；com.android.settings→scope_denied；intent://→scope_denied；https 深链→executed；均经校验链
- 通知监听：逻辑 18 单测全绿；但两台测试机（BOOX Leaf5 固件 + nova NetHunter GSI）均不绑定第三方 NotificationListenerService（系统 settings 已授予、包注册正确、重启无效），疑为定制固件限制——待 Magic8 Pro（常规手机系统）复验
- 已知边界：launch_app 在 App 后台时 Android 10+ 可能静默拦截前台化（回执 executed 但界面未拉起）——后续按「真实可见性」改进回执语义

---

## 发布记录（2026-11-01）

- 仓已公开：https://github.com/amwangfan/whale-mobile（4 个语义化提交；细粒度历史保留在本地 legacy-history 分支）
- 发布前泄露扫描：全历史无 API key/私钥/凭据/QQ 号；真实 IP（tailnet 100.x、东京公网）已置换占位符；密钥/签名/测量日志均不入库
- 推送前全量验证：契约 18/18、flutter 81/81、JUnit 83、gateway 10/10、debug 构建通过
