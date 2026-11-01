<div align="center">

# whale-mobile

*身无彩凤双飞翼，心有灵犀一点通*

鲸鱼的手机端身体与感官：感知、通知、VoIP、眼镜交互与设备执行。

</div>

---

**whale-mobile** 是鲸鱼运行在 Android 手机上的节点。它持续提供经过授权的手机端感知（屏幕、前台应用、通知），并承接由云端 [whale-core](https://github.com/amwangfan/whale-core)（大脑）授权的通知、实时通话、眼镜显示和设备操作。

与云端的关系：**感知、整理、交互和执行在手机；理解、人格、记忆治理和行动决策在 Core。** 两侧通过 [`contracts/`](contracts/) 中版本化的协议通信，互不依赖对方实现细节。

## 目录导航

| 路径 | 内容 | 语言 / 构建 |
|------|------|-------------|
| [`app/`](app/) | Flutter 宿主 UI：模块配置、设备配对、状态页、权限向导 | Flutter / Dart |
| [`modules/screenmemo/`](modules/screenmemo/) | ScreenMemo MCP 安全适配器（screen.context / search / evidence 三能力） | Dart + Kotlin |
| [`modules/notifications/`](modules/notifications/) | Android 通知栏发布/更新/撤销与回执 | Kotlin |
| [`modules/voip/`](modules/voip/) | 自管 Telecom 来电、接听/挂断、PCM 音频桥（迁自 whale-io call-bridge） | Kotlin/Java |
| [`modules/glasses/`](modules/glasses/) | 雷鸟 iO 眼镜通知与 HUD 桥 | Kotlin |
| [`transport/`](transport/) | WSS 控制通道、设备身份、离线队列、幂等接收 | Kotlin |
| [`server/`](server/) | 云端侧音频桥与信令（部署于服务器，非手机） | Python |
| [`contracts/`](contracts/) | 端云协议 v1：JSON Schema + 示例 + 版本策略 | JSON Schema |
| [`docs/`](docs/) | 架构、迁移、基线与安全文档 | Markdown |
| [`tests/`](tests/) | 契约测试、跨仓协议校验 | Python/Dart |

## 安全与隐私基线

- 原始截图、OCR、凭据、通知正文**默认不出设备**；跨端访问需明确 scope 授权。
- 设备指令必须认证、限权、可审计、可撤销；端侧独立校验白名单，不执行任意云端 Shell。
- 基本功能不依赖 Root；Root 仅作可选增强模块。
- 未连真机验收的能力一律标称未完成（`unit-tested` / `integration-tested` / `device-tested` / `production-ready` 四级）。

## CI（按需运行）

GitHub Actions 的 [ci 工作流](.github/workflows/ci.yml) 不再在 push / PR 时自动运行，与 whale-core 的按需测试方式保持一致。需要检查时打开 **Actions → ci → Run workflow**，选择：

- `contracts`（默认，最轻量）：仅校验端云协议 Schema 和黄金示例。
- `app`：先校验协议，再构建 Android Debug APK，执行 Flutter/Dart 与 Kotlin 单测。
- `secret-scan`：只执行 Gitleaks 历史凭据扫描。
- `all`：执行以上全部检查，建议用于准备合并重要功能或正式发行前。

注意：**不自动运行不等于测试通过**。PR 合并和发布前需要手动运行适用检查，并保留结果；若仓库设置了 Required Status Checks，需要相应调整保护规则，避免等待不再自动触发的状态。

## 许可与致谢

- 本仓库 © whale-mobile 作者们。
- 屏幕采集与检索能力致谢 [ScreenMemo](https://github.com/woijfwefw/ScreenMemo)（AGPL-3.0）项目。
- VoIP 与前台监测基础设施源自 whale-io 项目。
