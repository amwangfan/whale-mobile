# 荣耀 Magic8 Pro 实机安装手册（Gate A 正式机验收）

> 目标：在非 root 的 Magic8 Pro 上完成 Gate A 验收闭环。
> 安装包：`dist/whale-mobile-debug.apk`（宿主）、`dist/screenmemo-hardened-debug.apk`（加固截屏源）。
> 把两个 APK 传到手机（QQ 文件/U 盘/局域网均可），点击安装（允许安装未知来源应用）。

## 1. ScreenMemo 设置（5 分钟）

1. 打开 ScreenMemo → 走完引导（下一步×2 → 选择应用建议全选 → 开始使用）
2. 授权弹窗全部允许（通知/使用情况访问）
3. 系统设置里开启「屏忆」无障碍服务（设置 → 无障碍 → 已安装服务 → 屏忆 → 开）
4. 回 ScreenMemo 首页，点右上角相机图标开启截屏（图标变亮即开启）
5. 设置 → MCP 服务 → 打开「局域网 MCP 服务」开关（保持「仅回环」模式，不要开局域网模式）
6. 同页点「复制接入说明」或长按复制 token（48 位字符），备用

## 2. Whale Mobile 设置

1. 打开 Whale App（首次会自动生成设备身份）
2. 在「ScreenMemo」卡片粘贴上一步的 token → 保存 token → 点「测试连接」应显示运行中

## 3. 配对（owner-first，2026-11-23 clean break 起）

1. 主人在 Core 控制台「设备 → 配对新设备」生成 6 位配对码（10 分钟有效，一次性）。
2. 手机打开「我的 → Core 连接设置」，填好 Core 地址（配对 POST 与 WS 共用该基地址），
   输入 6 位码（可选填设备备注）→ 点「配对并连接」。
3. App 自动完成 `POST /api/devices/pair {code, pubkey_hex, label}` 兑换（免令牌），
   立即激活并以应答 `ws_path`（`/api/devices/ws`）建立 WSS 长连。

旧 device-first 流（手机出码 → 主人 curl `/api/v1/devices/pair*` 批准）已被 core 删除，无替代。

## 4. 联调

配对后状态应变「在线」；模块总览（旧版）页的「传输」开关可手动断/连。
之后即可在 N100 上发起查询/通知验证。

## 注意

- 正式机保持 Tailscale 在后台运行（否则掉线）。
- MagicOS 后台管理严格：Whale 与 ScreenMemo 都加入「应用启动管理」白名单并允许后台活动（**必须手动三项全开**：自启动/关联启动/后台活动；只做系统电池优化白名单不够——实测不加则关屏后进程被 CPU 冻结、WSS 死亡）。
- VoIP 语音桥经 tailnet 到东京节点：确认 tailnet ACL 放行本机到 <东京 tailnet IP 见内部账本>:18799/18800（或经 N100 中继 <N100 tailnet IP 见内部账本>:18799）。
- 截屏仅在所选应用前台时进行；随时可用 Whale 首页「一键暂停」全部停止。
