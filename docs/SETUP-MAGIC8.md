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

1. 打开 Whale App（首次会自动生成设备身份，主页显示 device_id 与公钥）
2. 在「ScreenMemo」卡片粘贴上一步的 token → 保存 token → 点「测试连接」应显示运行中
3. 把 device_id 与公钥发给管理员（或手机截图）

## 3. 管理员配对（N100 上执行）

```bash
TOKEN=$(cat /tmp/whale-dev/api_token)
curl -s -X POST http://127.0.0.1:8190/api/v1/devices/pair \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"pubkey_hex":"<手机公钥>","label":"magic8-pro"}'
# 返回 6 位配对码 → 批准：
curl -s -X POST http://127.0.0.1:8190/api/v1/devices/pair/approve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"<配对码>"}'
```

## 4. 联调

手机上打开「传输」开关 → 状态应变「在线」。之后即可在 N100 上发起查询/通知验证。

## 注意

- 正式机保持 Tailscale 在后台运行（否则掉线）。
- MagicOS 后台管理严格：Whale 与 ScreenMemo 都加入「应用启动管理」白名单并允许后台活动。
- 截屏仅在所选应用前台时进行；随时可用 Whale 首页「一键暂停」全部停止。
