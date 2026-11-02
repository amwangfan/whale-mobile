# Whale Mobile 架构（V1 目标态）

> 2026-10-31 · 对应 ROADMAP 阶段一/二。决策记录见 `/opt/whale/docs/whale-mobile-migration-plan.md`（工作区文档）。

## 1. 定位与边界

Whale Mobile = 鲸鱼的手机端身体与感官。whale-core = 大脑（意图、人格、记忆、权限、主动决策）。
两侧只经 `contracts/v1` 版本化协议通信；core 不依赖 Android API，mobile 不复制 core 决策系统。

## 2. 进程与模块

```
┌─────────────────────────── Android 设备 ───────────────────────────┐
│ Whale Mobile APK (com.wangfanlab.whale)                            │
│  ┌ Flutter UI（app/）：配对/模块开关/权限向导/状态页                │
│  │    ↑ TransportBridge 仅为 UI 客户端（BG-01；Engine 可随意重建）   │
│  ├ runtime/  NodeRuntime（Application 单例：WSS/身份/队列/执行器）   │
│  │           NodeService（dataSync 前台服务，常驻通知可一键停止）     │
│  ├ ModuleRegistry：installed→permission_required→disabled/enabled │
│  │    →starting→running→degraded/error→stopped                    │
│  ├ transport/    WSS 客户端 · DeviceIdentity(Keystore Ed25519)     │
│  │               离线队列（加密，至少一次+去重）                    │
│  ├ modules/screenmemo/    MCP 客户端→三能力适配                     │
│  ├ modules/notifications/ 通知发布/撤销 + 可选通知监听              │
│  ├ modules/voip/          Telecom 来电 + PCM 桥（call-bridge 改造） │
│  └ modules/glasses/       雷鸟 iO HUD 桥（既有方案，不刷固件）       │
│  ┌ ScreenMemo 独立 APK（阶段一保留）                                │
│  │    采集=AccessibilityService.takeScreenshot（API 30+）          │
│  │    OCR=ML Kit 本地 · SQLite 分库 · MCP 服务（加固后绑回环）      │
└────────────────────────────────────────────────────────────────────┘
        │ Control: WSS（双向认证，Ed25519 设备身份）   │ Media: 独立 PCM/Realtime 桥
┌───────┴────────────────────────────────────────────────────────────┐
│ N100 / whale-core                                                   │
│  devices/（新增：registry+pairing+WSS 端点）→ perception/ 事件入口   │
│  会话身份：OwnerChannelRegistry→ConversationContextResolver（S1）    │
│  授权：scope 矩阵 + temporary_auth 租约 + 审批；记忆：spaces/fork    │
│  server/audio-bridge：Omni Realtime / ASR→LLM→TTS 后备              │
└────────────────────────────────────────────────────────────────────┘
```

## 3. 关键设计决定（记录）

| # | 决定 | 理由 |
| --- | --- | --- |
| D1 | 全新仓库不带 whale-io 历史 | whale-io 历史含真实 PII（用户决策 S2） |
| D2 | Flutter UI + Kotlin 原生服务 | 复用 ScreenMemo 技术栈，阶段二合并阻力最小 |
| D3 | WSS 控制通道一步到位 | 用户决策 S5；core 侧新建 devices 端点 |
| D4 | ScreenMemo 阶段一保持独立 APK + 加固 fork | 用户决策 S4；MCP 进程间隔离无法律传染 |
| D5 | 截屏沿用 AccessibilityService.takeScreenshot | 不破坏 ScreenMemo 能力；API 30+ 由测试机 Leaf5（Android 13）覆盖 |
| D6 | 包名 com.wangfanlab.whale | 用户决策 S6；与 wangfanlab.com 域一致 |
| D7 | 端侧能力白名单 + 双边校验 | ROADMAP 原则；不执行任意云端 Shell/Intent |
| D8 | 原始屏幕数据默认不出设备 | 隐私基线；MCP 加固后绑回环 |
| D9 | 运行时归 Application 单例 + dataSync FGS 载体（BG-01） | UI 销毁不断连；Android 8+ 合规启动；Android 15 dataSync 限时内不绕过 |
| D9 | 传输拓扑：tailnet 内 ws://（WireGuard 加密）为联调态；wss://（tailscale serve 终结 TLS，真 LE 证书）为生产态 | N100 已配 `tailscale serve --https 8443 → 127.0.0.1:8765`；root 测试机不改系统 hosts（dm-verity 风险），生产非 root 机 Tailscale App 自带 MagicDNS 可解析 ts.net 域名 |

## 4. 模块责任/协议/依赖/验收（阶段一）

| 模块 | 责任 | 协议 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| transport | WSS、身份、心跳、离线队列 | contracts/v1 | Keystore | 断网恢复无重复执行；撤销即拒 |
| screenmemo 适配器 | MCP→screen.context/search/evidence | MCP JSON-RPC（回环）→ contracts | ScreenMemo 加固版 | 伪造 evidence_ref 被拒；越权 fail-closed |
| notifications | 通知发布/更新/撤销+回执 | action.request/receipt | Android 13+ 通知权限向导 | 重复 action_id 不重复发 |
| voip | 来电/接听/挂断+PCM 桥 | call.* + 媒体帧 | Telecom/MIC 权限 | nova 实机闭环；挂断释放麦克风 |
| app 宿主 | 模块状态机、权限向导、一键暂停 | 内部 | — | 关闭截屏模块即无截屏 |

## 5. 测试设备矩阵

| 设备 | 系统 | 角色 |
| --- | --- | --- |
| Onyx Leaf5（leaf5, 100.64.85.85） | Android 13 / API 33，Magisk root | 屏幕采集/OCR/MCP 全链路验收（adb+ssh 已通）。**无 telecom 服务（墨水屏阅读器无电话栈），不能验收 VoIP** |
| nova（100.64.110.11） | Android 9 / API 28 GSI，root | VoIP/传输/通知路径；不验收截屏（API<30）。注意会休眠掉线 |
| 正式机 | 荣耀 Magic8 Pro（MagicOS，Android 15+ 底）非 Root | Gate A/B 最终验收；tailnet 中可见 magic8-pro 节点（当前离线） |

**泛机型兼容目标**：多数手机可用 → minSdk 26 保底、能力检测优先（不假设后台常驻/厂商通道）、厂商差异（荣耀 MagicOS 的后台管理/自启动白名单）在权限向导中显式引导而非承诺。
