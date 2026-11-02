# Gate A 验收手册（阶段一总验收）

> 原则：全部真实设备实测；区分联网/断网/重连/强杀/重启；不录入敏感截图；每项记录时间/错误码/回执。
> 设备：leaf5（Android 13，唯一验收机——用户 2026-10-31 决定 Magic8 Pro 暂不参与）。nova（VoIP 线，Android 9）已先行完成 VoIP 验收。

## 验收结果汇总（2026-10-31，真 whale-core 实例 :8190/:8192）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 配对→审批→WSS 验签→在线 | ✅ | devices online:true，session 建立 |
| 撤销即时踢线 + 拒重连 | ✅ | revoke→kicked:true→offline；重连被拒；purge→重配对→手动恢复在线 |
| 伪造签名/未知 major/schema 违例 | ✅ | pytest 26 项（test_devices_ws） |
| 断网恢复补发不重复 | ✅ | WiFi 断 40s→重连→服务端 event_id 去重 + sensor.ack 停发 |
| 强杀/重启恢复 | ✅ | 身份保持，自动回连（上次在线时） |
| screen.history.search 真机命中 | ✅ | 番茄小说真实记录，OCR 默认隐藏，opaque ref |
| screen.evidence.read 二次授权 + 帧预算压缩 | ✅ | 296KB→228KB base64，downscaled 标注 |
| ScreenMemo 停服 fail-closed | ✅ | MCP 关 → sensor.result unavailable |
| 通知 post/cancel/幂等/频控 | ✅ | 通知栏可见；duplicate 409；6/min 限流 |
| VoIP 来电→PCM 双向→挂断释放 | ✅ | nova 实测 85s 全双工满速 |
| 日志无敏感内容 | ✅ | gateway 脱敏日志（kind/长度，不打 summary）；端侧不记 token |
| 性能基线 | ✅ | docs/BASELINE.md §5（P50=308ms 等） |
| 24h 耗电 | ⏳ | 采集中（battery-20261031-1920.log，明早读数） |

**Gate A 判定：主要闭环在真实设备可重复完成；安全回归通过；通知可送达。待 24h 耗电数据补齐后正式关门。**

## 验收结果追加（2026-11-01，Magic8 Pro BKQ-AN10 生产非 Root，真 core dev）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 配对/审批/Ed25519 上线 | ✅ | devices online:true（label=magic8-pro） |
| 通知 post | ✅ | executed |
| 通知监听全链 | ✅ | 授权+系统绑定+白名单事件入 core（leaf5/nova 固件不绑定问题在正式机不存在） |
| safe_action 矩阵 | ✅ | launch_app executed（unverified_no_usage_access 诚实标注）；https 深链 executed；微信/设置 scope_denied |
| VoIP（Telecom+PCM） | ✅ | ring→answer→media ready(mode=omni)→stopped（经 N100 中继绕 tailnet ACL） |
| 影子采集 | ✅ | attempts/unique/dup 统计累积；IME 拦截真实触发 |
| 关屏待机在线 | ✅ | 前提：应用启动管理手动三项全开（否则 CPU 冻结断连——见 SOP） |
| 整机重启自动恢复 | ✅ | 开机无人工介入自动上线（BootReceiver→FGS） |

**MagicOS/类 OEM 后台运维结论**：仅 FGS + deviceidle 白名单不够，必须「应用启动管理」手动三项全开。

## 0. 前置

- [ ] leaf5：Whale（最新 debug 包）+ 加固版 ScreenMemo 运行中；截屏开启；MCP 回环模式
- [ ] nova：VoIP APK 安装、权限齐、来电账户启用
- [ ] N100：dev-gateway 运行（`scripts/devgw.sh start`），tailnet 互通

## 1. 身份与连接

- [ ] 新设备配对：配对码 → 审批 → hello 验签 → online（记录 session_id）
- [ ] 撤销设备：revoke → 已在线连接下一帧被踢（error.device_revoked）→ 新连接被拒
- [ ] 伪造 device_id/签名 → auth_failed，无状态泄露
- [ ] 断网 60s → 重连自动恢复；期间事件入队，恢复后补发且 gateway 去重（无重复执行）
- [ ] App 强杀 → 重开 → 身份保持（Keystore）、队列保持、可重连
- [ ] 手机重启 → 手动/自动重连（记录是否需人工介入）

## 2. 屏幕检索闭环（leaf5）

- [ ] `screen.history.search` 按关键词命中真实记录（返回 app/时间/opaque ref，无 file_path，OCR 默认隐藏）
- [ ] `screen.context.read` 时间段摘要返回
- [ ] `screen.evidence.read` 合法 ref 返回压缩图（downscaled 标注，≤帧预算）
- [ ] 伪造 evidence_ref（旧 base64 格式/假 HMAC）→ 拒绝
- [ ] ScreenMemo 停止（停 MCP 服务）→ 查询回 `unavailable`（不伪造结果）
- [ ] 限流：search 11 次/分钟 → 第 11 次 denied/rate_limited
- [ ] 关闭 screenmemo 模块开关 → denied，ScreenMemo 本体不受影响

## 3. 通知输出

- [ ] `phone.notification.post` → 通知栏出现（渠道/标题/正文正确）
- [ ] 同 idempotency_key 重复下发 → 只通知一次
- [ ] 过期动作（expires_at 已过）→ failed/expired，不补执行
- [ ] 模块关闭 → 不执行且回执明确
- [ ] Android 13+ 通知权限撤销后 → failed/permission_missing（不崩溃）

## 4. VoIP（nova）

- [ ] 广播/信令触发来电 → 全屏 UI 响铃
- [ ] 接听 → PCM 桥建立（双向 16kHz）
- [ ] 挂断 → 麦克风释放（无残留占用）、连接关闭、回执完整
- [ ] 重复触发同 action → 不重复响铃

## 5. 隐私与权限矩阵

| 场景 | 预期 |
| --- | --- |
| 群聊/非主人请求 screen.* | 拒绝（core 侧，等 WM-05；当前由 dev-gateway scope 模拟验证） |
| sudo 临时授权 | 不超 scope、过期失效（等 WM-05） |
| 日志检查 | 全链路日志无 OCR 原文/截图/token（`adb logcat` + gateway 日志抽查） |

## 6. 性能基线（实测填数）

| 指标 | 实测 | 方法 |
| --- | --- | --- |
| query→result 端到端时延 | | gateway CLI 计时 ×10 取 P50/P95 |
| 事件上行时延 | | occurred_at vs gateway 接收时间 |
| 手机待机耗电 | | 24h 电池统计（ScreenMemo+Whale 同开） |
| 内存占用 | | adb dumpsys meminfo |
| 存储增长 | | ScreenMemo 库 + 队列 24h 增量 |

## 7. 通过定义（Gate A）

以上 1-5 全绿 + 6 已实测填数（无"达标"预设，只要求有真实数据）+ 原有 ScreenMemo 独立运行不受影响。
