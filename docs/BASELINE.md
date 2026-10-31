# 构建与实机基线（实测记录）

> 规则：只记实测结果，不预设达标数据。日期 2026-10-31，构建机 N100（Debian 13，4C/16G）。

## 1. 工具链

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Flutter | 3.47.9 stable（Dart 3.13.7） | /opt/flutter |
| Android SDK | platforms 34/36，build-tools 34.0.0/36.0.0 | /opt/android-sdk |
| JDK | OpenJDK 21.0.13 | 系统 |
| 测试机 leaf5 | Onyx Leaf5，Android 13（API 33），Magisk root | ssh `leaf5` + adb `100.64.85.85:5555`（已通） |
| 测试机 nova | Android 9（API 28）GSI，root | ssh `nova`；不适用截屏路径（API<30） |

## 2. 构建基线

| 目标 | 结果 | 说明 |
| --- | --- | --- |
| whale-mobile 骨架（app/） | ✅ debug APK 150,478,272 B | Flutter 3.47.9 一次通过 |
| whale-io fg-monitor | ✅ root/user 双 debug APK（各 ~12.3 MB） | 需补 debug.keystore（已生成）；JDK 21 可用 |
| whale-io call-bridge | 未复测 | 手编 build.sh，迁移时 Gradle 化（WM-06）一并验证 |
| ScreenMemo @2f57da1 | ✅ debug APK 构建通过 | 需 Gradle 8.14（wrapper 已改）+ `--android-skip-build-dependency-validation` + Dart null 修复 1 处（whale/hardening 分支 7862d4a） |

## 3. 实机验证基线

| 用例 | 设备 | 结果 |
| --- | --- | --- |
| whale-mobile 骨架 APK adb 安装 | leaf5 | ✅ `package:com.wangfanlab.whale` 已安装 |
| ScreenMemo debug APK adb 安装 | leaf5 | ✅ `package:com.fqyw.screen_memo` 已安装（采集需手工开无障碍服务，MCP 默认关） |
| N100↔leaf5 网络 | tailnet | ✅ ssh(2222)/adb(5555) 均通 |
| N100↔nova 网络 | tailnet | ✅ ssh 通 |

## 4. 契约基线

- contracts/v1：14 份 schema + 13 黄金示例；`python3 tests/contract_validate.py` = 16/16 通过（含 3 负例）。
- 冻结前的待确认项：见 PROTOCOL.md 与各 schema description 中标注「约定」的取值（signature 编码、call.state 枚举等）。

## 5. 性能基线

实测（2026-10-31，leaf5 ↔ N100 经 tailnet；debug 包）：

| 指标 | 实测 | 方法 |
| --- | --- | --- |
| sensor.query 端到端时延（context.read） | P50=308ms，P95=423ms（n=10，min 290 / max 552） | gateway CLI 计时 |
| sensor.query 端到端时延（history.search） | 320–483ms（n=10） | 同上 |
| 断网恢复 | WiFi 关闭 40s → 恢复后自动重连；队列补发 2 条均被服务端幂等去重 | svc wifi 定时恢复法 |
| 强杀恢复 | force-stop → gateway 正确报 device_offline；冷启动自动回连（上次在线时） | adb + 实机 |
| 内存（debug 包，偏大） | Whale PSS≈295MB；ScreenMemo≈386MB | dumpsys meminfo（release 包预期显著更低，Gate A 复测） |
| 存储 | ScreenMemo 数据 26MB（采集 1h+）；Whale 数据 59MB（含 debug 引擎缓存） | du |
| 耗电 | 未测（需 24h 窗口，Gate A 安排） | — |

## 6. 兼容性补充（2026-10-31 晚）

| 设备 | 结果 |
| --- | --- |
| nova（Android 9 / API 28，minSdk 底线） | Whale 宿主安装/启动/身份初始化正常；StrongBox 缺失自动降级普通 Keystore（设计路径）；无崩溃 |
| leaf5（Android 13 / API 33） | 全功能 |
