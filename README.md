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
| 手机操作（daily/root） | UI 树、截图、点击/滑动/输入、应用启动；Root 版还可使用 Shell、文件、日志、录制 | daily 需手动启用 Whale 无障碍服务；Root 版普通操作优先走原有 Root 执行器，Root 独有能力保持不变；动作仍遵守 Core 配对 scopes 和紧急停止 |

Core 已有 Android 自主体编排器与模拟测试，但生产决策入口和 Bilibili 完整真机动作链仍需验收；有执行器不代表持续自主体验已经全部接通。

## 代码导航

| 位置 | 用途 |
| --- | --- |
| [`app/lib/`](app/lib/) | Flutter 主界面、模块开关、权限引导、传输客户端 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/) | NodeRuntimeV2、NodeServiceV2、重启与后台恢复 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/) | agent / capture / glasses / notifications / notificationread / voip / safeaction 等实际原生实现 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/) | V2 设备身份、WSS、离线队列、Flutter 原生桥 |
| [`contracts/v2/`](contracts/v2/) | 端云协议、Schema、示例（与 Core 同步） |
| [`server/`](server/) | 服务端音频桥与联调工具，不是 Android APK 内容 |
| [`docs/`](docs/) | 架构、真机验收、部署与迁移记录 |

仓库顶层的 `modules/voip/docs/` 和 `transport/` 仅保留历史设计资料；当前 Android 宿主以 `app/android/app/src/main/` 的 V2 装配关系为准。旧独立 VoIP APK 和重复 Java 源码已清理，媒体鉴权测试迁移至 `tests/test_voip_media_security.py`。

## 运行与开发

需要 Flutter、Android SDK 和 Java 开发环境。最简本地构建：

```bash
git clone https://github.com/amwangfan/whale-mobile.git
cd whale-mobile
python3 tests/contract_validate.py
cd app
flutter pub get
flutter test
flutter build apk --debug --flavor daily
```

安装后按 App 引导生成设备身份、连接 Core、配置所需模块。正式手机的安装和后台运行方法见 [Magic8 Pro 指南](docs/SETUP-MAGIC8.md)；真机能力和测试状态见 [STATUS](docs/STATUS.md)。

## 结构与技术栈

| 运行层 | 代码位置 | 技术 |
| --- | --- | --- |
| 应用页面 | `app/lib/features/`、`app/lib/modules/` | Flutter / Dart |
| 设备后台 | `app/android/app/src/main/kotlin/.../runtime/v2/` | Kotlin、Android Service、Lifecycle |
| 通信与缓存 | `transport/v2/`（Android 宿主内） | V2 WSS、Ed25519、SQLite 加密离线队列 |
| 感知与索引 | `modules/capture/`、ScreenMemo 客户端 | Android Accessibility、ML Kit OCR、SQLite / FTS |
| 自动操作 | `modules/agent2/` | UI 树、无障碍操作、Root Shell |
| 来电与语音 | `app/android/.../voip/`、`server/audio-bridge/` | Android Telecom、PCM/TCP |
| 界面通知与眼镜 | `modules/notification2/`、`modules/glasses/` | Android 通知、可插拔设备桥 |

已实测 V2 设备握手、通知、查询/回执和部分 Root 执行；连续的自主 APP 任务和雷鸟真实显示仍未完成完整验收。Flutter 页面只负责展示与配置，不持有后台 WSS 连接。

```bash
cd app
flutter build apk --debug --flavor daily
flutter build apk --debug --flavor root
cd android
./gradlew :app:testDailyDebugUnitTest :app:testRootDebugUnitTest
```

## 设备权限

- **日常手机**：按选定的数据源、通知/采集权限和设备 scope 提供感知；需要审批的操作遵守 Core 与端侧实际授权设置，常规通知无须额外阻断。
- **普通日常手机**：到 Android 系统无障碍设置手动开启「Whale 屏幕感知与操控」后，支持结构化 UI 观察、点击/滑动、焦点输入框设字、返回/主页/多任务和启动应用，无须 Root。仅可列出 Android 允许查询的启动器应用；部分特殊按键需要 Root/ADB。
- **鲸鱼专用 Root 手机**：保留原有 Root Shell、输入命令、文件、日志与录制能力，普通 UI 操作优先使用 Root；没有 Root 时可使用已启用的无障碍服务。不会因为无障碍动作结果不明而在 Root 端重放。仍保留紧急停止。
- **执行状态**：以动作终态回执和设备反馈为准。超时/离线/未知结果与执行成功是不同状态，避免重复执行有副作用的操作。

## CI（按需运行）

[ci 工作流](.github/workflows/ci.yml) 使用手动 `workflow_dispatch`，在 **Actions → ci → Run workflow** 选择 `contracts`、`app`、`secret-scan` 或 `all`。构建和测试结果以实际运行记录为准，不因为 PR 合并就默认通过。

## 许可与致谢

屏幕采集与检索参考 [ScreenMemo](https://github.com/woijfwefw/ScreenMemo)（AGPL-3.0）项目；部分 VoIP 与前台监测代码沿革来自 whale-io。引入第三方代码或素材时按其实际许可证处理。
