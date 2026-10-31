import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// ScreenMemo MCP 客户端错误分类（DESIGN.md §3 失败映射的输入）。
enum McpErrorKind {
  /// 服务不可达 / 超时 / HTTP 非 200（连接拒绝、服务停止、超时）。
  unavailable,

  /// 401/403：Bearer token 缺失或失效。
  denied,

  /// JSON-RPC 层错误（协议错误、方法不存在、参数错误）。
  protocol,

  /// 工具级失败（result.isError == true）。
  toolError,
}

class McpException implements Exception {
  McpException(this.kind, this.message, {this.statusCode});

  final McpErrorKind kind;
  final String message;
  final int? statusCode;

  @override
  String toString() => 'McpException($kind, $message)';
}

/// MCP 连接状态（供 UI 展示最近一次交互结果）。
enum McpConnectionState {
  unknown,
  ready,
  denied,
  unavailable;

  String get label {
    switch (this) {
      case McpConnectionState.unknown:
        return '未测试';
      case McpConnectionState.ready:
        return '已连接';
      case McpConnectionState.denied:
        return '鉴权失败（token 无效）';
      case McpConnectionState.unavailable:
        return '不可达';
    }
  }
}

/// ScreenMemo 本机 MCP 服务（WM-03 加固版，127.0.0.1:37621/mcp）的 JSON-RPC 2.0 客户端。
///
/// - 纯 Dart（dart:io HttpClient），不依赖 Flutter，可单测。
/// - 首次 callTool 前自动 initialize；token 经 [tokenProvider] 惰性获取，不落日志。
/// - 默认 8s 超时；连接拒绝/超时 → [McpErrorKind.unavailable]；401 → [McpErrorKind.denied]。
class ScreenMemoMcpClient {
  ScreenMemoMcpClient({
    this.host = '127.0.0.1',
    this.port = 37621,
    this.path = '/mcp',
    this.timeout = const Duration(seconds: 8),
    Future<String?> Function()? tokenProvider,
    HttpClient Function()? httpClientFactory,
  })  : _tokenProvider = tokenProvider, // ignore: prefer_initializing_formals
        _httpClientFactory = httpClientFactory ?? HttpClient.new;

  static const String protocolVersion = '2025-06-18';

  final String host;
  final int port;
  final String path;
  final Duration timeout;
  final Future<String?> Function()? _tokenProvider;
  final HttpClient Function() _httpClientFactory;

  McpConnectionState _state = McpConnectionState.unknown;
  McpConnectionState get state => _state;

  bool _initialized = false;
  int _nextId = 1;

  /// MCP initialize 握手（幂等；失败后下次调用会重试）。
  Future<Map<String, dynamic>> initialize() async {
    if (_initialized) return const {};
    final result = await _rpc('initialize', <String, dynamic>{
      'protocolVersion': protocolVersion,
      'capabilities': <String, dynamic>{},
      'clientInfo': <String, dynamic>{'name': 'whale-mobile', 'version': '1.0.0'},
    });
    _initialized = true;
    return result;
  }

  /// 调用 MCP 工具，返回完整 result（含 content / structuredContent / isError）。
  Future<Map<String, dynamic>> callTool(
    String name, [
    Map<String, dynamic>? arguments,
  ]) async {
    await initialize();
    return _rpc('tools/call', <String, dynamic>{
      'name': name,
      'arguments': arguments ?? const <String, dynamic>{},
    });
  }

  /// 调用工具并取 structuredContent；isError=true 时抛 [McpErrorKind.toolError]。
  Future<Map<String, dynamic>> callToolStructured(
    String name, [
    Map<String, dynamic>? arguments,
  ]) async {
    final result = await callTool(name, arguments);
    if (result['isError'] == true) {
      throw McpException(McpErrorKind.toolError, extractText(result));
    }
    final sc = result['structuredContent'];
    if (sc is Map) return sc.cast<String, dynamic>();
    return const <String, dynamic>{};
  }

  /// 健康检查（screenmemo_status）。
  Future<Map<String, dynamic>> status() => callToolStructured('screenmemo_status');

  /// 重置初始化状态（token 更换后调用，下次 callTool 重新握手）。
  void resetSession() {
    _initialized = false;
  }

  /// 从工具 result 的 content[] 提取首条 text（错误消息用）。
  static String extractText(Map<String, dynamic> result) {
    final content = result['content'];
    if (content is List) {
      for (final entry in content) {
        if (entry is Map && entry['type'] == 'text' && entry['text'] is String) {
          return entry['text'] as String;
        }
      }
    }
    return 'tool error';
  }

  Future<Map<String, dynamic>> _rpc(
    String method, [
    Map<String, dynamic>? params,
  ]) async {
    final id = _nextId++;
    final body = jsonEncode(<String, dynamic>{
      'jsonrpc': '2.0',
      'id': id,
      'method': method,
      'params': ?params,
    });
    String? token;
    try {
      token = await _tokenProvider?.call();
    } catch (_) {
      token = null; // 通道异常按未配置 token 处理（服务端会回 401 → denied）
    }

    final client = _httpClientFactory();
    client.connectionTimeout = timeout;
    try {
      final request = await client.post(host, port, path).timeout(timeout);
      request.headers.contentType = ContentType.json;
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (token != null && token.isNotEmpty) {
        request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      // ScreenMemo MCP 服务端按 Content-Length 读体（不支持 chunked），必须显式设置。
      final bodyBytes = utf8.encode(body);
      request.contentLength = bodyBytes.length;
      request.add(bodyBytes);
      final response = await request.close().timeout(timeout);
      final text = await utf8.decoder.bind(response).join().timeout(timeout);

      if (response.statusCode == 401 || response.statusCode == 403) {
        _state = McpConnectionState.denied;
        throw McpException(
          McpErrorKind.denied,
          'MCP 鉴权失败（HTTP ${response.statusCode}）',
          statusCode: response.statusCode,
        );
      }
      if (response.statusCode != 200) {
        _state = McpConnectionState.unavailable;
        throw McpException(
          McpErrorKind.unavailable,
          'MCP HTTP ${response.statusCode}',
          statusCode: response.statusCode,
        );
      }

      final Object? decoded;
      try {
        decoded = jsonDecode(text);
      } on FormatException catch (e) {
        _state = McpConnectionState.unavailable;
        throw McpException(McpErrorKind.protocol, 'MCP 响应非 JSON: ${e.message}');
      }
      if (decoded is! Map) {
        _state = McpConnectionState.unavailable;
        throw McpException(McpErrorKind.protocol, 'MCP 响应不是 JSON 对象');
      }
      final map = decoded.cast<String, dynamic>();
      final error = map['error'];
      if (error is Map) {
        // JSON-RPC 错误：-32602 参数错误视为协议错，其余统一 protocol。
        throw McpException(
          McpErrorKind.protocol,
          'MCP error ${error['code']}: ${error['message']}',
        );
      }
      final result = map['result'];
      if (result is! Map) {
        throw McpException(McpErrorKind.protocol, 'MCP 响应缺少 result');
      }
      _state = McpConnectionState.ready;
      return result.cast<String, dynamic>();
    } on McpException {
      rethrow;
    } on SocketException catch (e) {
      // 连接拒绝 / 网络不可达 / 对端重置。
      _state = McpConnectionState.unavailable;
      throw McpException(McpErrorKind.unavailable, 'MCP 连接失败: ${e.message}');
    } on TimeoutException {
      _state = McpConnectionState.unavailable;
      throw McpException(McpErrorKind.unavailable, 'MCP 请求超时（>${timeout.inSeconds}s）');
    } on HttpException catch (e) {
      _state = McpConnectionState.unavailable;
      throw McpException(McpErrorKind.unavailable, 'MCP HTTP 错误: ${e.message}');
    } catch (e) {
      _state = McpConnectionState.unavailable;
      throw McpException(McpErrorKind.unavailable, 'MCP 请求异常: $e');
    } finally {
      client.close(force: true);
    }
  }
}
