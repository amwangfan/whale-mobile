# modules/screenmemo — MCP 适配器设计（WM-04 预研）

> 依赖：WM-02（transport）、WM-03（ScreenMemo 加固）。本文档在编码前冻结适配层设计。

## 1. 定位

whale-mobile 作为 MCP **客户端**访问本机 ScreenMemo（加固后绑 127.0.0.1:37621，Bearer token 经 method channel 内部传递，不落地日志）。对外只暴露三个云端能力：`screen.context.read` / `screen.history.search` / `screen.evidence.read`。

## 2. 八工具 → 三能力映射

| 云端能力（contracts） | MCP 工具 | 返回裁剪规则 |
| --- | --- | --- |
| screen.context.read | get_dynamic_context + list_recent_dynamics(+get_segment 补细节) | 时间段段落摘要；默认不含 OCR 原文；字段：segment_id/起止时间/app 名/摘要文本/privacy_class |
| screen.history.search | search_screenshots + search_dynamics + search_docs（按 query 路由） | 命中列表：segment_id/score/snippet（≤200 字符）/capture_time/app；evidence_ref 用加固后 opaque id |
| screen.evidence.read | get_evidence_images（仅 opaque ref）+ screenmemo_status（健康检查） | **逐笔二次授权**；图片 base64 ≤5 张；privacy_class=owner_sensitive |

## 3. 数据规则

- MCP 响应中的任何 `file_path`/`database.path` 字段一律丢弃（WM-03 已从源头移除，适配器再做一次防御性过滤）。
- 失败映射：ScreenMemo 未安装/服务停止/token 失效 → `sensor.result{status:"unavailable"}`；scope 不足 → `"denied"`；绝不返回伪造空结果。
- 限流：search ≤ 10 次/分钟/设备；evidence ≤ 5 次/分钟。
- 所有云端请求到达适配器前必须已过 transport 层的 action/sensor.query 校验；适配器再核对该请求 scope 与本地模块开关。

## 4. 代码落点

- Dart：`app/lib/modules/screenmemo/`（MCP JSON-RPC 客户端、能力映射、裁剪器、限流器）
- 通道：Flutter `dart:io` HttpClient 直连 127.0.0.1:37621（无需原生代码）
- token 获取：用户在 ScreenMemo 设置页查看 token → 粘贴进 Whale Mobile 配对向导 → 存 Keystore 加密存储（与设备身份同机制）

## 5. 测试

- 单测：映射/裁剪/限流/错误映射（Dart test）
- 契约测试：构造 MCP 假响应（含 file_path 字段）断言被剥离
- 真机（leaf5）：ScreenMemo 采集真实数据 → search/context 全链路 → 证据图二次授权链路
