<div align="center">

# whale-mobile

*身无彩凤双飞翼，心有灵犀一点通*

鲸鱼在 Android 上的身体与感官：感知、通知、VoIP、眼镜交互和设备自主操作。

[English](README_EN.md) · [架构](docs/ARCHITECTURE.md) · [AI 开发指南](docs/AI_DEVELOPMENT_GUIDE.md) · [当前进度](docs/STATUS.md)

</div>

whale-mobile 是 [whale-core](https://github.com/amwangfan/whale-core) 的 Android 节点。手机负责采集、展示、实时交互和执行，Core 负责人格、记忆、任务决策和多设备编排。两侧通过 [contracts/v2](contracts/v2/) 中的版本化协议通信。

目标不是把手机做成另一个聊天 App，而是让鲸鱼拥有可使用的感官和身体：能感知日常，给用户提示，也能在专用设备上自主观察和操作。普通手机与专用 Root 手机可以采用不同权限配置。

## 当前能力

| 模块 | 已实现的内容 | 使用情况 |
| --- | --- | --- |
| 后台与传输 | Application 级 NodeRuntime、前台服务、重启/网络恢复、WSS 身份校验、加密离线队列、动作回执 | Android 原生侧运行，不依赖 Flutter 页面常驻 |
| 日常感知 | ScreenMemo MCP、内置影子采集、ML Kit OCR、索引和数据源切换、通知监听 | 采集源与权限可在 App 中配置 |
| 通知与交互 | 通知发布/撤销、设备状态、暂停和回执 | 普通手机可用 |
| 通话 | Android Telecom 来电和 PCM 音频桥 | 需要麦克风等 Android 权限和已配置的桥 |
| 眼镜 | 雷鸟 iO 通知/HUD 传输适配与状态反馈 | 具体显示能力以设备及宿主联调结果为准 |
| AI 专用手机 | UI 树、截图、点击/滑动/输入、应用启动、媒体键、Root Shell 持续会话、输出回读、紧急停止 | 需要设备 Root、App 的模块开关和持续 Root 授权，以及 Core 端配对的 agent scopes |

Core 已有 Android 自主体编排器与模拟测试，但生产决策入口和 Bilibili 完整真机动作链仍需验收；有执行器不代表持续自主体验已经全部接通。

## 代码导航

| 位置 | 用途 |
| --- | --- |
| [`app/lib/`](app/lib/) | Flutter 主界面、模块开关、权限引导、传输客户端 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/) | NodeRuntimeV2、NodeServiceV2、重启与后台恢复 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/) | agent / capture / glasses / notifications / notificationread / voip / safeaction 等实际原生实现 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/) | V2 设备身份、WSS、离线队列、Flutter 原生桥 |
| [`contracts/v2/`](contracts/v2/) | 端云协议、Schema、示例（与 Core 同步） |
| [`server/`](server/) | 服务端音频桥与联调工具，不是 Android APK 内容 |
| [`docs/`](docs/) | 架构、真机验收、部署与迁移记录 |

仓库顶层的 `modules/`、`transport/` 等目录仍包含历史设计或独立模块资料；当前 Android 宿主以 `app/android/app/src/main/` 的 V2 装配关系为准。`modules/voip/` 的旧独立工程仍待清理（#35），不是宿主运行时入口。

## 运行与开发

需要 Flutter、Android SDK 和 Java 开发环境。最简本地构建：

```bash
git clone https://github.com/amwangfan/whale-mobile.git
cd whale-mobile
python3 tests/contract_validate.py
cd app
flutter pub get
flutter test
flutter build apk --debug
```

安装后按 App 引导生成设备身份、连接 Core、配置所需模块。正式手机的安装和后台运行方法见 [Magic8 Pro 指南](docs/SETUP-MAGIC8.md)；真机能力和测试状态见 [STATUS](docs/STATUS.md)。

## 设备权限

- **日常手机**：按选定的数据源、通知/采集权限和设备 scope 提供感知；需要审批的操作遵守 Core 与端侧实际授权设置，常规通知无须额外阻断。
- **鲸鱼专用 Root 手机**：用户可在 App 打开「设备自主体」与「持续 Root Shell 授权」，让已配对的 Core 使用 UI 操作、观察和 Root Shell；不设置无意义的命令白名单。手机端仍提供紧急停止，方便立即接管。
- **执行状态**：以动作终态回执和设备反馈为准。超时/离线/未知结果与执行成功是不同状态，避免重复执行有副作用的操作。

## CI（按需运行）

[ci 工作流](.github/workflows/ci.yml) 使用手动 `workflow_dispatch`，在 **Actions → ci → Run workflow** 选择 `contracts`、`app`、`secret-scan` 或 `all`。构建和测试结果以实际运行记录为准，不因为 PR 合并就默认通过。

## 许可与致谢

屏幕采集与检索参考 [ScreenMemo](https://github.com/woijfwefw/ScreenMemo)（AGPL-3.0）项目；部分 VoIP 与前台监测代码沿革来自 whale-io。引入第三方代码或素材时按其实际许可证处理。
