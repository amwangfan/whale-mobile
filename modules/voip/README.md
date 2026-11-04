# modules/voip — Whale 自管 Telecom VoIP 来电模块

> **Legacy 独立形态**：本目录保持可独立构建的 APK；阶段二 B4 起生产形态为宿主内集成，
> 见 `app/android/app/src/main/java/com/wangfanlab/whale/voip/`（信令走 WSS call.\*，
> SignalService 长轮询在宿主内默认关闭）。

从 `whale-io/infra/call-bridge`（包名 `com.nova.fakecall`）迁移改造的独立可构建 Android
模块。纯 Java（无 Kotlin），AGP 8.11.1 / Gradle 8.14，minSdk 26 / targetSdk 34 / compileSdk 36，
JDK 17 源/目标兼容。

## 模块责任

- 自管 `ConnectionService`（`FakeConnectionService`）+ `PhoneAccount.CAPABILITY_SELF_MANAGED`：
  远端/本地触发 → `addNewIncomingCall` → 全屏来电 UI（`IncomingCallActivity` + FSI 通知
  `IncomingNotification`）→ 接听 → `InCallActivity` 通话页。
- 接通后 `AudioBridge` 建立 TCP PCM 双向音频桥（采 `AudioRecord` 16kHz s16le mono → 桥，
  桥 → `AudioTrack` 播放）。
- `SignalService` 前台服务 HTTP 长轮询信令，收 `ring`/`hangup` 事件触发 Telecom 来电/挂断；
  `BootReceiver` 开机/自升级后按开关恢复长轮询；`BootstrapActivity` 为设置/冒烟入口页。
- 责任边界：**本模块只含手机端 APK**。服务端音频桥/信令 hub（原仓 `audio-bridge/` Python）
  不在此迁移，归属 `whale-mobile/server/` 线路。

## 协议

### 信令（SignalService，HTTP 长轮询，Bearer 鉴权）

- 轮询：`GET {base}/v1/poll?device_id={id}&wait=50`，`Authorization: Bearer {token}`（可空）。
  非 `127.0.0.1/localhost` 的明文 `http://` 会被客户端拒绝（须 HTTPS/隧道）；默认地址
  `http://127.0.0.1:18799`（`SignalPrefs.DEFAULT_URL`）。
- 事件：新 hub `{ok, messages:[...]}`，旧 mock 单事件 `{cmd:...}`；`action=ring` 携带
  `number/name`，`hangup` 挂断；`timeout/hello/pong/keepalive` 为心跳。
  远端 `bridge_host/bridge_port` 一律忽略，不能覆盖或持久化本地端点（协议 §4）。
- 404 回退旧路径 `GET /wait?device={id}&timeout=55`（box mock）。

### 音频桥（AudioBridge，TCP，默认 127.0.0.1:18799）

- 帧格式：`[u32 BE length][u8 type][payload]`；`type 0` = PCM s16le mono 16kHz，
  `type 1` = 控制 UTF-8 文本。
- 先发 `AUTH {BRIDGE_TOKEN}`，再发 `HELLO nova sample=16000`；只有明确收到 `AUTH_OK`
  后才创建/启动录音与播放、发送 PCM。握手读超时 8 秒，最多 8 个控制帧、每帧 ≤4096 字节；
  兼容 `AUTH_OK` 在前以及 `HELLO` 在前的响应顺序，但 `HELLO` 本身不是鉴权成功。
  `AUTH_FAIL`、未知控制响应、错误帧、EOF/超时均在开麦前拒绝（走既有有界重试）。
  上行 20ms 帧，下行 PCM 直写 AudioTrack。断线指数退避重连（≤8 次）。
- **独立媒体凭据必填**：来自 `BridgePrefs` 的 app-private `nova_bridge/media_token`，
  与 `nova_signal/token`（`SIGNAL_TOKEN`）、WSS 设备身份完全分开。缺少/清除媒体 token 时，
  在建 socket / 开麦前安全拒绝；不读取、不迁移、不静默回退信令 token。
- 主宿主在模块总览的 VoIP 卡配置「PCM 桥地址 / 端口 / 独立媒体 BRIDGE_TOKEN」，填写 token
  后点保存。密码框不回显旧 token，留空保存保留旧值；「清除媒体凭据」显式清空。
  Legacy APK 在 Bootstrap 的「音频桥」对话框配置同样的本地凭据；留空保留，重置按钮同时清除。
  两个 APK 的私有存储互不共享，须分别配置。token 不含空白/控制字符，最长 4096 字符。
- 本地 `getVoipBridgeConfig` 仅返回 `host/port/tokenConfigured`；`setVoipBridgeConfig`
  可选 `token`，缺字段保留，空字符串清除。日志不包含凭据值或长度，旧值不返回 Flutter。
  已在通话中的连接不会因编辑自动重连/撤销，修改作用于下一次媒体启动；需要立即停止时先挂断。
- 媒体连接最终只读取本地 `BridgePrefs` 端点，调用参数/Intent extras 也不能改变凭据接收者。
  若升级前曾被旧信令覆盖地址，须在本地重新核对并保存可信端点；不自动信任或迁移历史远端值。
- 服务端仍使用其独立 `BRIDGE_TOKEN`。即使服务端允许 tokenless loopback 开发模式，客户端也
  不默认匿名：需显式设置一对开发媒体 token；两类 token 可且应不同。TCP 本身不加密，仍须使用
  可信本机反向隧道/受控私网，不能把 AUTH 直接暴露到公网；本次不改媒体传输或服务部署。

### 离线安全回归（不需要真机或真实服务）

在仓库根目录执行：

```bash
python3 -m unittest discover -s modules/voip/tests -v
cd app
/opt/flutter/bin/flutter test --no-pub test/voip_media_config_test.dart test/widget_test.dart
```

首项仅依赖 Python stdlib + JDK，用临时 Android fakes 编译两份真实 `BridgePrefs/AudioBridge`，
并原样编译 legacy ring handler；仅与测试进程内回环 mock TCP 桥交互。覆盖独立 token 持久化、
`SIGNAL_TOKEN != BRIDGE_TOKEN` 的实际 AUTH 帧、缺失/清除拒绝且不开麦、token 格式拒绝、
本地端点强制选择、AUTH_OK/HELLO 两种顺序、错误鉴权/HELLO-only/EOF 不开麦、
恶意远端 ring 不改变端点/凭据。Flutter 测试 mock MethodChannel，覆盖
读取仅含配置状态、端点编辑保留 token、显式替换/清除。它们不证明 Android 权限/Telecom/
音频硬件行为或服务端拒绝行为；本次没有真机、真实 QQ/服务、secrets 访问或部署推送。

### 本机广播 action（RingReceiver，**exported=false**）

`com.wangfanlab.whale.voip.ACTION_RING` / `ACTION_ANSWER` / `ACTION_HANGUP`（extras:
`number`、`name`、`bridge_host`、`bridge_port`）。仅 root（`su am broadcast`）与同进程
PendingIntent 可触发；生产入口是信令（后续 WSS action），非广播。

## 与原 call-bridge 的差异清单（仅以下为有意改动，其余逐文件 diff 为零语义变化）

1. 包名 `com.nova.fakecall` → `com.wangfanlab.whale.voip`（package 声明、Manifest、
   所有 action 字符串、Telecom 组件名、脚本内组件/action 名）。
2. Log/FileLog 标签 `NovaFakeCall` → `WhaleVoip`（各类 `TAG` 常量）。
3. `RingReceiver` → `android:exported="false"`；`scripts/{ring,answer,hangup,stop}.sh`
   改 `su -c 'am broadcast ...'` 并加头部说明。
4. Manifest 去掉 `android:debuggable="true"` 与全局 `usesCleartextTraffic="true"`；
   后者移入 `app/src/debug/AndroidManifest.xml`（仅 debug 构建放开明文 HTTP）。
5. 未迁移：`OutgoingCallReceiver.java`（disabled Phase-2 拨号拦截）、
   `docs/stubs/DialOneSketch.java.txt`（存根）、`secrets/`（不入仓）、`audio-bridge/`（服务端）。
   连带的最小修复：`FakeConnectionService` 内联 `normalize()`（方法体逐字来自被移除类），
   `CallAccountHelper.placeOutgoingAi` 删除对已移除类静态旗标的读写（3 行）。
6. 构建从手写 aapt2/d8 管线（旧 `scripts/build.sh`）改为 Gradle/AGP；`scripts/build.sh`
   变为 `./gradlew :app:assembleDebug` 薄封装。versionCode 10 / versionName `1.8.1-user`
   沿用原 Manifest 值。

### 有意保留不改的内部标识（不影响安全/协议）

SharedPreferences 文件名（`nova_bridge`/`nova_signal`/`nova_boot`）、通知 channel ID
（`nova_incoming_call`/`nova_signal`）、Telecom 账户 ID（`nova-fake-call`/`nova-ai-out`）、
wakelock 标签、日志文件名 `nova.log`、信令 UA `NovaFakeCall-Signal/1.6`、桥握手串
`HELLO nova ...`、UI 文案与 `app_name`（"Nova AI Call" 等产品文案留待 App 侧统一品牌决策）。
`docs/` 为原设计文档逐字迁移（已逐文件核查无真实 QQ/手机号/IP，均为 `<PUBLIC_IP>` 等
占位符），文中出现的旧包名属历史记录，不再回改。

## 构建

```bash
cd whale-mobile/modules/voip
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
  ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 缺省时写入 `sdk.dir=/opt/android-sdk`（已被 .gitignore 忽略）。
- Gradle wrapper 固定 `gradle-8.14-all.zip`；AGP 8.11.1；无第三方依赖，可离线构建
  （本机 wrapper/AGP 已在 Gradle 缓存时）。
- release 构建默认无 cleartext、无 debuggable；调试明文 HTTP 只存在于 debug variant。

## 实机测试

1. 安装与授权：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell pm grant com.wangfanlab.whale.voip android.permission.RECORD_AUDIO
   adb shell pm grant com.wangfanlab.whale.voip android.permission.CALL_PHONE
   adb shell pm grant com.wangfanlab.whale.voip android.permission.READ_PHONE_STATE
   adb shell pm grant com.wangfanlab.whale.voip android.permission.READ_PHONE_NUMBERS
   adb shell pm grant com.wangfanlab.whale.voip android.permission.POST_NOTIFICATIONS
   ```

2. 桥联通：开发期 `adb reverse tcp:18799 tcp:18799`（手机默认桥 `127.0.0.1:18799`），
   笔记本/服务器跑兼容的 PCM 桥（原仓 `audio-bridge/`，协议见上）。
3. 无 root 冒烟：打开 App（Nova AI Call）→ 「拨打 AI」或「模拟来电（响铃接听）」；
   或 `adb shell am start -n com.wangfanlab.whale.voip/.BootstrapActivity --es auto call_ai`。
4. root 冒烟（广播路径，测试脚本已改走 su）：

   ```bash
   sh scripts/ring.sh "***" "AI 助手" 127.0.0.1 18799   # 触发来电
   sh scripts/answer.sh && sh scripts/hangup.sh
   ```

5. 信令端到端：App「设置信令地址」填 hub 地址+token 并开「远端待命」，服务端
   `POST /v1/ring`（Bearer）→ 手机全屏来电。详见 `docs/remote-ring.md`（历史记录，
   包名以本文档为准）。

## 实机运维要点（nova / NetHunter chroot）

- ssh 进 nova 落在 Kali chroot，与 Android **挂载命名空间隔离**：`/data/data/...` 的写入对 Android 应用不可见。改应用数据必须 `nsenter -t 1 -m sh -c '...'`。
- `am`/`pm`/`logcat` 需 `export PATH=/system/bin:$PATH`；uiautomator 在 chroot 内会 abort，UI 自动化请用 adb（网络 adb 需预置 adb_keys + 重启 adbd 两次）或系统侧 input。
- nova 会休眠掉线（tailnet rx=0），测试前先确认在线。

## nova 实测记录（2026-10-31）

- 来电→接听→PCM 双向→挂断全链通过：echo 桥 85.2s 会话，rx/tx 各 2.7MB（≈32KB/s=16kHz s16le 满速）；挂断后麦克风释放、桥连接关闭。
- 鉴权：BRIDGE_TOKEN 经 SharedPreferences 下发（root 写入须 nsenter），AUTH 首帧校验通过。
