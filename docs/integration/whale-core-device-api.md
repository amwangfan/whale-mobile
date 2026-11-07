# Core 设备接口：V2 当前状态

> 本文替代早期 V1 接口需求草案。当前设备协议唯一事实源为
> [contracts/v2](../../contracts/v2/)；Core 实现位置见
> [whale-core/docs/devices.md](https://github.com/amwangfan/whale-core/blob/main/docs/devices.md)。

## 已实现的设备接口

- 配对、批准、列表、详情、scope 调整与撤销：Core 管理 REST（全站路径前缀为 `/api/v1`）。
- 设备长连接：`/api/v2/devices/ws`，使用 V2 challenge / hello、Ed25519 身份和逐帧 schema 校验。
- 上行：`experience.event`、`sensor.result`、`action.receipt`、通知回执、RunTrace 与录制状态。
- 下行：`sensor.query`、`action.request`、通知事件、设备确认和录制控制。
- 设备撤销、scope 收窄、五终态回执与持久化幂等由 Core 生产设备网关处理。

## 当前集成边界

- Android 宿主由 `NodeRuntimeV2`、`WssClientV2` 和 V2 模块构成。
- daily/root flavor 在 hello 中声明不同的真实能力；Core 再按设备 scope 收窄。
- Core 的 `DeviceGatewayV2` 已有多 transport 选路接口；ADB/SSH 生产接线与统一持久化回执仍是后续工作。
- Root 手机 Bilibili 完整自主任务链仍待真机验收。

旧 V1 设备接口草案不再作为实现要求；新增接口应直接更新 canonical V2 schema、黄金示例和两仓同步校验。
