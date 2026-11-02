# 迁移记录：whale-io / ScreenMemo → whale-mobile

> 决策：全新仓库、不带 whale-io git 历史（其历史含真实 PII）。资产以文件级拷贝迁入并脱敏；whale-io 归档只读。

## 1. 资产处置表

| 来源 | 处置 | 目标位置 | 状态 |
| --- | --- | --- | --- |
| whale-io `infra/call-bridge/src`（com.nova.fakecall） | 迁入并 Gradle 化、改名 com.wangfanlab.whale、RingReceiver 权限收口、去 debuggable | `modules/voip/` | 待 WM-06 |
| whale-io `infra/call-bridge/audio-bridge`（Python） | 迁入（服务端组件，非手机代码）；ring_hub 双实现合一、SIGNAL_TOKEN 强制 | `server/` | 待迁移 |
| whale-io `infra/fg-monitor` | 不迁入；架构参考（flavor 设计、UsageStats+Accessibility 组合） | 留 whale-io 归档 | 已归档参考 |
| whale-io `docs/` 18 篇 | 选择性迁入并脱敏（设备类文档留；QQ/N100 运维文档不迁） | `docs/legacy/` | 待做 |
| ScreenMemo 整体 | 阶段一保持独立仓 fork（分支 `whale/hardening`），仅经 MCP 接入；阶段二再逐模块合并 | 外部依赖 | WM-03 进行中 |
| ScreenMemo MCP 契约 | 八工具→三能力映射 | `modules/screenmemo/` | 待 WM-04 |

## 2. 改名窗口清单（一次性）

- applicationId：`com.wangfanlab.whale`（app 宿主）
- VoIP 模块原 `com.nova.fakecall` 全部符号、广播 action、权限名改为 `com.wangfanlab.whale.voip.*`；RingReceiver 改非导出或加 signature 权限
- 音频/信令 Token 拆分（BRIDGE_TOKEN ≠ 设备凭据）

## 3. 已知泄露点处置

- whale-io `docs/deploy-n100-handoff.md`、`docs/research-weq-deepseek-qq.md` 含真实 QQ 号 → 不迁入本仓；whale-io 归档仓建议后续做历史脱敏或转私有（通知维护者）。
- 本仓红线：任何真实 QQ 号/手机号/API key/私钥/签名指纹/真实截图录屏不入库（CI 加 secret 扫描）。

## 4. 回滚策略

- 阶段一 ScreenMemo 独立 APK 不动用户数据；whale-mobile 卸载不残留后台进程。
- 阶段二合并 ScreenMemo 模块时：保留独立 APK 兼容路径直至实机对照测试通过；数据迁移可回滚（导出/导入走 ScreenMemo 既有备份能力）。
