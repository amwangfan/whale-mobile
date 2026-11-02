# 远端来电（Magic8 非 Root · v1.7-user）

> **兼容性提醒（P0 安全变更）**：原文是旧版 HTTP 运维记录，不可直接用于新 APK。新客户端拒绝携带 Token 的远程明文 HTTP；请通过 HTTPS 反向代理 / 私有隧道升级信令。新音频桥若设置 BRIDGE_TOKEN，手机音频连接需要先认证。保留下面记录只作为历史排障参考。

## 一键响铃（需鉴权）

公网入口已启用固定 `SIGNAL_TOKEN`。**无 token 的请求返回 401**；空 token 也会被拒。

阿里云公网（信令与音频同端口，HTTP/TCP 复用）：

```bash
TOKEN=$(cat secrets/signal_token)
# 或服务器上：TOKEN=$(grep ^SIGNAL_TOKEN  /opt/nova-fake-call/audio-bridge.env | cut -d= -f2-)

curl -sS -X POST "http://<PUBLIC_IP>:18799/v1/ring?device_id=magic8&number=***&name=AI助手&token=${TOKEN}"

# 推荐：Bearer（token 不进 URL/日志）
curl -sS -X POST 'http://<PUBLIC_IP>:18799/v1/ring?device_id=magic8&number=***&name=AI助手' \
  -H "Authorization: Bearer ${TOKEN}"

# 或 JSON body 带 token
curl -sS -X POST 'http://<PUBLIC_IP>:18799/v1/ring' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"magic8","number":"***","name":"AI助手"}'
```

或从笔记本 SSH 到机上 hub：

```bash
ssh -i ~/.ssh/<ssh_key> -p <SSH_PORT> root@<PUBLIC_IP> \
  'TOKEN=$(grep ^SIGNAL_TOKEN  /opt/nova-fake-call/audio-bridge.env | cut -d= -f2-);
   curl -sS -X POST "http://127.0.0.1:18800/v1/ring?device_id=magic8&number=***&name=AI助手" \
     -H "Authorization: Bearer ${TOKEN}"'
```

手机须：**Nova AI Call 已打开**（或通知栏「Nova 远端待命」在），「设置信令地址」里填好与服务器一致的 **Token**，且网络能访问 `<PUBLIC_IP>:18799`。

## 端口

| 用途 | 地址 | 说明 |
|------|------|------|
| 公网信令+音频入口 | **<PUBLIC_IP>:18799** | `http_tcp_mux`：HTTP→ring hub，其它 TCP→Omni 桥 |
| 机上 ring hub | 127.0.0.1:18800 | 长轮询 `/v1/poll`、触发 `/v1/ring` |
| 机上音频桥 | 127.0.0.1:18798 | Omni；经 mux 对外仍是 :18799 |
| SSH | <PUBLIC_IP>:<SSH_PORT> | 运维 |

> 安全组未放行 **18800**；故信令走已开放的 **18799**（与音频复用）。

## App

- 包：`com.nova.fakecall` **1.7-user**（versionCode 8）
- 默认信令：`http://<PUBLIC_IP>:18799`
- 设备 id：`magic8`
- Token：App「设置信令地址」→ Token 字段（`SignalPrefs`）；长轮询会自动附带
- 前台服务长轮询 `GET /v1/poll?device_id=magic8&wait=50`（带 token）
- 收到 `action=ring` → `addNewIncomingCall` → `IncomingCallActivity`

Token 文件（仅运维机）：`secrets/signal_token`（mode 600）。**勿把原始 token 写进文档或聊天。**

## 限制

1. **App/待命服务须在跑**（杀进程后不会响；开机可自启但荣耀可能拦）
2. **建议忽略电池优化**、允许通知/全屏意图
3. Tailscale 节点 `magic8-pro` 当前常 offline；公网 :18799 即可，不必开 TS
4. 若安全组以后放行 18800，可把信令改回 `http://<PUBLIC_IP>:18800`（App「设置信令地址」）
5. 生产已启用 `SIGNAL_TOKEN`；公网无鉴权 POST 应 401

## 服务端进程（Aliyun）

```bash
ss -tlnp | grep -E '18798|18799|18800'
# mux / hub / bridge 日志：
# /opt/nova-fake-call/mux.log
# /opt/nova-fake-call/ring_hub.log
# /opt/nova-fake-call/audio-bridge-18798.log
bash /opt/nova-fake-call/setup_mux.sh   # 重建 mux+桥布局
systemctl restart nova-ring-hub         # 改 SIGNAL_TOKEN 后
journalctl -u nova-ring-hub -n 20       # 应见 token=set
```
