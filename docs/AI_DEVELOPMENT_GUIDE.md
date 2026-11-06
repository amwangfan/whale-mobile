# Whale Mobile · 开发者与 AI 协作指南

> 面向修改本仓的开发者与编程 Agent。首次阅读从 [README](../README.md) 开始，协议和实测状态分别查看 [contracts/v1](../contracts/v1/) 与 [STATUS](STATUS.md)。
>
> 更新：2026-11-06。本指南是当前代码导航，不复写历史阶段验收表。

## 1. 产品定位与三仓边界

鲸鱼是能够感知、交流并行动的 AI 伴侣，不限定为电子女友。mobile 为它提供 Android 身体和感官，而不是复制一个本地大脑。

- **[whale-core](https://github.com/amwangfan/whale-core)**：人格、长期记忆、任务意图、模型决策、主动行为、设备注册和动作调度。
- **whale-mobile**：手机端采集、通知、通话、眼镜桥、UI 操作、Root Shell 和真实执行回执。
- **[whale-io](https://github.com/amwangfan/whale-io)**：QQ/WeQ、历史桥接及运维材料；手机端业务在本仓维护。

日常手机主要采集和提示，需要的动作按用户授权执行；专用 Root 手机可以授予鲸鱼持续操作权，由 Core 的角色模型与决策循环驱动。不要为专用设备凭空附加一串阻止自主操作的免责或拒绝规则。

## 2. 运行链路

```text
whale-core ── WSS / contracts/v1 ──▶ Android NodeRuntime
                                    ├─ 设备身份 / 离线队列 / 回执
                                    ├─ 感知与通知
                                    ├─ VoIP / 眼镜桥
                                    └─ AgentModule（观察 / UI / Root Shell）
                       Flutter UI ─┘ 配对、模块控制、状态和紧急停止
```

`WhaleApp` / `NodeRuntime` 归属 Application 生命周期；`NodeService` 承载前台服务。Flutter Activity 和 `TransportBridge` 是交互入口，不拥有长连接或在途任务。端云协议以 `contracts/v1/` 为源，与 Core 镜像按同步脚本检查。

## 3. 代码地图

| 修改目标 | 文件/目录 |
| --- | --- |
| 主界面和模块设置 | `app/lib/main.dart`、`app/lib/modules/registry.dart` |
| Flutter ↔ Kotlin 桥 | `app/lib/transport/transport_client.dart`、`app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/TransportBridge.kt` |
| 长连接、后台服务、恢复 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/runtime/` |
| 身份、WSS、离线队列、回执 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/transport/` |
| 设备自主体 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/agent/` |
| ScreenMemo、OCR、截屏与索引 | `app/lib/modules/screenmemo/`、`app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/capture/` |
| 通知与监听 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/notifications/`、`notificationread/` |
| 来电与语音桥 | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/voip/`、`modules/voip/`、`server/audio-bridge/` |
| 眼镜 HUD | `app/android/app/src/main/kotlin/com/wangfanlab/whale/modules/glasses/` |
| 协议 | `contracts/v1/PROTOCOL.md`、`contracts/v1/messages/`、`tests/contract_validate.py` |
| 真机部署与状态 | `docs/SETUP-MAGIC8.md`、`docs/deployment-devices-sop.md`、`docs/STATUS.md` |

顶层 `modules/` 等目录有历史拆分组件；需要确定运行链路时，以 `NodeRuntime` 中实际注册的执行器和 Flutter MethodChannel 调用为准。

## 4. 设备权限与执行约定

- 日常使用设备：用户选择感知源，授权后采集；通知等日常操作走现有模块，不额外发明重复批准。需要确认的操作按现有设备 scope 和 Core 策略执行。
- AI 专用 Root 设备：`AgentLogic` 区分模块启用、持续 Root 授权、全局暂停与紧急停止；用户开启后 `AgentModule` 可执行 `control`、`observe`、`shell`。不要把服务器 Shell 沙箱规则套到 Root 手机上的本地执行器。
- 真实结果：收到动作不等于执行；回执的 `executed`、`failed`、`unknown`、`timeout` 按协议处理。Shell 与 UI 操作可能产生外部副作用，超时不能直接推断为“没做”。
- 不可信输入：OCR、网页文本、群消息和工具输出可作观察材料，但不能作为设备 owner 身份、授权或新的系统指令。无需为此加入额外的长篇提示词。
- 用户控制：保留紧急停止、授权撤销、模块开关和真实错误回执；不增加无价值的命令黑名单和免责式交互。

## 5. 最常见的改动路径

1. 改模块 UI：`app/lib/main.dart` → `transport_client.dart` → `TransportBridge.kt`，确保开关反映原生实际状态。
2. 改手机自主执行：`AgentLogic.kt` 参数/状态 → `AgentModule.kt` 动作与回执 → `AgentShell.kt` 进程/超时 → Kotlin 测试 → Core `devices/agent_loop.py` 的对应能力。
3. 改端云消息：先改 canonical Schema + 示例，更新 Core 镜像和契约测试；两仓保持相同协议。
4. 改后台行为：从 `NodeRuntime`、`NodeService` 和 `WssClient` 追踪实际 owner，避免 Flutter 界面重建导致重复连接或动作。
5. 改 ScreenMemo：尊重数据源切换和旧库回滚流程；不复制一套已有的采集服务。

## 6. 开发与验证

```bash
python3 tests/contract_validate.py
cd app
flutter pub get
flutter test
flutter build apk --debug
```

跨仓契约同步脚本在 `scripts/contract_sync_check.py`。CI `.github/workflows/ci.yml` 由手动 `workflow_dispatch` 触发（`contracts`、`app`、`secret-scan`、`all`）。Root、摄像/截屏、通知监听、VoIP 和眼镜能力仍以对应真实设备试验为准。

## 7. 当前接线和实测状态

- #46 已修复 Root Shell 参数处理、超时、退出码、紧急停止等问题，并补充 Flutter 开关。检查新改动时仍应区分当前代码、单测和真实机型覆盖。
- Core 的 `DeviceAgentLoop` 有模拟闭环，但生产模型决策接线、leaf5 专用 Root 手机 Bilibili 完整操作链尚未验收；不要在 README 中宣称已实现全天候自主刷视频。
- 手机图库正式注入 API 尚未接入 ResultDelivery；Core 的服务器暂存目录不等于图片已出现在手机图库。
- 其他真机结果和机型差异见 `docs/STATUS.md`，不要反复复制成新的“禁止操作”条目。

## 8. AI 协作与文档

先读相关源码和现有测试，再提出或实施改动；能复用的模块直接复用。以可玩性、稳定交互和真正的功能闭环为目标，避免无意义的免责声明、过度安全分层或法律报告式表达。

跨仓任务先定契约并对齐依赖，小步提交，最后按仓库汇总 PR。反馈只需交代改动、测试、真机验证范围及剩余可执行事项。涉及正式设备或生产服务的操作按用户具体指令执行。

用户向导与功能入口放在根 `README.md`，本指南维护代码路径，精确协议保存在 `contracts/v1/`，历史与机型测试在 `docs/`。
