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
| 后台与传输 | Application 级 NodeRuntime、前台服务、重启/网络恢复、WSS 身份校验（Ed25519 设备身份）、加密离线队列、动作回执 | Android 原生侧运行，不依赖 Flutter 页面常驻；回执区分 succeeded/failed/timeout/cancelled/unknown 五终态，幂等不重放 |
| 日常感知 | ScreenMemo MCP、内置影子采集、ML Kit OCR、索引和数据源切换、通知监听 | 采集源与权限可在 App 中配置 |
| 通知与交互 | 通知发布/撤销、多渠道、审批按钮、深链、暂停和回执 | 普通手机可用；回执 `displayed` 表示已提交 Android 通知系统，不等于用户已看到 |
| 通话 | Android Telecom 来电和 PCM 音频桥、通话状态事件 | 需要麦克风等 Android 权限和已配置的桥 |
| 眼镜 | 卡片校验、频控、敏感内容不上镜、可插拔卡片桥接口 | 需配置真实眼镜桥接通道；未连接眼镜时自动降级为手机通知 |
| 手机操作（daily/root） | UI 树、截图、点击/滑动/输入、应用启动；Root 版另有 Shell、文件、日志、录屏等能力 | daily 需手动启用 Whale 无障碍服务；动作遵守 Core 配对 scopes 和紧急停止 |

V2 设备握手、通知、查询/回执、Root 执行与 Bilibili 全链（打开→搜索→播放→回报标题）已在真机验证，详见 [STATUS](docs/STATUS.md)。眼镜日历、卡片与 followup 接入见 [Core 眼镜集成文档](https://github.com/amwangfan/whale-core/blob/main/docs/glasses-joint-integration.md)。

## 代码导航

| 位置 | 用途 |
| --- | --- |
| [`app/lib/`](app/lib/) | Flutter 主界面、模块开关、权限引导、传输客户端 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/) | NodeRuntimeV2、NodeServiceV2、重启与后台恢复 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/) | agent / capture / glasses / notifications / notificationread / voip / safeaction 等实际原生实现 |
| [`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/`](app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/) | V2 设备身份、WSS、离线队列、Flutter 原生桥 |
| [`contracts/v2/`](contracts/v2/) | 端云协议、Schema、示例（与 Core 同步） |
| [`server/`](server/) | 服务端音频桥与联调工具，不是 Android APK 内容 |
| [`docs/`](docs/) | 架构、真机验收、部署与集成记录 |

顶层 `modules/` 与 `transport/` 为独立模块的设计资料；APK 的实际装配以 `app/android/app/src/main/` 为准。

## 运行与开发

需要 Flutter、Android SDK 和 Java 开发环境。最简本地构建：

```bash
git clone https://github.com/amwangfan/whale-mobile.git
cd whale-mobile
pip install -r requirements-test.txt
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
| 设备后台 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/` | Kotlin、Android Service、Lifecycle |
| 通信与缓存 | `app/android/.../transport/v2/` | V2 WSS、Ed25519、SQLite 加密离线队列 |
| 感知与索引 | `app/android/.../modules/capture/`、ScreenMemo 客户端 | Android Accessibility、ML Kit OCR、SQLite / FTS |
| 自动操作 | `app/android/.../modules/agent2/` | UI 树、无障碍操作、Root Shell |
| 来电与语音 | `app/android/.../modules/voip/`、`server/audio-bridge/` | Android Telecom、PCM/TCP |
| 界面通知与眼镜 | `app/android/.../modules/notification2/`、`app/android/.../modules/glasses/` | Android 通知、可插拔设备桥 |

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
- **鲸鱼专用 Root 手机**：提供 Root Shell、输入命令、文件、日志与录制能力，普通 UI 操作优先使用 Root；没有 Root 时使用已启用的无障碍服务。保留紧急停止。
- **执行状态**：以动作终态回执和设备反馈为准。超时/离线/未知结果与执行成功是不同状态，避免重复执行有副作用的操作。

## 后续计划

- 雷鸟 iO 真实镜片显示：眼镜桥接通道联调与真机验收
- 连续自主 APP 任务的端到端验收
- 通话语音后备链路的降级提示与话中工具审批联动
- 长期耗电基线与更多机型兼容性验证

## CI（按需运行）

[ci 工作流](.github/workflows/ci.yml) 使用手动 `workflow_dispatch`，在 **Actions → ci → Run workflow** 选择 `contracts`、`app`、`secret-scan` 或 `all`。

## 许可与致谢

屏幕采集与检索参考 [ScreenMemo](https://github.com/woijfwefw/ScreenMemo)（AGPL-3.0）项目；部分 VoIP 与前台监测代码源自 whale-io。引入第三方代码或素材时按其实际许可证处理。
