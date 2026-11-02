import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// 测试用假 MCP HTTP server：模拟 ScreenMemo WM-03 加固版的最小 JSON-RPC 面
/// （initialize / tools/call，Bearer 校验，可注入延迟与工具响应）。
class FakeMcpServer {
  FakeMcpServer({this.token = 'test-token'});

  final String token;
  HttpServer? _server;

  /// 工具名 → 响应生成器（入参为 arguments，返回 tools/call 的 result 对象）。
  final Map<String, Object? Function(Map<String, dynamic> args)> handlers =
      <String, Object? Function(Map<String, dynamic> args)>{};

  /// 已收到的 tools/call 调用记录（name + arguments）。
  final List<Map<String, dynamic>> calls = <Map<String, dynamic>>[];

  /// 响应前延迟（超时测试用）。
  Duration delay = Duration.zero;

  int get port => _server!.port;

  Future<void> start() async {
    _server = await HttpServer.bind('127.0.0.1', 0);
    _server!.listen(_handle);
  }

  Future<void> stop() async {
    await _server?.close(force: true);
    _server = null;
  }

  /// 找一个“应该已被拒绝”的空闲端口（bind 后立刻 close）。
  static Future<int> freePort() async {
    final s = await HttpServer.bind('127.0.0.1', 0);
    final p = s.port;
    await s.close();
    return p;
  }

  Future<void> _handle(HttpRequest req) async {
    if (delay > Duration.zero) {
      await Future<void>.delayed(delay);
    }
    try {
      if (req.uri.path != '/mcp') {
        req.response.statusCode = 404;
        return;
      }
      final auth = req.headers.value('authorization');
      if (auth != 'Bearer $token') {
        req.response.statusCode = 401;
        req.response.headers.set('WWW-Authenticate', 'Bearer');
        return;
      }
      final body = await utf8.decoder.bind(req).join();
      final rpc = jsonDecode(body) as Map<String, dynamic>;
      final id = rpc['id'];
      final method = rpc['method'] as String?;
      Object? result;
      Object? error;
      if (method == 'initialize') {
        result = <String, dynamic>{
          'protocolVersion': '2025-06-18',
          'serverInfo': <String, dynamic>{'name': 'fake-screenmemo', 'version': '0'},
        };
      } else if (method == 'ping') {
        result = <String, dynamic>{};
      } else if (method == 'tools/call') {
        final params = (rpc['params'] as Map).cast<String, dynamic>();
        final name = params['name'] as String;
        final args =
            (params['arguments'] as Map?)?.cast<String, dynamic>() ??
                <String, dynamic>{};
        calls.add(<String, dynamic>{'name': name, 'arguments': args});
        final handler = handlers[name];
        if (handler == null) {
          error = <String, dynamic>{'code': -32601, 'message': 'unknown tool'};
        } else {
          result = handler(args);
        }
      } else {
        error = <String, dynamic>{'code': -32601, 'message': 'method not found'};
      }
      req.response.headers.contentType = ContentType.json;
      final respObj = <String, dynamic>{'jsonrpc': '2.0', 'id': id};
      if (error != null) {
        respObj['error'] = error;
      } else {
        respObj['result'] = result;
      }
      req.response.write(jsonEncode(respObj));
    } finally {
      await req.response.close();
    }
  }
}

/// 构造与 ScreenMemo toolResult 同构的 result（content text + structuredContent）。
Map<String, dynamic> toolResult(Map<String, dynamic> structured,
    {List<Map<String, dynamic>>? extraContent}) {
  return <String, dynamic>{
    'content': <Map<String, dynamic>>[
      <String, dynamic>{'type': 'text', 'text': jsonEncode(structured)},
      ...?extraContent,
    ],
    'structuredContent': structured,
    'isError': false,
  };
}

/// 构造工具级错误（isError=true）。
Map<String, dynamic> toolError(String message) {
  return <String, dynamic>{
    'content': <Map<String, dynamic>>[
      <String, dynamic>{'type': 'text', 'text': message},
    ],
    'isError': true,
  };
}
