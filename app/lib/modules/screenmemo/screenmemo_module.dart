import 'dart:async';

import '../../transport/transport_client.dart';
import 'adapter.dart';
import 'mcp_client.dart';

/// ScreenMemo 模块胶水：把 transport 层的 sensor.query 流接到适配器，
/// 并保证每个 query 都有且仅有一次 sensor.result 回包（异常也回 error）。
///
/// 接线顺序：init() 注册 delegate → Kotlin 才开始把 sensor.query 转发给 Dart；
/// 此前/模块关闭时由 Kotlin fail-closed 或适配器回 denied，云端不会空等。
class ScreenMemoModule {
  /// 默认构造：mcpClient 的 token 经 transport 的 Keystore 加密存储惰性获取。
  factory ScreenMemoModule({
    TransportClient? transport,
    ScreenMemoMcpClient? mcpClient,
    ScreenMemoAdapter? adapter,
  }) {
    final t = transport ?? TransportClient();
    if (adapter != null) {
      return ScreenMemoModule._(t, adapter, adapter.client);
    }
    final c =
        mcpClient ?? ScreenMemoMcpClient(tokenProvider: () => t.getMcpToken());
    return ScreenMemoModule._(t, ScreenMemoAdapter(client: c), c);
  }

  ScreenMemoModule._(this.transport, this.adapter, this.mcpClient);

  final TransportClient transport;
  final ScreenMemoMcpClient mcpClient;
  final ScreenMemoAdapter adapter;

  StreamSubscription<SensorQuery>? _querySub;
  bool _started = false;

  /// 默认开（DESIGN.md §3 本地模块开关）。
  bool get enabled => adapter.enabled;

  /// 启动：token provider 接到加密存储 → 订阅 sensor.query → 声明 Dart 接管。
  Future<void> init() async {
    if (_started) return;
    _started = true;
    _querySub = transport.sensorQueries.listen(
      _onQuery,
      onError: (Object _) {/* 通道异常不致命，保持订阅 */},
    );
    try {
      await transport.setSensorQueryDelegate(true);
    } catch (_) {
      // 原生侧不可用时（单元测试环境）静默降级：无 delegate，WssClient fail-closed。
    }
  }

  /// 开关切换；关闭时适配器对后续查询回 denied(module_disabled)。
  Future<void> setEnabled(bool value) async {
    adapter.enabled = value;
  }

  /// 保存/清除 MCP Bearer token（Keystore 加密存储，经 method channel）。
  Future<void> setToken(String token) async {
    await transport.setMcpToken(token);
    mcpClient.resetSession();
  }

  Future<String?> getToken() => transport.getMcpToken();

  Future<bool> hasToken() async => (await getToken())?.isNotEmpty ?? false;

  /// UI 连接测试：ping screenmemo_status。McpException 直接上抛给 UI 展示。
  Future<Map<String, dynamic>> ping() => adapter.ping();

  Future<void> _onQuery(SensorQuery q) async {
    SensorResultReply reply;
    try {
      reply = await adapter
          .handleQuery(ScreenQueryRequest(
            operation: q.operation,
            filters: q.filters,
            maxItems: q.maxItems,
            deadlineMs: q.deadline,
          ))
          .timeout(_replyBudget(q), onTimeout: () {
        return SensorResultReply.fail(
          'error',
          'deadline_exceeded',
          '处理超时（deadline 到期）',
        );
      });
    } catch (e) {
      // 兜底：适配器承诺不抛，此处防御性再兜一层，保证总有回包。
      reply = SensorResultReply.fail('error', 'internal', '$e');
    }
    try {
      await transport.sendSensorResult(
        requestId: q.requestId,
        status: reply.status,
        data: reply.data,
        truncated: reply.truncated,
        source: reply.source,
      );
    } catch (_) {
      // 传输层不可用（未连接）：消息已尽力，WSS 恢复后不重发 query 结果。
    }
  }

  /// 处理预算：deadline 前 1s 截断；无 deadline 用 MCP 超时两倍兜底。
  Duration _replyBudget(SensorQuery q) {
    if (q.deadline > 0) {
      final remain = q.deadline -
          DateTime.now().millisecondsSinceEpoch -
          1000; // 留 1s 回包余量
      if (remain > 0) return Duration(milliseconds: remain);
      return Duration.zero; // 立即触发 timeout → error 回包
    }
    return mcpClient.timeout * 2 + const Duration(seconds: 1);
  }

  Future<void> dispose() async {
    await _querySub?.cancel();
    _querySub = null;
    _started = false;
    try {
      await transport.setSensorQueryDelegate(false);
    } catch (_) {
      // 原生侧已销毁（引擎拆卸中）：无需再注销 delegate
    }
  }
}
