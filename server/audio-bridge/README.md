# Nova 音频桥（Tailscale PCM + 语音 Agent）

VoIP 来电接通后，手机麦克风 PCM 经 Tailscale TCP 传到本机。支持三种模式：

- **omni**（原生多模态）：百炼 `qwen3.8-omni-flash-realtime` WebSocket（Manual + 本地能量 VAD）
- **agent**（级联回退）：能量 VAD → DashScope ASR → AgentScope LLM → CosyVoice TTS
- **echo**：麦克风回环

默认仍为 **agent**；上线原生语音时用 `BRIDGE_MODE=omni`。

## 协议

- 端口默认 **18799**（TCP，`0.0.0.0`）；leaf5 mock 仍在 **18798**，互不影响
- 帧：`uint32 BE 长度` + `uint8 类型` + `payload`
  - 类型 `0`：PCM s16le 单声道 16 kHz
  - 类型 `1`：控制文本（UTF-8），如 `HELLO …`
- 接通后桥端会发：`HELLO box mode=… sample=16000`（`agent` / `omni` / `echo`）
- 开发可用 Tailscale / USB reverse；**生产公网**：`<PUBLIC_IP>:18799`（阿里云 systemd）。远端响铃信令见 `signaling/ring_hub.py`（默认 `:18800`）。可选 `BRIDGE_TOKEN` / `SIGNAL_TOKEN`

## Agent 管线

| 步骤 | 模型 / 说明 |
|------|-------------|
| VAD | 能量门限（默认 RMS≥600），语音后静音约 **900ms** 判句末；忽略极短 blip |
| ASR | DashScope `fun-asr-flash-2026-06-15`（同步 base64 WAV；按音频秒计费；可用 `BRIDGE_ASR_MODEL` 覆盖） |
| LLM | AgentScope `qwen3.8-flash`（默认，短句更快；可用 `BRIDGE_LLM_MODEL=deepseek-v4.1-flash` 等覆盖） |
| TTS | DashScope CosyVoice `cosyvoice-v3-flash`，音色 `longanhuan`，16 kHz WAV→PCM |
| 播放 | type0 帧约 **40ms** 一块；**播放期间忽略麦克风**（MVP，无打断） |

鉴权（日志已脱敏，切勿打印）：

- `DASHSCOPE_API_KEY` — ASR / TTS（北京 Base：`https://dashscope.aliyuncs.com/api/v1`）
- `AGENTSCOPE_API_KEY` — LLM only（Base：`https://platform.agentscope.io/v1`）

其它环境变量：`BRIDGE_MODE`、`BRIDGE_OMNI_VOICE`、`BRIDGE_OMNI_MODEL`、`BRIDGE_OMNI_INSTRUCTIONS`、`BRIDGE_ASR_MODEL`、`BRIDGE_TTS_MODEL`、`BRIDGE_TTS_VOICE`、`BRIDGE_LLM_MODEL`、`BRIDGE_VAD_RMS`、`BRIDGE_VAD_SILENCE_MS`、`DASHSCOPE_BASE`、`AGENTSCOPE_BASE`。


## Omni 原生多模态（推荐低延迟）

| 项 | 说明 |
|----|------|
| 模型 | `qwen3.8-omni-flash-realtime`（可用 `BRIDGE_OMNI_MODEL` / `qwen3.5-omni-flash-realtime`） |
| 接入 | `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=…`（**无需** WorkspaceId；勿用 `{WorkspaceId}.cn-beijing.maas…` 除非该地址失败） |
| 轮次 | **Manual**（`turn_detection: null`）+ 本地 EnergyVAD；句末 `commit` + `response.create` |
| 音频 | 上行 pcm16 / 16 kHz；下行 pcm24 → 本地降采样到 16 kHz 再回手机 |
| 音色 | 默认 `Tina`（`BRIDGE_OMNI_VOICE`；Chelsie 在部分账号会报不支持） |
| 鉴权 | 仅需 `DASHSCOPE_API_KEY`（不要打印） |

离线冒烟：

```bash
./audio-bridge/.venv/bin/python \
  ./audio-bridge/omni_realtime.py
# → captures/omni-smoke-reply.pcm
```


## 白名单工具（电话可说）

设 `BRIDGE_TOOLS=1` 后启用（默认关）。仅允许固定动作，**无任意 shell**：

| 工具 | 语音触发例 |
|------|------------|
| `what_time` | 「现在几点」 |
| `bridge_status` | 「查一下桥状态」 |
| `ring_fake_call` | 「打个 VoIP 来电」 |
| `list_workspace` | 「列一下工作区」 |
| `tail_bridge_log` | 「看桥日志」 |
| `restart_bridge` | 「重启桥」（会断通话） |

路径：Omni **原生 tools**（`session.update`）+ **ASR 关键词路由**（优先）；agent 模式在 ASR 后关键词直达。每条命令超时约 10s；日志只记工具名与 ok/fail，不打印密钥。

```bash
BRIDGE_MODE=omni BRIDGE_TOOLS=1 ./audio-bridge/start.sh
# 离线冒烟
BRIDGE_TOOLS=1 .venv/bin/python bridge_tools.py
```

## Whale 双脑集成（可选）
- 实时电话仍由 Omni 直接处理音频；设置 `WHALE_MEMORY_DB=/private/path/whale.db` 可把用户和 AI 的最终转写异步写入同一记忆库。
- 不设置时保持原通话行为；此路径不请求 DeepSeek，不等待文本模型回复。
- `BRIDGE_TOKEN` 启用后，手机使用信令设置中的 Token 发送 PCM 连接首帧 `AUTH <token>`。**现在音频/信令共用手机端一个 Token 配置项，务必在隔离环境先验证；后续应分离两套凭据。**
- 默认监听改为 `127.0.0.1` 且默认不再留 PCM 捕获；跨设备连接须显式配置地址、认证与安全隧道。
- HTTP 远程信令已被手机客户端拒绝；需先配置有效 HTTPS 终端再部署新 APK。
- 当前默认桥模式仍为 agent；建议实际低延迟陪伴使用 `BRIDGE_MODE=omni`。

## 启动 / 停止（box）

```bash
# 默认 agent
./audio-bridge/start.sh

# 显式模式
BRIDGE_MODE=omni  ./audio-bridge/start.sh
BRIDGE_MODE=agent ./audio-bridge/start.sh
BRIDGE_MODE=echo  ./audio-bridge/start.sh

./audio-bridge/stop.sh
# 日志: audio-bridge/bridge.log
# PID:  audio-bridge/bridge.pid
# 录音: audio-bridge/captures/nova-mic-*.pcm
```

离线冒烟（无需手机）：

```bash
python3 ./audio-bridge/smoke_test.py
```

听捕获：`ffplay -f s16le -ar 16000 -ac 1 captures/xxx.pcm`

## 手机侧怎么打通

1. 确认桥已 `mode=agent` 监听 `18799`
2. 手机 APK 默认连 `<TAILSCALE_IP>:18799`（按实际 Tailscale IP）
3. 发起 VoIP 来电：`scripts/ring.sh '显示名' '号码' [host] [port]`（号码 `***` 时在 zsh 用 `set -f`）
4. 接听：UI「接听」或 `scripts/answer.sh` / `am broadcast … ACTION_ANSWER`
5. 说话 → 停顿约 1 秒 → 听助手回复；需 `RECORD_AUDIO`

## 限制

- MVP 播放中 / 管线处理中不接麦（无 barge-in / 无 AEC）；处理中会发 thinking beep + ~1.5s tick
- ASR/LLM/TTS 失败会尽量 TTS 一句中文道歉；对端已挂断时干净日志、不崩 handler
- TCP + Tailscale 延迟约几十～两百 ms


## 公网 / 信令（阿里云）

- 音频：`<PUBLIC_IP>:18799`（`nova-audio-bridge.service`）
- 信令：`<PUBLIC_IP>:18800`（`nova-ring-hub.service`，`signaling/ring_hub.py`）
- 详见 whale-io 归档仓 `infra/call-bridge/docs/public-aliyun-bridge.md`（未迁入）
