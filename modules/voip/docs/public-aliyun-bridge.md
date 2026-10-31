# 公网音频桥（阿里云）

- **音频**：`<PUBLIC_IP>:18799`（TCP PCM，omni+tools）— 公网已通
- **信令**：经 **18799** mux 转发到机上 `127.0.0.1:18800`（`POST/GET /v1/ring`、`GET /v1/poll`、`WS /ws`）。直连 18800 需安全组放行（当前未开）
- **鉴权**：固定 `SIGNAL_TOKEN`（`/opt/nova-fake-call/audio-bridge.env`，mode 600）。无 token → 401。详见 `docs/remote-ring.md`
- Magic8：App 设置桥地址 → `<PUBLIC_IP>` / `18799`；「设置信令地址」填同一 Token（USB 回退：`127.0.0.1` + adb reverse）
- systemd：`nova-audio-bridge`、`nova-ring-hub` @ `/opt/nova-fake-call`
- 触发响铃：

```bash
TOKEN=$(cat secrets/signal_token)
curl -sS -X POST 'http://<PUBLIC_IP>:18799/v1/ring?device_id=magic8&number=***&name=NovaAI' \
  -H "Authorization: Bearer ${TOKEN}"
```
