# B1 增量感知事件管线 — 设计（阶段二预研，Gate A 后实施）

> 原则：事件产生于 OCR 写入/动态生成/前台切换的**真实完成时刻**，不用周期全库扫描伪装实时。

## 1. 事件源（ScreenMemo 侧挂钩点）

| 事件 kind | 触发点（ScreenMemo 源码位置） | privacy_class |
| --- | --- | --- |
| screen.ocr_ready | ScreenCaptureScreenshotProcessing OCR 落库后 | owner_private |
| screen.segment_ready | SegmentSummary 段落生成/合并完成 | owner_private |
| screen.app_changed | ScreenCaptureAccessibilityService 前台窗口切换 | owner_private |
| screen.topic_changed | 段落主题漂移（既有动态总结输出） | owner_private |

实现形态：ScreenMemo 新增「事件出口」可选开关（默认关）——上述点位产生事件后写本地 outbox 表（SQLite），whale-mobile 经 MCP 拉取新事件（长轮询或 ScreenMemo 主动 POST 回环到 Whale 端口，二选一，优先拉取：不改变 ScreenMemo 网络面）。

## 2. 端侧过滤与合并（上传前）

- 排除名单：密码键盘/支付/锁屏/私密 App（用户可配清单，默认含系统锁屏与输入法）
- 相似度去重：OCR 文本 simhash 距 < 阈值 → 丢弃
- 时间窗合并：同 app 连续事件 60s 内合并为一条（保留首尾时间）
- 降采样：每 app 每分钟 ≤ 2 条
- 暂停（all_paused 或 screenmemo 模块关）→ 不入队、不补传

## 3. 上传

- 走既有 transport sensor.event + OfflineQueue（event_id 幂等、sensor.ack 闭环）
- payload 只含 summary（≤200 字符）+ occurred_at + evidence_ref（opaque）；**不含截图/OCR 原文**
- 离线队列上限 1000，溢出丢最旧并计 dropped（心跳 modules{} 上报队列水位）

## 4. Core 侧消费（等 WM-05/WM-10）

sensor.event → perception/ 适配器 → initiative 策略（冷却/静默/配额）→ 主动提醒经 action.request 回到手机通知。

## 5. 验收标准（阶段二实机）

真实使用 1 小时：事件量 10–60 条（视操作强度），无隐私排除项泄漏，无重复风暴，断网 30min 恢复后按序补传不丢不重。

## 6. 实现状态

### ScreenMemo 侧（分支 whale/hardening）✅ 已完成

- **outbox 表**：主库 `sensor_events`（id PK 自增游标 / kind / occurred_at / privacy_class / summary≤500 / evidence_ref nullable / dedupe_key UNIQUE / created_at）。
  - Flutter 迁移：`lib/data/database/screenshot_database.dart` `_dbVersion` 56→57，`_onUpgrade` 加 `oldVersion < 57` 步，建表函数在 `lib/data/database/screenshot_database_sensor_events.dart`。
  - 原生幂等建表：`android/.../database/SegmentDatabaseHelper.kt` `ensureSchema`（L432 起）；事件写入侧 `events/SensorEventOutbox.kt` `ensureSchema`。
- **事件挂钩点**（均为真实完成时刻，各 1 处）：
  - `screen.ocr_ready`：`capture/ScreenCaptureScreenshotProcessing.kt` `runOcrAsyncAndPersist` 中 `updateOcrTextByFilePath` 落库后（L73 调用 `emitOcrReadyEvent`，助手 L112）；dedupe=shot 行 id（新增 `ScreenshotDatabaseHelper.findShotRowIdByFilePath` L380），evidence_ref=McpEvidenceSecurity SHOT 引用。
  - `screen.app_changed`：`capture/ScreenCaptureAccessibilityService.kt` L1022（`stable != prevStable` 分支内）；dedupe=包名+分钟桶。
  - `screen.segment_ready`：`segment/SegmentSummaryManager.kt` L1349（`finishSegment` 中 `updateSegmentStatus(..., "completed")` 之后）；dedupe=segment_id。
- **隐私护栏**（`events/SensorEventOutbox.recordEvent` 集中执行）：`event_export_enabled=false`（默认）直接丢弃；截屏总开关 `screenshot_enabled=false` 丢弃；来源包命中排除（锁屏中/输入法，探针可替换测试）丢弃；挂钩点本身位于既有监控名单过滤之后。
- **MCP 工具** `poll_events`：`mcp/McpToolRegistry.kt`（L21/L115/L149 注册），实现在 `mcp/ScreenMemoMcpRepository.kt` `pollEvents` L225；入参 since_id(默认0)/limit(默认50，≤200)，按 id 升序返回 id/kind/occurred_at/privacy_class/summary/evidence_ref，无 file_path；开关关闭时 isError 提示未开启。
- **证据引用密钥共享**：新增 `mcp/McpEvidenceSecretProvider.kt`，`ScreenMemoMcpRepository.evidenceSecret()` 收敛为委托调用（行为不变）。
- **设置开关**：`event_export_enabled`（`UserSettingsKeysNative` / `UserSettingKeys.eventExportEnabled`），设置→MCP 页新增「事件导出（供 Whale）」开关（`settings_page_mcp_part.dart` `_buildEventExportCard`），四语种 l10n。
- **测试**：`android/app/src/test/kotlin/.../events/SensorEventOutboxTest.kt`（表创建/三类写入/dedupe 唯一/排除拦截/开关关闭拒绝/游标语义）与 `.../mcp/PollEventsToolTest.kt`（未开启 isError、升序游标、无路径泄漏）；新增 testImplementation Robolectric 4.15.1（仅测试依赖）。
- **顺带修复（达成全绿所必需）**：分支 HEAD 既有 13 个陈旧失败用例（a083e58 拆分后反射目标失效/链式 editor 桩不全/Looper 未 mock）。已按测试侧最小修复：三个 SegmentSummary*Test 改为直接调用 internal 顶层函数；AccessibilityServiceTest 补 editor 链桩；`AccessibilityStateMonitor.handler` 改 lazy（设备行为不变）。

### 遗留（后续）

- whale-mobile 侧：MCP 客户端轮询 `poll_events` → 组装 sensor.event（WM-03 信封）→ OfflineQueue 上传；`screen.topic_changed` 事件未挂（主题漂移点待阶段二再定）。
- 端侧高级过滤（simhash 去重、60s 时间窗合并、每 app 每分钟 ≤2 条降采样）当前仅由分钟桶 dedupe 覆盖 app_changed，其余在 whale-mobile 汇聚侧实现。
