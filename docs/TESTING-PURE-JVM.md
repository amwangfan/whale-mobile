# 无 Android SDK 的纯 JVM 验证

`scripts/test_pure_jvm.py` 编译并运行不依赖 Android 的实际源码和现有 JUnit 测试，覆盖 V2 编解码、ID、序号、重连、分页、注册、观察选项、恢复策略、UI 事件、通知、VoIP 和眼镜逻辑。

入口不下载依赖、不启动手机模块、不修改系统 Java。需要显式提供本地 Kotlin 编译器依赖、JUnit 和项目 Gson jar；所有生成内容都写到 `--output`，运行与失败会覆盖旧报告状态，避免沿用上次绿色结果。

```text
python scripts/test_pure_jvm.py --tools "<Kotlin和JUnit目录>" --gson "<gson-2.11.0.jar>" --output "../logs/01-验证/mobile-jvm"
```

本机验证使用 Kotlin compiler-embeddable 1.9.24 及其完整依赖、JUnit 4.13.2 / Hamcrest 1.3、Gson 2.11.0。Java 通过 `--java` 指定，默认从 PATH 查找；纯逻辑编译目标为 JVM 1.8。

输出包括 `compile.log`、`tests.log` 和 `report.json`。2026-11-09 第19轮范围为 15 个测试类，120 项通过，新增严格JSON/整数边界、录制选项与连接代次策略。范围由脚本中的 SOURCES/TESTS 显式列出，未静默过滤失败用例；WssClientV2等依赖Android的实际组件不在此入口的编译范围。

这不能替代 Flutter/Gradle、Robolectric、APK 编译或真机测试。Android Service、Context、SQLite、权限、MediaProjection 和设备硬件仍需在项目要求的 JDK17/Flutter/Android SDK 环境中验证。
