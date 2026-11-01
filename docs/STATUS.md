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

### WM-07 / GA-01（Issue #7）— Gate A 收口
- 状态：**in_progress**（2026-11-01 Magic8 Pro 已入网完成主矩阵；待 leaf5 24h 耗电基线读完即收口）
- **Magic8 Pro（BKQ-AN10，生产非 Root）实测**：配对/审批/上线 ✓；通知 post executed ✓；通知监听全链（授权+系统绑定+白名单事件入 core，leaf5/nova 的固件不绑定问题在正式机不存在）✓；safe_action launch_app executed（unverified_no_usage_access 诚实标注）/https 深链 executed/微信与设置 scope_denied ✓；VoIP ring→answer→media ready(omni)→stopped ✓（经 N100 中继绕 tailnet ACL）；影子采集 attempts/unique/dup 统计 + IME 拦截 7 次真实触发 ✓；**关屏待机在线（应用启动管理白名单后）+ 整机重启自动恢复在线（无人工介入）** ✓
- **关键运维结论（MagicOS/类 OEM）**：仅 FGS + deviceidle 白名单不够——必须「应用启动管理」手动三项全开，否则系统 CPU 冻结进程致 socket 死亡（state 文件与 core 双侧证据）；已写入部署 SOP
- 调试通道：OEM 无 logcat 固件经 filesDir 快照文件观测（node_state.json / notifread_debug.log / capture_stats.json，仅 debug 包）
- 待：leaf5 24h 耗电读数（采集中）；Doze 深睡矩阵按 MagicOS 白名单前提记录

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

### SEC-01（Issue #4）— OfflineQueue 敏感 payload 加密、版本迁移及撤销后清理
- 状态：**device-tested**（2026-11-01，nova 真 core）
- 具体完成：`QueueCipher` 接口 + `IdentityQueueCipher`（复用身份 Keystore AES-256/GCM 主密钥，材料不出 Keystore）；`payload_json` 列自 DB v2 起存 `v1.` 前缀密文；v1→v2 单事务迁移（读旧明文→加密→写回；幂等跳过已加密；加密失败删除不留明文）；出库解密失败 fail-closed 丢弃并计入 dropped（绝不上传明文/半解密）；无 cipher 打开加密库即拒绝读出
- **nova 实测**：升级安装后 ring 动作 executed 触发真实事件流 → 队列 12 条全部 `v1.` 密文、0 明文行、PRAGMA user_version=2、事件正常 SENT→ACKED（解密 flush 无回归）
- 测试：JUnit 103/103（新增 OfflineQueueEncryptionTest 6 例，Robolectric 真实 SQLite：迁移/幂等/密文落库/损坏 fail-closed/无 cipher 删除明文/容量幂等不变）
- 撤销后清理：device_revoked 时 queue.clear() 原有语义不变（密文行一并清空）；卸载由系统擦除
- 协议版本与兼容影响：无

### SEC-02（Issue #5）— NotificationListenerService 独立接入：无 UI 也不丢已授权事件
- 状态：**device-tested**（2026-11-01，nova 真 core；老认知「nova 不绑定监听」本次证伪，见下）
- 具体完成：钩子未就绪→有界缓冲（NotificationEventBuffer 容量 20，溢出丢最旧计数）不静默丢；onListenerConnected/Disconnected 观测（bound 与 granted 分开）；解绑自动 requestRebind；缓冲回放；bindingStatus（permission/bound/last_bound_at/buffered/dropped_overflow）经 getNotificationReadStatus 出桥；UI 模块卡「监听权限/系统绑定」分行显示
- **nova 实测**：kill -9 冷启动 → BG-02 自动重连（全程未开 UI）→ core 下发 notification.post → **系统独立绑定的监听器捕获** → 白名单过滤 → core 落 `notification.posted | Whale: SEC02冷启动验证`；授权授予但模块/白名单默认关时事件丢弃（fail-closed 日志可证）
- 测试：JUnit 107/107（新增 NotificationEventBufferTest 4 例）；flutter 81/81；analyze 0
- 设备兼容记录：leaf5（BOOX 固件）历史观测不绑定；Magic8 Pro 复测归 #7
- 协议版本与兼容影响：无

### SEC-03（Issue #6）— 动作回执终态语义、幂等持久化及真实可见性
- 状态：**device-tested**（2026-11-01，nova 真 core）
- 具体完成：action_dedup 持久幂等表（whale_transport.db v3，4096 容量 FIFO）；WssClient 下行幂等改读持久表——重复键：在飞→重发 received、已终结→重发真实终态、**跨重启未终结→unknown 绝不重放**；无执行器且无 UI → 立即 failed/unavailable（不再悬挂 accepted）；Dart 未注册 capability 回 failed/unavailable；Dart/执行器终态均落幂等表（sendActionReceipt 按 action_id 补记）；校验失败（expired/scope_denied/capability_unknown）同步落终态
- **nova 实测**：同幂等键 REST 重发 → core 409 duplicate（端侧持久表为深度防御）；无 UI 时 screen.live.subscribe（无执行器）→ **failed/unavailable**；无 UI 时 notification.post → **executed**（两条路径均诚实终态）
- 诚实边界：safe_action launch_app 维持 foreground_verified/background_restricted/unverified_no_usage_access 三态（此前已实现）；跨重启 unknown 的设备端注入演练因设备无 sqlite3 未做，由 Robolectric 存储层测试 + 代码审查覆盖
- 测试：JUnit 113/113（新增 ActionDedupStoreTest 6 例）；flutter 81/81
- 协议版本与兼容影响：无（回执状态机不变；端侧幂等不依赖 core 行为）

### SM-01（Issue #8）— 内置截屏采集模块：影子对照、采集互斥与系统权限
- 状态：**device-tested**（2026-11-01，leaf5 Android 13 + nova Android 9 真 core）
- 具体完成：`modules/capture/`（CaptureDecider 纯逻辑护栏链：模块开关→一键暂停→独立 ScreenMemo 互斥→锁屏→排除包名→输入法→间隔节流；DHash 64 位去重；ShadowCaptureService 无障碍服务（canTakeScreenshot=true、canRetrieveWindowContent=false、只收窗口切换事件驱动前台识别）；ShotTaker30 隔离 API 30+ 截屏调用（HARDWARE→ARGB_8888 转换后仅算 dHash，位图立即回收，不落盘不 OCR）；CaptureModule 门面 + getCaptureStatus/setCaptureEnabled/openAccessibilitySettings 桥；UI 影子采集卡（统计实时可见）；CaptureStats 每分钟 logcat 心跳（无内容数据）
- **leaf5 实测**：ScreenMemo 采集中时 60/60 tick 全部 conflict_standalone_screenmemo 跳过（互斥成立）；临时切换采集源后真实影子采集 attempts=12/unique=2/dup=10/fail=0、latency 22ms（切屏即 unique、静态画面正确去重）；验证后已还原 ScreenMemo 采集并关闭本模块
- **nova（API 28）实测**：takeScreenshot 不可用 → 明确 api_below_30 降级跳过，服务稳定不崩溃（低版本不 Root、不反射）
- 联调修复：canRetrieveWindowContent=false 时 getWindows() 为空 → 前台识别改窗口事件包名；HARDWARE 位图不可 getPixels → 先转软件位图；tick 异常吞没防 worker 线程死亡；connect 幂等（防恢复路径与 UI 自动连接竞态被服务端踢会话）
- 测试：JUnit 122/122（CaptureDeciderTest 9 例）；flutter 81/81
- 协议版本与兼容影响：无（影子统计仅本地，未上行）

### SM-02（Issue #9）— 原生 OCR、本地索引与 ScreenMemo 数据迁移工具（可回滚）
- 状态：**device-tested**（2026-11-01，leaf5 真机）
- 具体完成：`CaptureStore`（whale_capture.db：截图元数据+去重键(capture_time,package,dhash)+FTS5 索引，FTS5 不可用时自动 LIKE 降级——BOOX 实测触发该路径）；`MlKitOcrEngine`（text-recognition-chinese 本地识别，Tasks.await 同步等待——修复 listener 回调丢失假超时）；`CapturePipeline`（唯一帧→JPEG 落 filesDir→OCR→索引，失败记 ocr_status=failed 可重试）；`ScreenMemoImporter`（真实分片布局 shards/<pkg>/<year>/smm_*.db、shots_YYYYMM 表；校验→备份→事务增量导入（checkpoint 断点续跑）→去重幂等；is_deleted 尊重旧库删除语义）；persist 开关（capture_persist_enabled，默认 false 纯影子）；importScreenMemoData 桥（主线程回执）
- **leaf5 实测**：唯一帧落盘 JPG + ML Kit 中文 OCR 真实命中（设置页 163 字：「设置/网络和互联网/…」）+ 索引可查；真实分库夹具（脱敏后入库测试资源）导入 77/77 行、去重/checkpoint/备份全过
- 测试：JUnit 135/135（CaptureStoreTest 8 + ImporterTest 5 含真实夹具）；flutter 81/81
- 诚实边界：迁移期条目不带本地图像（image_path 空，evidence.read 对其回 unavailable）；数据源切换属 #10
- 协议版本与兼容影响：无

### SM-03（Issue #10）— 统一 APK 数据源切换、发布回滚与旧 ScreenMemo 兼容期
- 状态：**device-tested**（2026-11-01，leaf5 真 core）
- 具体完成：`NativeScreenQueryHandler`（builtin 数据源：context.read/history.search/evidence.read 直答 CaptureStore，字段形状与 MCP adapter 对齐，Core 无感知；证据 JPEG 帧预算阶梯压缩）；数据源开关 whale_capture/data_source（mcp|builtin，默认 mcp；builtin 需持久化+无障碍前置校验，不过拒绝）；切换审计日志（无内容）；UI 采集卡加持久化开关/数据源切换/迁移导入入口与结果展示；NodeRuntime sensor.query 路由（builtin 优先，MCP 回退路径保留）
- **leaf5 实测**：builtin 数据源下销毁 UI → `screen.history.search("设置")` → source=capture.local 真实命中（OCR snippet 200 字内、opaque capture:N 引用）；`screen.evidence.read capture:1` → JPEG 压缩回传（64KB，downscaled）；还原回 mcp 后 ScreenMemo 独立采集不受影响
- 回滚：数据源默认 mcp；builtin 可随时切回；导入工具只读旧库且备份先行；旧 ScreenMemo APK 不卸载要求
- 测试：JUnit 142/142（NativeScreenQueryHandlerTest 7 例）；flutter 81/81
- 协议版本与兼容影响：无（source 字段细化，字段向后兼容）

### EV-01（Issue #11）— 增量感知数据治理：合并、去重、隐私预算
- 状态：**device-tested**（2026-11-01，leaf5 真 core）
- 具体完成：`EventGovernor`（纯 Dart 可单测）：过期丢弃（>10min 不补传）、隐私关键词过滤（输入法/锁屏/支付等默认拒绝 + 用户黑白名单）、同 App 同 kind 60s 时间窗合并、simhash64 近重复抑制（汉明 ≤4）、每 App 12/h 与全局 120/h 滑动窗口预算；接入 EventPoller（治理为终态：丢弃即推进游标）；治理统计入 UI 导出状态行
- **leaf5 实测**：注入 2 条同内容同 App 新鲜事件 → core 仅收 1 条（近重复被合并抑制），游标推进不卡死；存量 6 条 >10min 旧事件按 stale 规则不补传（游标推进至 211 而 core 无新增）；事件源侧修复：ScreenMemo fork 补显示源目标变化挂钩（whale/hardening edd0cef，修复 BOOX 固件无障碍事件断流）
- 测试：flutter 89/89（新增 event_governor_test 8 例：stale/过滤/黑白名单/合并/simhash/预算/哈希性质）；JUnit 142/142
- 与 #4 对接：事件经 WssClient 离线队列，payload 已应用层加密
- 协议版本与兼容影响：无

### EV-02（Issue #12）— 主动提醒闭环（手机侧完成度）
- 状态：**mobile 部分 device-tested**（2026-11-01，nova 真 core）；Core 运行时自治接线待 whale-core 侧
- 具体完成：contracts/v1 action.request 增可选 correlation_id（校验器 18/18，示例同步）；whale-core-dev gateway/REST 透传（2e7082c，28 项设备测试过）；NotificationModule 执行后 provider_ref 回显 corr:<id>（审计链闭合）
- **nova 实测（真 core dev）**：事件 device:event 落库 → POST action 带 correlation_id → executed → 回执 provider_ref=corr:sm-0000000000212 → core receipts 链可追
- 诚实边界：Core 运行时自治决策接线（sensor_event→situation→rule→remind 的活路由与规则配置）属 whale-core 侧（其 #9 已闭于桥/测试层）；本项在 mobile 侧所需全部能力已就绪且真机验证
- 负例复核：一键暂停/模块关/撤销 fail-closed 由 BG/SEC 系列覆盖
- 协议版本与兼容影响：contracts/v1 minor（可选字段）

### GL-01（Issue #13）— 眼镜 HUD 桥（模块化 + 降级路径）
- 状态：**device-tested（降级路径）**（2026-11-01，nova 真 core）；真实眼镜联动待硬件
- 具体完成：`modules/glasses/`（GlassesLogic 纯逻辑：title≤40/body≤120/ttl 3s-300s/owner_sensitive 不上镜/6 次每分频控/暂停与模块 fail-closed；GlassesModule 执行器 + 可插拔 GlassesTransport（当前 NullTransport）；无桥降级手机通知 + provider_ref=degraded:phone_notification，转发成功标 forwarded_no_display_ack 绝不伪报 shown）；V1_CAPABILITIES/Dart 双侧能力表同步 glasses.card.*；PROTOCOL.md V1.1 候选记录
- **nova 实测（真 core）**：show 无桥 → executed + degraded:phone_notification（手机通知可见）；owner_sensitive → failed/scope_denied；dismiss 无桥 → failed/unavailable
- 联调修复：能力枚举三处 schema（hello/hello_ack/action.request）+ BG-02 恢复路径能力漂移（恢复改报当前全集，core 按 scopes 收窄）+ Dart/原生双能力表同步
- 测试：JUnit 148/148（GlassesLogicTest 6 例）；flutter 89/89
- 待硬件：雷鸟 iO 实桥（Turbo-IO/厂商 API 通道待用户确认）与真机 shown 级验收
- 协议版本与兼容影响：contracts/v1 minor（能力枚举扩充，向后兼容）

### VOICE-01（Issue #14）— VoIP Realtime 稳定化与可见降级
- 状态：**device-tested**（2026-11-01，nova 真 core + 东京 omni 桥）
- 具体完成：AudioBridge 媒体状态机（connecting/ready(mode 从桥端 HELLO 解析 omni|agent|echo)/retrying/failed/stopped）经 MediaStateListener → call.state 事件上行；AUTH_FAIL 即 failed；重试耗尽 → 媒体失败 → 自动挂断（不留死通话）+ 用户通知「语音桥不可用」；FakeConnectionService 通话期间 phoneCall 类型前台服务（API 29+ 带类型，ended 退出）
- **nova 实测**：正常链 ringing→answered→media connecting→ready(mode=omni, voice=Tina, tools=1)→ended(duration=20s) 全事件序列入 core；桥死端口 → retrying×8 → failed → 自动挂断 call.ended + 通知可见 + dumpsys 确认麦克风 0 残留会话
- 诚实边界：ASR→LLM→TTS 为桥端模式（东京 server.py echo/agent/omni），手机侧经 mode 字段可见当前模式；话中工具调用的 scope 审批属 core 侧
- 测试：既有 VoipLogic/NotificationLogic 单测全过（148/148）；nova 双向实测
- 协议版本与兼容影响：无（call.state summary 文本扩展，字段不变）

### AUTH-01（Issue #15）— 手机可信确认：一次性授权、审批回执与撤销
- 状态：**device-tested**（2026-11-01，nova 真 core 三路径）
- 具体完成：contracts/v1 minor（device.confirm.request/response 两消息类型 + schema + 黄金示例，校验 20/20）；`AuthConfirmModule`（结构/有效期/设备绑定校验；过期即拒；whale_confirm 高优通知 + ConfirmActivity 锁屏全屏确认；同意→Ed25519 签名 challenge_id → 回执；拒绝/超时 approved=false+reason；绝不默认批准）；WssClient 新增类型分发与 sendConfirmResponse；whale-core-dev 配套（2abdcee：gateway send_confirm_request/响应分发 + POST /devices/{id}/confirm 创建挑战→签名核验→原子核销签发租约；核心1681 测试全过）
- **nova 实测（真 core）**：同意 → grant 签发（proof_method=device，max_uses=1）；拒绝 → 409 user_denied；不操作超时 → 409 expired（端侧到期自动按拒绝回执）
- 纪律复核：已配对≠已确认（每挑战独立 nonce 一次核销）；群聊来源只展示不提权；撤销设备后网关拒绝所有帧
- 协议版本与兼容影响：contracts/v1 minor（新消息类型，两端同步）

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
