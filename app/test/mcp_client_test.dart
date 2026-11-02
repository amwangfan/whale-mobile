import 'package:flutter_test/flutter_test.dart';
import 'package:whale_mobile/modules/screenmemo/mcp_client.dart';

import 'fake_mcp_server.dart';

void main() {
  group('ScreenMemoMcpClient', () {
    late FakeMcpServer server;
    late ScreenMemoMcpClient client;

    setUp(() async {
      server = FakeMcpServer();
      await server.start();
      client = ScreenMemoMcpClient(
        port: server.port,
        tokenProvider: () async => server.token,
      );
    });

    tearDown(() async {
      await server.stop();
    });

    test('initialize 后 tools/call 正常返回 structuredContent', () async {
      server.handlers['screenmemo_status'] = (args) => toolResult(
        <String, dynamic>{
          'service': <String, dynamic>{'running': true},
          'database': <String, dynamic>{'ok': true, 'segment_count': 42},
        },
      );

      final status = await client.status();

      expect(status['database'], isA<Map>());
      expect((status['database'] as Map)['segment_count'], 42);
      expect(client.state, McpConnectionState.ready);
      // initialize + tools/call 各一次；第二次调用不再重复握手
      await client.status();
      final toolCalls = server.calls.map((c) => c['name']).toList();
      expect(toolCalls, ['screenmemo_status', 'screenmemo_status']);
    });

    test('连接拒绝 → unavailable', () async {
      final dead = ScreenMemoMcpClient(
        port: await FakeMcpServer.freePort(),
        tokenProvider: () async => 'x',
        timeout: const Duration(seconds: 2),
      );
      expect(
        () => dead.status(),
        throwsA(isA<McpException>()
            .having((e) => e.kind, 'kind', McpErrorKind.unavailable)),
      );
      try {
        await dead.status();
      } on McpException {
        // 状态已记录
      }
      expect(dead.state, McpConnectionState.unavailable);
    });

    test('token 错（401）→ denied', () async {
      final bad = ScreenMemoMcpClient(
        port: server.port,
        tokenProvider: () async => 'wrong-token',
      );
      try {
        await bad.status();
        fail('应当抛 McpException');
      } on McpException catch (e) {
        expect(e.kind, McpErrorKind.denied);
      }
      expect(bad.state, McpConnectionState.denied);
    });

    test('未配置 token → 401 → denied', () async {
      final noToken = ScreenMemoMcpClient(port: server.port);
      expect(
        noToken.status(),
        throwsA(isA<McpException>()
            .having((e) => e.kind, 'kind', McpErrorKind.denied)),
      );
    });

    test('响应超时 → unavailable', () async {
      server.delay = const Duration(seconds: 2);
      final slow = ScreenMemoMcpClient(
        port: server.port,
        tokenProvider: () async => server.token,
        timeout: const Duration(milliseconds: 300),
      );
      expect(
        slow.status(),
        throwsA(isA<McpException>()
            .having((e) => e.kind, 'kind', McpErrorKind.unavailable)),
      );
    });

    test('工具 isError → toolError，携带服务端消息', () async {
      server.handlers['get_evidence_images'] =
          (args) => toolError('No image references found.');
      expect(
        () => client.callToolStructured('get_evidence_images'),
        throwsA(isA<McpException>()
            .having((e) => e.kind, 'kind', McpErrorKind.toolError)
            .having((e) => e.message, 'message',
                contains('No image references found'))),
      );
    });

    test('未知工具 → JSON-RPC error → protocol', () async {
      expect(
        () => client.callToolStructured('no_such_tool'),
        throwsA(isA<McpException>()
            .having((e) => e.kind, 'kind', McpErrorKind.protocol)),
      );
    });

    test('arguments 透传（含 limit/时间窗）', () async {
      Map<String, dynamic>? seenArgs;
      server.handlers['search_dynamics'] = (args) {
        seenArgs = args;
        return toolResult(<String, dynamic>{'items': <dynamic>[]});
      };
      await client.callToolStructured('search_dynamics', <String, dynamic>{
        'query': '微信',
        'limit': 7,
        'start_time': 1730000000000,
      });
      expect(seenArgs, isNotNull);
      expect(seenArgs!['query'], '微信');
      expect(seenArgs!['limit'], 7);
      expect(seenArgs!['start_time'], 1730000000000);
    });
  });
}
