# server/dev_gateway — V1 开发参考网关

> ⚠️ **这是开发用参考实现，不是生产组件。** 它存在的唯一目的：在 whale-core
> 真实设备面就绪之前，为 whale-mobile 手机端提供传输层联调对端。生产环境由
> **whale-core 的 `devices/` 组件取代本服务**；替换时不改动
> `contracts/v1/`（协议事实源），手机端无感知。
>
> 协议事实源：[`contracts/v1/PROTOCOL.md`](../contracts/v1/PROTOCOL.md) 与
> [`contracts/v1/messages/*.schema.json`](../contracts/v1/messages/)。

## 功能

- 明文 WebSocket 服务（默认 `127.0.0.1:8765`；TLS 由外层反代终结，本进程不做，§1）。
- 握手：连接建立即下发 `auth.challenge`（32 字节 hex 挑战，默认 30s 有效）；
  首帧必须是 `device.hello`，Ed25519 验签通过才回 `device.hello_ack`，
  否则回 `error`（`auth_failed` / `device_revoked` / `protocol_version`）并关闭。
- 心跳：按 `hello_ack.heartbeat_interval` 期待 `device.heartbeat`；
  默认 90s 无心跳在内存中标记离线（可调）。
- `sensor.event`：schema 校验 → 按 `event_id` 幂等去重（SQLite）→ 落库 + 脱敏日志
  （只打 kind / event_id / privacy_class / summary 长度，不打摘要全文）。
- `sensor.query` / `action.request`：经回环管理通道（默认 `127.0.0.1:8766`，
  JSON lines）由 CLI 触发，向在线设备下发并等待 `sensor.result` / `action.receipt`
  （带 deadline 超时）。
- 双向每一帧都过 `contracts/v1/messages` 的 JSON Schema；未知 major 版本拒绝并回
  `error.protocol_version`；单帧上限 256 KiB。

## 安装与运行

```bash
pip install -r server/requirements.txt   # websockets + cryptography
python3 server/dev_gateway.py serve      # 127.0.0.1:8765（ws）/ 127.0.0.1:8766（admin）
```

数据持久化在 `server/dev_gateway.db`（SQLite，可用 `--db` 改路径；已 gitignore）。

## CLI

```bash
# 配对（开发简化流程；真实流程中公钥由手机经配对通道上报，此处显式传入）
python3 server/dev_gateway.py pair-request --label "Pixel 8" --pubkey <64hex公钥> [--scopes a,b,c]
#   → 打印 6 位配对码与 device_id（= 公钥指纹 SHA-256 前16字节 hex）
python3 server/dev_gateway.py pair-approve 123456      # 批准 → active
python3 server/dev_gateway.py pair-list
python3 server/dev_gateway.py pair-revoke <device_id>  # 撤销即时生效

# 下行请求（要求 serve 正在运行；设备不在线/无 scope 会返回错误）
python3 server/dev_gateway.py query --device <id> --operation screen.history.search \
    --filters '{"text":"whale"}' --max-items 5 --timeout 15
python3 server/dev_gateway.py action --device <id> --capability phone.notification.post \
    --args '{"title":"hi","body":"from core"}' [--idempotency-key my-key-123]
```

## 测试

```bash
python3 -m pytest server/test_dev_gateway.py -v
```

集成测试用 websockets 客户端模拟设备，覆盖：配对 → hello 验签 → 心跳 →
sensor.event 上行（含重复 event_id 去重验证）→ query/result 与 action/receipt
往返 → 伪造签名被拒 → 撤销后连接被拒 → 未知 major 被拒 → schema 违例回 error →
心跳超时标记离线 → 幂等键重复拒绝。密钥对在测试内现生成，不落盘。

## 与协议/生产实现的差异（开发简化，仅供联调）

1. **签名输入**：`hello.payload.signature = Ed25519_sign(私钥, challenge 的
   UTF-8(ASCII) 字节)`——即 64 字符 hex 串本身，不是解码后的 32 字节
   （协议未显式规定，schema 注释已按此约定写明）。
2. **配对**：`pair-request` 经 CLI 直接登记公钥并生成配对码，`pair-approve`
   批准。真实流程是手机在可信配对通道上报公钥、主人在管理端确认（§3）。
3. **撤销踢线**：`pair-revoke` 立即改库，**新连接**被拒（`device_revoked`）；
   已在线连接不会被主动踢断（生产实现应即时踢线并清空下行队列，§6）。
4. **上行 seq** 跳变只记日志不拒绝；**未知消息类型**忽略（前向兼容）。
5. schema 校验失败统一回 `error.internal`（V1 错误码集中没有 invalid_message）。
6. `call.*` 消息不做业务处理（媒体通道不属于本参考实现，§7）。
7. 管理通道是回环明文 JSON lines，仅 bind 127.0.0.1，无认证——生产由
   whale-core 内部 RPC 取代。
