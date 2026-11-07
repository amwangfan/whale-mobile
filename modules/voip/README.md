# VoIP 设计资料（历史目录）

当前唯一的 Android VoIP 实现位于 [app/android/app/src/main/java/com/wangfanlab/whale/voip/](../../app/android/app/src/main/java/com/wangfanlab/whale/voip/)，由宿主 APK 的 Kotlin `VoipModule` 接线。来电信令使用 V2 WSS；PCM 音频桥服务端仍在 `server/audio-bridge/`。

旧的独立 APK、重复 Java 源码、Gradle wrapper 和调试脚本已删除，避免维护两套行为不一致的通话组件。媒体凭据、AUTH_OK 握手与本地端点防覆盖仍由宿主的 `BridgePrefs`、`AudioBridge`、`SignalService` 实现；回归测试见 `tests/test_voip_media_security.py`。

[docs/](docs/) 保留早期 Telecom、来电样式及部署研究记录，供查阅设计思路；其中旧包名、独立构建命令和脚本不再适用于当前版本。
