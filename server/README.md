# Server-side components

设备配对、设备 WSS、动作派发与通知投递由
[whale-core](https://github.com/amwangfan/whale-core) 的设备运行时提供。
旧 V1 `dev_gateway` 是迁移期参考实现；设备协议已切换为 V2，相关服务、
测试和启动脚本已从本仓移除，不能再作为手机端的默认对端。

本目录目前只保留服务端音频桥：

- [audio-bridge](audio-bridge/)：Realtime / ASR / LLM / TTS 音频链路。
- 音频桥依赖、启动和配置以其目录内的 README 与 requirements 为准。

Android 端的 WSS 客户端、VoIP 客户端和设备执行器位于
`app/android/app/src/main/`。
