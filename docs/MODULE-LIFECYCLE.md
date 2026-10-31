# MODULE-LIFECYCLE — 模块生命周期与状态机（A1）

Host 层收口的模块注册表与状态推导。实现：`app/lib/modules/registry.dart`（纯 Dart 可测），
UI：`app/lib/main.dart`（模块总览页），原生配合：`TransportBridge`（all_paused 落盘 + 执行器前置拦截）。

## 状态机

```
installed → permissionRequired → disabled ⇄ enabled → starting → running
                                      ↘ running → degraded / error → stopped
```

| 状态 | 含义 |
|---|---|
| installed | 已安装未就绪：transport 从未连接；screenmemo 未配 token；权限状态未知 |
| permissionRequired | 所需运行时权限未授予（notification_post: POST_NOTIFICATIONS） |
| disabled | 用户关闭模块开关（whale_modules/<id>_enabled） |
| enabled | 前置条件满足的就绪态（事件驱动模块无持续运行态；或已配置待验证） |
| starting | 启动中（transport 连接/认证） |
| running | 正常运行 |
| degraded | 降级运行（transport 掉线重连；screenmemo 在线但数据库不可用） |
| error | 错误（screenmemo 鉴权失败/不可达/ping 失败） |
| stopped | 已停止（transport 连接过又断开；或全局暂停收束） |

**全局暂停（allPaused）为最高优先级覆盖**：暂停期间所有模块 status = stopped。

## 内置模块与推导来源

| 模块 | 权限 | 常驻 | 可选 Root | 隐私级 | 状态来源 |
|---|---|---|---|---|---|
| transport | 无 | ✔ | ✘ | public | TransportClient.stateStream：online→running、connecting/awaitingAuth→starting、degraded→degraded、disconnected→stopped（连过）/installed（未连过）；开关=连接/断开 |
| notification_post | POST_NOTIFICATIONS | ✔ | ✘ | owner_private | 权限状态+模块开关：未知→installed、未授予→permissionRequired、关→disabled、授予+开→enabled |
| screenmemo | 无（需 token + ScreenMemo App 存活） | ✘ | ✘ | owner_sensitive | token+开关+ping：无 token→installed、关→disabled、待验证→enabled、在线→running、库不可用→degraded、鉴权失败/不可达→error |

## 一键暂停

- UI：AppBar 暂停/恢复按钮。暂停 = 断开传输 + 关闭全部模块开关 + `setAllPaused(true)`；
  恢复 = 逆向（按暂停前快照还原开关，传输仅当暂停前在线才回连）。
- 落盘：MethodChannel `whale/transport` 新增 `setAllPaused({paused})` / `isAllPaused()`
  （SharedPreferences `whale_modules/all_paused`），重启后保持暂停、不自动回连。
- 原生执行器校验链前置检查（暂停时）：
  - `NotificationModule`（NotificationDecider 第 0 环）：action 回 `failed/unavailable`，不消耗频控配额；
  - `sensor_query` 分发（TransportBridge.onSensorQuery）：不分发 Dart，直接回 `sensor.result status="paused"`。
- `sendSensorResult` status 白名单相应扩展为 `ok/denied/unavailable/error/paused`。
