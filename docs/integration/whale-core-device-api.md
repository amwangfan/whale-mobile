# whale-core 接口需求（whale-mobile 对接 Issue 草案）

> 提交目标：whale-core 主仓（独立 issue/PR，一接口一 issue）。本文件只描述**需求与契约锚点**，不在手机仓复制 core 实现。
> 依据：whale-mobile `contracts/v1/PROTOCOL.md`（唯一协议事实源）。对齐策略：whale-core 持续开发中，按现状提需求，稳定后小补丁对齐（用户决策 S3）。

## Issue D1 — devices/ 设备注册与配对

**需求**：新增 `src/whale_core/devices/`：
- `DeviceRegistry`：设备注册/吊销/状态/公钥指纹，存 MemoryStore kv（对齐 `memory/spaces.py` 模式）。
- 配对端点 `POST /api/v1/devices/pair`：手机展示配对码 → 主人在控制台确认 → 签发设备凭据（绑定 Ed25519 公钥指纹 + scope 上限）。参考 `api/voice.py` 一次性票据模式与 `temporary_auth.py` 租约事务。
- 吊销即时生效；配对/吊销/每次 hello 全量审计（对齐 `audit:studio:*` 约定，不记正文）。

**契约锚点**：PROTOCOL.md §3（Ed25519 挑战-应答；device_id=公钥指纹前 16 字节 hex）。

## Issue D2 — WSS 设备端点

**需求**：`api/server.py` 新增 `head == "devices"` 分支或独立 listener：`WS /api/v1/devices/ws`。
- 连接即下发 `auth.challenge`；`device.hello` 验签通过前拒绝一切其他帧。
- 上行 `sensor.event` → PerceptionAdapter 入口；下行 `sensor.query`/`action.request` ← OutboundPort。
- 逐帧契约校验（schema 见 whale-mobile contracts/v1/messages/，建议 core 侧放版本化副本+校验测试，不依赖 Python 库跨仓导入）。
- 心跳/离线判定/幂等去重按 §2/§6。

## Issue D3 — screen.* scope 族与会话矩阵

**需求**：
- `life/conversation_context.py` 增加常量：`screen.context.read` `screen.history.search` `screen.evidence.read` `screen.live.subscribe` `phone.notification.post` `phone.notification.read` `phone.call.ring` `phone.call.audio` `phone.device.safe_action`。
- resolver 权限矩阵：screen.* 仅 owner 私聊/API owner 可授予；群聊硬上限拒绝（INV-03 一致）；`screen.evidence.read` 永远需要二次授权（逐笔或短时租约）。
- `derive_kind` 扩展认识 channel="mobile"。
- 检索结果强制标记来源 `screenmemo`/事件时间/设备 ID/隐私级；**不做默认全局召回**，仅在明确授权的当前主人会话进入短期上下文。

## Issue D4 — temporary_auth 设备确认路径

**需求**：`verify()` 放开 QQ_PRIVATE 硬编码；落地 `proof_method="device"`（租约确认推送到已配对设备，主人在手机上点确认）。契约锚点：PROTOCOL.md §4 `action.request.approval_ref`。

## Issue D5 — Studio 设备节点卡（阶段二，B6）

**需求**：`api/studio.py::_graph` 增 `kind:"device"` 节点（数据源=D1 registry）；前端 `DeviceCard`（S6 画布一部分）。非法连线（私人原始库直连会话）API 侧拒绝。

## Issue D6 — 审计与指标

**需求**：设备面全量留痕：配对/解配/hello/事件上行计数/指令下行裁决/回执状态机迁移；接口 `GET /api/v1/devices` 列表与单设备详情（供 Studio/控制台）。

---

**依赖关系**：D1→D2→D3→D4；D5/D6 阶段二。whale-mobile 侧以 `server/dev_gateway.py` 为参考实现先行联调，core 落地后替换，协议不变。
