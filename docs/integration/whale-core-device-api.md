# Core 设备接口：现行状态（2026-11-23 clean break）

> 本文替代早期 V1 接口需求草案。当前设备协议唯一事实源为
> [contracts/device](../../contracts/device/)（canonical 在 Core 仓，本仓为镜像）。

## 已实现的设备接口

- 配对（owner-first）：Core 控制台签发 6 位码 → 手机 `POST /api/devices/pair`
  `{code, pubkey_hex, label}`（免令牌）→ 立即激活（应答含 device_id/name/scopes/ws_path）。
  列表、详情、改名、scope 调整与撤销：Core 控制台 RPC 面。
- 设备长连接：`/api/devices/ws`（路径去版本化），使用 challenge / hello（信封 v=1.x）、
  Ed25519 身份和逐帧 schema 校验；WS 地址优先取配对应答的 ws_path。
- 上行：`experience.event`、`sensor.result`、`action.receipt`、通知回执、RunTrace 与录制状态。
- 下行：`sensor.query`、`action.request`、通知事件、设备确认和录制控制。
- 设备撤销、scope 收窄、五终态回执与持久化幂等由 Core 生产设备网关处理。

## 当前集成边界

- Android 宿主由 `NodeRuntimeV2`、`WssClientV2` 和 V2 模块构成。
- daily/root flavor 在 hello 中声明不同的真实能力；Core 再按设备 scope 收窄。
- Core 的 `DeviceGatewayV2` 已有多 transport 选路接口；ADB/SSH 生产接线与统一持久化回执仍是后续工作。
- Root 手机 Bilibili 完整自主任务链仍待真机验收。

旧 V1 设备接口草案与旧 `contracts/v2` 均不再作为实现要求；新增接口应直接更新 canonical（Core 仓 contracts/device）schema、黄金示例和两仓同步校验。
