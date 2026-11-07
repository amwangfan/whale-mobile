# Whale Mobile · 开发者与 AI 协作指南

> 面向修改本仓的开发者与编程 Agent。首次阅读从 [README](../README.md) 开始；当前协议和真机状态分别查看 [contracts/v2](../contracts/v2/) 与 [STATUS](STATUS.md)。

> 更新：2026-11-08。本文只描述当前 V2 代码导航；旧 V1 验收记录保留在 STATUS 的历史阶段中。

## 1. 产品定位与仓库边界

鲸鱼是能够感知、交流并行动的 AI 伴侣。mobile 为它提供 Android 身体和感官，不复制 Core 的人格、长期记忆和决策系统。

- **[whale-core](https://github.com/amwangfan/whale-core)**：人格、长期记忆、意图、任务决策、设备注册与多设备编排。
- **whale-mobile**：Android 感知、通知、通话、眼镜桥、UI 操作、Root Shell、录制和真实执行回执。
- **[whale-io](https://github.com/amwangfan/whale-io)**：QQ/WeQ 与历史桥接基础设施；手机端业务以本仓为准。

日常手机与专用 Root 手机是两个构建 flavor。能力以端侧实际注册与 Core scope 为准，不靠隐藏 UI 假装不存在。

## 2. 当前运行链路（V2）

```text
Flutter UI / Deep Links
       │ MethodChannel
       ▼
NodeRuntimeV2（Application 单例）── NodeServiceV2
       │
       ├─ DeviceIdentity / WssClientV2
       ├─ EventOutboxV2 / OfflineQueue / ReceiptDispatcherV2
       ├─ Agent2 / Notification2 / Recording
       └─ Capture / ScreenMemo / VoIP / Glasses
                  │ contracts/v2
                  ▼
       whale-core EmbeddedWssGateway
```

- 当前设备协议只有 `contracts/v2/`；canonical source 在本仓，Core 的同名目录是镜像。
- Core 管理 REST 仍使用 `/api/v1` 路径前缀；设备 WSS 使用 `/api/v2/devices/ws`。不要混淆 API 路径版本和设备协议版本。
- V1 设备协议、旧 WssClient/NodeRuntime 生产链路和 `server/dev_gateway.py` 已退役；不要继续新增 V1 兼容代码。

## 3. 代码地图

| 需求 | 当前路径 |
| --- | --- |
| Flutter 页面与聊天 | `app/lib/features/`、`app/lib/modules/` |
| 应用入口与深链 | `app/lib/main.dart` |
| 后台运行时 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/v2/` |
| WSS、身份、队列、回执 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/v2/` |
| daily/root 能力声明 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/CapabilityRegistry.kt` |
| UI / Shell 执行器 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/agent2/` |
| 原生通知 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/notification2/` |
| 屏幕录制 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/recording/` |
| ScreenMemo 与采集 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/capture/`、`app/lib/features/screenmemo/` |
| VoIP 宿主实现 | `app/android/app/src/main/java/com/wangfanlab/whale/voip/` |
| 眼镜桥 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/glasses/` |
| V2 协议与样例 | `contracts/v2/` |
| 真机记录 | `docs/STATUS.md`、`docs/SETUP-MAGIC8.md` |

`modules/voip/` 仍含旧独立 APK 的重复源码，清理工作跟踪在 #35；当前宿主运行时以 `app/android` 为准。

## 4. 实际能力边界

- daily flavor 不声明 Root Shell、文件写入、logcat、安装应用和录制等 root-only 能力。
- root flavor 在本机注册 Root 能力；Root 命令在目标 Android 设备执行，不转发到 Core 云主机。
- 端侧 WSS、身份、离线队列和在途回执由 Application 级 NodeRuntimeV2 持有；Flutter Activity 不拥有后台长连接。
- 执行成功、失败、超时、取消和未知结果按 V2 五终态表达；超时不自动重放可能产生副作用的动作。
- ScreenMemo、截图、录音和录屏按实际模块状态与系统授权工作；不要把 UI 开关当成真实权限已授予的证据。

## 5. 开发与验证

```bash
python -m pip install -r requirements-test.txt
python3 tests/contract_validate.py
python3 scripts/contract_sync_check.py --core ../whale-core --mobile .
cd app
flutter pub get
flutter test
flutter analyze
flutter build apk --debug
cd android
./gradlew :app:testDebugUnitTest
```

仓库 CI 采用手动 `workflow_dispatch`，可选择 `contracts`、`app`、`secret-scan` 或 `all`。没有实际运行记录时不要声称 CI 通过。模拟测试、跨仓契约检查和真机验收是不同证据。

## 6. 当前仍需完成的工作

- #35：清理 `modules/voip/` 中的旧独立 APK 重复源码，保留宿主唯一实现。
- #39：继续把 `main.dart` 收敛为应用装配与路由入口。
- #40：本机 flutter_tester 残留进程诊断工具。
- Root 手机 Bilibili 完整自主任务链由 Core #58 与本仓 Agent2 联合验收。
- 雷鸟 iO 的真实显示回执仍需硬件链路验收。

## 7. 协作方式

先读当前源码、测试、协议和 Issues。跨仓协议变更先改本仓 canonical schema 与示例，再同步 Core 镜像并运行双仓契约校验。按语义拆分本地 commit，最后每仓汇总一个 PR。交付时说明实际改动、测试结果、真机覆盖和剩余事项。
