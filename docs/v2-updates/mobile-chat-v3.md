# 移动端聊天面 v3 收敛（Link RPC + 事件流 + artifacts）

> 2026-11-24 · 契约 `D:\Whale_new\docs\mobile-chat-convergence.md`（M1-M5 已批）落地记录。
> 聊天面收敛到 v3 统一主干，**v2 聊天 REST 旁路（`/api/v2/conversations`、
> `/api/v2/chat/stream`、`/api/chat/attachments` multipart）已删除**。

## 1. 连接形态（M2/O1 双连接）

- 设备 WS（`/api/devices/ws`，Kotlin `WssClientV2`）：身体/能力/通知，**既有不动**。
- Link WS（`ws(s)://{base}/api/link`，role=mobile）：聊天面**前台按需连接**——
  `LinkSession` 引用计数，聊天 tab 可见且应用前台时建立，切走/退后台断开。
  通知永不丢走设备 WS；Link 在线时 `notify` 事件在会话列表 SnackBar 即时展示。
- 鉴权 **C 路 link_ticket**（主人定，已落地）：建连前经 `LinkTicketService`
  以设备 Ed25519 身份换票（`POST /api/devices/link_ticket`，§11 签名四头，
  签 `device_id\nts\nnonce\nPOST\n/api/devices/link_ticket`）→ hello 带
  `ticket`、固定 role=mobile；主 token 不上机。票 900s 可复用重连（留 30s
  安全边际）、过期重签；4401/4403 终态 → 作废重签再连（连续 3 次失败给
  「设备可能已被撤销」明确提示）。artifacts/SSE 退路无 token 时带
  `X-Whale-Link-Ticket` 头（core 的 /api/chat/stream 票校验为核心差距）。
- 4401（鉴权失败）/4403（服务端 kick）为终态：不裸重连，先作废重签；
  连续失败给明确提示。

## 2. Link RPC 层（`lib/transport/link/link_client.dart`）

- 信封 `v=1.0` + ULID id + `corr` 配对 + 130s 自超时（超时=结果未知，绝不重放）。
- 错误码枚举 invalid_params/unauthorized/forbidden/not_found/conflict/
  rate_limited/internal/unavailable/timeout；本地合成码 origin=local。
- `request()` 从不自动重试；读路径（`LinkChatApi._read`）仅在服务端
  `retryable:true` 时自动重试一次。
- 单帧 ≤1 MiB 本地拒发（invalid_params）；30s ping + 3×间隔无下行帧看门狗；
  断线退避 1s/2s/5s/15s/30s 封顶。
- **落点说明**：Link RPC 层落在 Dart 而非 Kotlin——聊天面是 Flutter UI，
  且验收要求「假 Link server 桩（dart:io WebSocket）」在 `flutter test` 下
  驱动握手/RPC/事件流/断线补齐/越权（flutter test 的 Dart VM 够不到 Kotlin）。
  通道模式（串行生命周期/连接代际/退避/看门狗）复用 `WssClientV2` 既有口径。

## 3. 聊天主链路（`chat_controller.dart` v3）

- 发送 `chat.send{text, conversation_id, images[]≤4, persona_id?, wait:false}`
  → 立返 run_id → 事件流 `run.started`（思考中）→ `run.step`（状态行/轨迹）→
  `run.delta`（流式上屏）→ `run.completed` 定屏；`run.failed/cancelled` 显式落态。
- 事件早于 rpc.response 的竞态：按 conversation_id 认领本会话在飞 run。
- 打断 `chat.cancel{run_id}`（仅 Link 路径；SSE 退路无打断通道，UI 不出按钮）。
- 历史 `conversations.messages{id, limit, before=毫秒ts}` 正序拉取、新→旧展示。
- 断线补齐：重连 welcome(isReconnect) → `conversations.messages` 补对话 →
  在飞 run 三级回落：补齐的 assistant 消息为准 → `events.query(run.*)` →
  `runs.get`。
- 弱网/无长连退路 `POST /api/chat/stream`（SSE `run/step/delta/done`，
  `chat_stream_v3.dart`）；连接在 done 前关闭 → 显式失败不伪装完成。

## 4. artifacts（`artifact_service.dart`）

- `GET /api/artifacts/{id}`（Bearer）拉取/预览。
- `POST /api/artifacts`（raw body + X-Whale-Name/X-Whale-Content-Type）：
  core 未实现面 → 404 特性探测降级为显式 not_implemented，不重试。
- 聊天图片第一版：`chat.send.images` 收 data: URL（≤4 张每张 ≤2MB，
  `ChatImages.fitToLimit` 超界自动降尺寸/重编码）；图片回合估帧超 1MiB 时
  走 SSE 退路（HTTP 无帧上限，同一份 Run 语义）。
- 正文里的 `art_/att_+ULID` 引用渲染为可点芯片 → GET 拉取。

## 5. 审批（M1 手机可见可裁决）

- `approval.requested` 事件 → 待决列表；`approval.resolved` → 移除
  （含服务端 600s 超时拒绝 by=system）。
- 审批卡：args 原文展开 + note + 600s 倒计时；一次性核销，
  重复裁决 conflict → 显式提示 + 权威刷新。
- 审批中心页：待决 + 历史（`approvals.list(status=null)`，含 note/裁决人）。
- 管理面（config.*/models.*/plugins.*/memory 写/policy.set）手机不给 UI；
  服务端 role=mobile 白名单拦截（越权回 forbidden，测试已覆盖 config.get）。

## 6. 记忆提示/工具轨迹（M1）

- 默认折叠成「想起了 N 条 · 调用了 xxx」一行（`TraceExpansion`），点击展开
  run.step 轨迹（recall/think/tool/approval/reply 各带 title/status/detail）。
- 历史消息按 meta（memories/tools）展示摘要；轨迹明细只对在飞/刚完成回合留痕。

## 7. 测试

- `test/link/fake_link_server.dart`：dart:io WebSocket 假 Link server
  （握手/RPC/事件脚本/断线控制/审批一次性/白名单 forbidden）。
- `test/link/link_client_test.dart`：握手/welcome/4401 终态/corr 配对/
  130s 自超时绝不重放/帧上限/ping/退避重连/越权 forbidden/读路径重试策略。
- `test/chat/chat_controller_v3_test.dart`：发送主链路（Link+竞态认领）/
  打断/历史 before-ts 分页/断线补齐（assistant 消息为准 + events.query 回落）/
  SSE 退路（含 1 字节分包）/模型解析。
- `test/chat/approval_controller_test.dart`：审批事件/裁决/conflict/
  断线补齐/600s 倒计时。
- `test/chat/chat_page_test.dart`：widget 冒烟（内存 FakeChatBackend，不出网）。
- `test/chat/artifact_service_test.dart`：GET Bearer/上行 201/404 探测降级。
