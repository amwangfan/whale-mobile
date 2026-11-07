# Whale Mobile 架构（V2 当前实现）

> 更新：2026-11-08。V1 设计已由当前 V2 实现取代；旧阶段验收记录见 [STATUS](STATUS.md)。

## 1. 定位与协议

Whale Mobile 是鲸鱼的 Android 身体与感官；whale-core 负责人格、长期记忆、意图、任务决策和多设备编排。两侧通过本仓 `contracts/v2/` 通信；该目录是 canonical source，Core 仓的同名目录为镜像。

Core 的设备管理 REST 路径沿用全站 `/api/v1` 前缀；设备 WSS 路径为 `/api/v2/devices/ws`。协议版本以消息 envelope 和 `contracts/v2` 为准。

## 2. Android 运行时

```text
WhaleApp / Application
  └─ NodeRuntimeV2（单例）
       ├─ DeviceIdentity
       ├─ WssClientV2
       ├─ EventOutboxV2 / OfflineQueue
       ├─ ReceiptDispatcherV2
       ├─ Agent2 / Notification2 / Recording
       └─ NodeServiceV2（前台服务载体）

Flutter UI
  ├─ Chat / Conversations / SSE
  ├─ ScreenMemo timeline / search / saved items
  ├─ Notification inbox
  └─ Device pairing / modules / diagnostics
```

长连接、设备身份、上行队列和在途回执归 Application 级 NodeRuntimeV2；Flutter Activity 仅提供 UI 和 MethodChannel，不拥有后台连接。

## 3. 双 APK 能力模型

同一 Android 工程提供 `daily` 与 `root` 两个 flavor：

- daily 只声明两版共有的观察、UI、通知、通话和眼镜能力。
- root 额外声明 Shell、文件、logcat、应用安装/卸载和屏幕录制能力。
- `CapabilityRegistry` 决定 hello 实际声明的能力；Core 再按设备配对 scope 收窄 accepted_capabilities。

设备 flavor 是设备形态，不自动授予 scope；Root 手机持续授权在端侧开关和 Core scope 两侧共同生效。

## 4. 主要模块

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 传输与回执 | `transport/v2/` | 握手、心跳、队列、序号、回执与幂等 |
| 后台服务 | `runtime/v2/` | 连接生命周期、前台服务、启动恢复 |
| Agent2 | `modules/agent2/` | observe、点击、滑动、输入、Shell、文件与停止 |
| Notification2 | `modules/notification2/` | 原生通知、按钮动作、撤回与展示回执 |
| Recording | `modules/recording/` | Root flavor MediaProjection 录制 |
| Capture | `modules/capture/` | 屏幕采集、OCR、事件与 ScreenMemo 整合 |
| VoIP | `app/android/app/src/main/java/com/wangfanlab/whale/voip/` | Telecom 来电与 PCM 音频桥 |
| Glasses | `modules/glasses/` | 雷鸟 iO 通知/HUD 适配 |

旧独立 `modules/voip/` 工程与重复 Java 源码已删除，只保留历史设计资料；宿主 VoIP 是唯一实现。

## 5. 传输与回执

- WSS 使用设备身份与 challenge-response 握手；所有消息通过 V2 schema 校验。
- 离线事件队列加密保存；事件上行按 event_id 幂等，回执按 action_id 关联。
- 动作终态为 succeeded、failed、timeout、cancelled、unknown；收到请求不代表动作完成。
- 断线恢复和系统后台限制按 Android 平台行为处理；不通过隐藏的后台常驻或绕过系统策略伪造在线状态。

## 6. 服务端组件

本仓 `server/` 仅保留音频桥服务端。设备 WSS/REST 与动作编排归 whale-core。旧 V1 `dev_gateway`、其测试和启动脚本已移除，不能再作为默认设备对端。

## 7. 验证入口

```bash
python -m pip install -r requirements-test.txt
python3 tests/contract_validate.py
python3 scripts/contract_sync_check.py --core ../whale-core --mobile .
cd app
flutter test
flutter analyze
flutter build apk --debug --flavor daily
flutter build apk --debug --flavor root
cd android && ./gradlew :app:testDailyDebugUnitTest :app:testRootDebugUnitTest
```

CI 只在 GitHub Actions 手动触发。真机结果、机型差异和未完成验收见 [STATUS](STATUS.md)。
