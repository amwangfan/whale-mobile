# Gate B / Gate C 发行验收记录（Issue #16）

> 日期：2026-11-01。基线 `main`（AUTH-01 合并后）。本文只记录已跑过的验证，不含未完成功能的空勾选。

## 一、功能闭环状态

| 范围 | Issue | 状态 | 证据 |
| --- | --- | --- | --- |
| 后台运行时（NodeService/恢复/网络） | #2 #3 | device-tested | leaf5/nova/Magic8 Pro 实测（重启/强杀/断网/服务端重启恢复；暂停/撤销不恢复） |
| 队列加密 / 监听生命周期 / 回执幂等 | #4 #5 #6 | device-tested | nova 密文落库 0 明文；冷启动无 UI 事件不丢；跨重启 unknown 不重放 |
| 内置采集影子 + 互斥 | #8 | device-tested | leaf5：互斥 60/60 跳过；真实采集 22ms；nova API28 明确降级 |
| 原生 OCR/索引/迁移工具 | #9 | device-tested | leaf5 中文 OCR 命中；真实分库夹具 77/77 幂等导入 |
| 数据源可撤销切换 | #10 | device-tested | leaf5 无 UI 时 builtin 源答查询；还原后 ScreenMemo 不受影响 |
| 感知数据治理 | #11 | device-tested | leaf5：近重复合并、过期不补传、预算生效 |
| 主动提醒闭环 | #12 | device-tested | nova 全链：事件→规则→自动通知→executed 回执；core 接线 whale-core-dev 4b77ce6（默认关） |
| 眼镜 HUD 模块 | #13 | device-tested（降级路径） | nova 三路径（degraded/scope_denied/unavailable）；实物 shown 级验收用户暂缓 |
| VoIP 稳定化 | #14 | device-tested | nova+Magic8：media ready(omni)、失败自动挂断+通知、麦克风释放 |
| 手机可信确认 | #15 | device-tested | nova：批准/拒绝/超时三路径，proof_method=device 租约 |
| Gate A 收口 | #7 | 收口（用户决定） | Magic8 Pro 主矩阵全过；24h 耗电用户暂缓 |

## 二、质量门槛

- 契约校验：contracts/v1 校验器 20/20（17 正例 + 3 负例，18 schema）。
- 单测：Flutter 89/89；Android JUnit 148/148（含 Robolectric 真实 SQLite）；whale-core 1681 全过。
- 构建：debug 与 **release 正式签名** APK 均构建通过；release 包在 nova 清洁安装→启动→运行时拉起→恢复策略正确拒绝（未配对状态）。
- 秘钥扫描：gitleaks CLI 全历史 0 findings（.gitleaks.toml 仅放行契约示例/测试夹具）。
- CI：按需手动（用户决定），入口 Actions → ci → 选择 checks。

## 三、支持矩阵（诚实边界）

| 设备 | 系统 | 已验证 | 明确限制 |
| --- | --- | --- | --- |
| Magic8 Pro（BKQ-AN10） | Android（MagicOS） | 全矩阵（含通知监听/VoIP/重启自恢复） | **须「应用启动管理」手动三项全开**，否则关屏后 CPU 冻结断连；tailnet ACL 默认不放行到东京桥（经 N100 中继绕） |
| leaf5（BOOX Leaf5） | Android 13 | 屏幕采集/检索/事件治理/恢复矩阵 | 通知监听固件不绑定（记录）；无 telecom（无 VoIP）；电商休眠 WiFi 会断连 |
| nova（Android 9 GSI） | Android 9 | 通知/VoIP/监听/治理/可信确认 | 无 takeScreenshot（API<30 明确降级，不 Root）；PIN 锁屏下 UI 自动化受限 |

## 四、回滚方案

- 数据源：capture_data_source 默认 mcp，builtin 可随时切回；ScreenMemo 迁移只读旧库 + 备份先行 + checkpoint 断点。
- 连接：一键暂停/断开/撤销设备各自 fail-closed；撤销后队列清空。
- 卸载：用户显式卸载即全清（Keystore 身份随卸载失效，需重配对）。

## 五、已知未完成（如实）

- 雷鸟 iO 实物 shown 级验收（用户暂缓；模块与降级已证）。
- leaf5 24h 耗电基线（用户暂缓；采集器已备）。
- 话中工具调用的 scope 审批联动属 whale-core 侧运行时。
