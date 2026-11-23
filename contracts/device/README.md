# contracts/device

Whale 端云协议（whale-mobile ↔ core 控制通道），信封 `v` 现行 `1.x`，WSS 路径 `/api/devices/ws`。
本目录是两侧**唯一协议事实源**；whale-core 与 whale-mobile 各持一份镜像，禁止单侧修改——改动必须双仓同窗口同步。

- 协议文档：[PROTOCOL.md](PROTOCOL.md)（破坏性变更汇总见 §18，含 2026-11-23 路径去版本化 + 信封 1.x 的 clean break 条目）
- `messages/`：WSS 逐消息 JSON Schema（draft 2020-12）
- `objects/`：共享对象合同（DeviceDescriptor / ExperienceEvent / EvidenceRef / AttachmentRef / RunTraceEvent / RecordingManifest / ApprovalRequest）
- `examples/`：黄金示例（交叉一致：同一 device_id / ULID 族 / 时间序列）
- `objects/examples/`：对象合同正例

校验：core 侧 `whale.devices.contract.ContractValidator`（schema 小子集）+ `tests/test_devices.py` 契约用例（正例逐条过 schema、负例覆盖版本不符/缺字段/错 dir/ID 格式）。

**schema 子集约束**：校验器只实现 draft 2020-12 小子集，禁止使用 `$ref`/`$defs`/`if/then/else`/`oneOf`/`anyOf`/`allOf`/`patternProperties`/相对 `$id`。

历史：本目录前身 `contracts/v2`（信封 `2.x`）；V1 契约早已移除。2026-11-23 clean break 后路径与信封代次对齐 core v1，目录去版本化。
