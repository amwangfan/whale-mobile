# Magic8 Pro 非 Root 使用说明（v1.7-user）

## 包信息
- APK：`<本机构建产物>/nova-fakecall-user.apk`（亦 `build/nova-fakecall.apk`，APK 不入库）
- 包名：`com.nova.fakecall`
- versionName：`1.7-user` / versionCode：`8`
- targetSdk：35

## 开发期网络（USB）
笔记本须能访问 box `:18799`（Tailscale），并本地转发：

```powershell
# 1) 笔记本 TCP 中继 → box
python nova_bridge_relay.py   # listen 127.0.0.1:18799 → <TAILSCALE_IP>:18799

# 2) 手机 reverse
adb -s <serial> reverse tcp:18799 tcp:18799
```

App 默认桥：`127.0.0.1:18799`（可在 App「设置桥地址」改 Tailscale IP）。

## 安装 / 权限
```powershell
adb -s <serial> install -r nova-fakecall-user.apk
adb -s <serial> shell pm grant com.nova.fakecall android.permission.RECORD_AUDIO
adb -s <serial> shell pm grant com.nova.fakecall android.permission.CALL_PHONE
adb -s <serial> shell pm grant com.nova.fakecall android.permission.READ_PHONE_STATE
adb -s <serial> shell pm grant com.nova.fakecall android.permission.READ_PHONE_NUMBERS
adb -s <serial> shell pm grant com.nova.fakecall android.permission.POST_NOTIFICATIONS
```

## 用户测试步骤
1. 打开 **Nova AI Call**
2. 确认状态栏桥地址为 `127.0.0.1:18799`（或你的 Tailscale）
3. **拨打 AI** → 进入通话页，听 Tina/Nova 问候，对着说话
4. 或 **模拟来电（响铃接听）** → 全屏来电 → 接听 → 同上
5. 通话页可点 **扬声器** / **挂断**
6. 主页也可单独切扬声器

ADB 自动拨出（调试）：
```powershell
adb -s <serial> shell am start -n com.nova.fakecall/.BootstrapActivity --es auto call_ai
```

## 不需要
- Root / SSH / ring.sh
- 设置里启用「呼叫账户」（自管路径）
- 拨号盘拨 1（Phase 2）

## 可选设置
- 若想用系统通话 UI：设置 → 通话 → 呼叫账户 → 启用 **Nova AI**（CallProvider）
- 全屏来电被拦时：设置 → 应用 → Nova AI Call → 通知 / 全屏意图

## Box 桥
```bash
# 已在跑则保持：BRIDGE_MODE=omni BRIDGE_TOOLS=1 @ 0.0.0.0:18799
./audio-bridge/start.sh
```

## 公网桥（国内阿里云，推荐随身）

公网音频桥已部署（omni + tools），**无需 USB reverse** 也能通话：

| 项 | 值 |
|----|----|
| 公网 IP | `<PUBLIC_IP>` |
| 音频桥 TCP | **`<PUBLIC_IP>:18799`**（已测公网可达） |
| 远端响铃信令 | 公网走 **`<PUBLIC_IP>:18799`** mux（需 `SIGNAL_TOKEN`）；直连 18800 需安全组放行 |
| 模式 | `BRIDGE_MODE=omni` + `BRIDGE_TOOLS=1` |
| systemd | `nova-audio-bridge` / `nova-ring-hub` |
| 服务器目录 | `/opt/nova-fake-call/audio-bridge` |
| 密钥环境 | `/opt/nova-fake-call/audio-bridge.env`（600，勿打印） |

### Magic8 怎么改桥地址
1. 打开 App → **设置桥地址**（或主页状态栏附近的设置）
2. Host 填：`<PUBLIC_IP>`，Port：`18799`
3. 保存后拨打 AI / 模拟来电即可走公网
4. **USB 开发回退仍可用**：把 Host 改回 `127.0.0.1`，并保持 `adb reverse tcp:18799 tcp:18799`

（APK 默认仍是 `127.0.0.1:18799`，不破坏 USB 路径。）

### 远端触发来电（服务器已就绪；APK 需连信令）
手机需保持 `GET /v1/poll?device_id=magic8&wait=50` 长轮询，或 `WS /ws?device_id=magic8`（另一代理可改 APK）。

在服务器本机（或安全组放行后公网）：

```bash
TOKEN=$(grep ^SIGNAL_TOKEN  /opt/nova-fake-call/audio-bridge.env | cut -d= -f2-)
curl -G 'http://127.0.0.1:18800/v1/ring' \
  --data-urlencode "token=$TOKEN" \
  --data-urlencode 'device_id=magic8' \
  --data-urlencode 'number=***' \
  --data-urlencode 'name=NovaAI'
# 健康检查
curl -s http://127.0.0.1:18800/health
```

公网请走 **:18799**（mux）并带 Bearer/query token；勿依赖未放行的 18800。

### 运维
```bash
ssh -p <SSH_PORT> -i ~/.ssh/<ssh_key> root@<PUBLIC_IP>
systemctl status nova-audio-bridge nova-ring-hub
journalctl -u nova-audio-bridge -u nova-ring-hub -f
systemctl restart nova-audio-bridge nova-ring-hub
```

**阻塞**：阿里云控制台 → ECS 安全组 → 入方向添加 **TCP 18800**（音频 18799 已通；18800 目前公网超时）。


## 远端来电（v1.7 · 需 Token）

见 `docs/remote-ring.md`。最短命令（无 token 返回 401）：

```bash
TOKEN=$(cat secrets/signal_token)
curl -sS -X POST 'http://<PUBLIC_IP>:18799/v1/ring?device_id=magic8&number=***&name=AI助手'   -H "Authorization: Bearer ${TOKEN}"
```

手机打开 Nova AI Call，在「设置信令地址」填入同一 Token，并保持「远端待命」通知。
