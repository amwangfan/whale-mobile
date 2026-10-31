# modules/notifications — 通知模块设计（WM-06 后半）

> 阶段一范围：只做 `phone.notification.post`（发布/更新/撤销 + 回执）。`phone.notification.read`（NotificationListener）是阶段二独立可选模块。

## 1. 责任

接收 contracts/v1 `action.request{capability:"phone.notification.post"}` → 端侧白名单校验 → 发布/更新/撤销 Android 通知 → 回报 `action.receipt` 精确状态（received→accepted→executed/failed）。

## 2. 端侧校验链（全部通过才执行）

1. transport 层已过：设备验签、idempotency_key 去重、expires_at 有效期。
2. 模块开关：通知模块 enabled（用户可一键停）。
3. 权限：Android 13+ `POST_NOTIFICATIONS` 运行时权限；未授予 → `failed{error_code:"permission_missing"}` 并引导 UI。
4. 参数白名单：title ≤ 100 字符、text ≤ 500 字符、channel_id ∈ 注册表、click_action 仅允许打开本 App 深链或云端已授权深链（拒任意 Intent/URI 跳转第三方）。
5. 频控：同一会话每分钟 ≤ 6 条通知（防通知风暴）。

## 3. 动作语义

- `args.op = "post" | "update" | "cancel"`；`update/cancel` 须带 `notification_tag`（幂等定位）。
- 通知渠道：`whale_reminder`（提醒）、`whale_call`（通话，高优先级）、`whale_status`（常驻状态，低优先级）。
- 点击回执：用户点击通知 → 本地记录 → 下一心跳捎带 `action.receipt{state:"executed", provider_ref:clicked}`？——V1 简化：发布成功即 executed；点击事件进 sensor.event（kind=notification.clicked，privacy_class=owner_private）。

## 4. 代码落点

- Kotlin：`app/android/.../modules/notifications/NotificationModule.kt`（渠道注册、发布、频控、回执）
- 桥接：TransportBridge 的 action_request 事件 → Dart → 仅 UI 展示；**执行在 Kotlin 侧直接做**（通知不需 Dart 参与，减少时延）——TransportBridge 注册 `ActionExecutor` 接口，NotificationModule 注册为 `phone.notification.post` 的执行器，WssClient.onActionRequest 校验后调执行器并回终态。
- Dart：模块开关 + 权限状态展示（main.dart 状态页）。

## 5. 测试

- JUnit：频控窗口、参数白名单（超长截断/非法深链拒绝）、op 语义、回执状态机。
- 真机（nova + leaf5）：gateway CLI action → 通知栏出现 → 重复幂等键不重复响 → 撤销设备后动作不再执行。

## 6. 显式非目标（阶段一）

通知监听（NotificationListener）、通知回复（RemoteInput）、媒体通知样式。
