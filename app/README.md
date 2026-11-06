# Whale Mobile · Flutter / Android App

这里是鲸鱼的 Android 应用宿主。仓库总体介绍、设备能力和三仓分工请读 [根 README](../README.md)，代码导航和编程 Agent 的协作约定见 [AI 开发指南](../docs/AI_DEVELOPMENT_GUIDE.md)。

## 开发

```bash
cd app
flutter pub get
flutter test
flutter build apk --debug
```

Flutter 页面在 `lib/`；设备连接和原生方法通道在 `lib/transport/transport_client.dart`。Android 常驻运行时、传输层与功能执行器在 `android/app/src/main/kotlin/com/wangfanlab/whale/`。

核心链路是 `NodeRuntime` + `NodeService` 管理连接与后台任务，Flutter UI 只显示状态和提供配置入口。不要将长连接重新绑定到 Activity 生命周期。

设备自主体的原生入口在 `modules/agent/`，包括 Root Shell、UI 观察/控制与紧急停止；日常手机和专用 Root 手机通过不同模块授权使用同一套协议。

端云 JSON Schema 位于仓库根 `contracts/v1/`。Android 真机运行记录和使用步骤见 `../docs/STATUS.md` 与 `../docs/SETUP-MAGIC8.md`。
