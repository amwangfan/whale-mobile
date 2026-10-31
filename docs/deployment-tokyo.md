# 东京节点部署记录（aliyun-tokyo，流量出入口）

> 服务器：阿里云东京 100.64.154.46（tailnet）/ 公网 <公网IP见内部账本>，域名 rayneo.wangfanlab.com（nginx 443）。
> 定位：媒体/信令中继与流量出入口；永不承载隐私原始数据存储。

## 已部署（systemd，自启）

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| whale-audio-bridge | 100.64.154.46:18799 | PCM 桥（BRIDGE_TOKEN 鉴权，AUTH 首帧），当前 echo 模式 |
| whale-ring-hub | 100.64.154.46:18800 | 信令长轮询（SIGNAL_TOKEN 强制；非回环无 token 拒绝启动已生效） |

凭据存 N100 `/opt/whale/secrets/tokyo-bridge.env`（600，不入任何仓库）。

## 实机验证（2026-11-01）

nova 经 tailnet 呼叫 → 东京 echo 桥：鉴权通过，双向 PCM ≈32KB/s 满速 23.2s，挂断干净释放。

## 待办

- 公网承载语音前必须加 TLS（PCM 明文不可出 tailnet）：App 侧 AudioBridge 换 SSLSocket + Tokyo 侧 stunnel/nginx stream。域名建议新增子域（rayneo.* 已被眼镜桥占用）。
- echo 模式换 agent/omni 模式需 DASHSCOPE/AGENTSCOPE key（secrets 注入 systemd Environment）。

## omni 模式上线（2026-11-01）

- 凭据从原阿里云（aliyun 节点 nova-audio-bridge，已 inactive）迁至 `/opt/whale-mobile/audio-bridge.env`（600）
- 服务模式 echo→omni（qwen3.8-omni-flash-realtime，voice=Tina，6 工具）
- nova 实测 Realtime 通话 50s：session.created→问候语音合成→挂断 WS 干净关闭；rx 1.58MB
