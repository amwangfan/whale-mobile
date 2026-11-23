import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/controllers/chat_controller.dart';
import 'package:whale_mobile/features/chat/models/message.dart';
import 'package:whale_mobile/features/chat/services/chat_stream_v3.dart';
import 'package:whale_mobile/features/chat/services/link_chat_api.dart';
import 'package:whale_mobile/features/chat/services/run_feed.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

import '../link/fake_link_server.dart';

/// ChatController v3（Link RPC + 事件流 + SSE 退路 + 断线补齐）测试。
void main() {
  late FakeLinkServer server;
  late LinkClient client;
  late LinkChatApi api;

  setUp(() async {
    server = FakeLinkServer();
    await server.start();
    client = LinkClient(
      uri: server.uri,
      token: 'test-token',
      backoffSteps: const [Duration(milliseconds: 30)],
      pingInterval: const Duration(seconds: 3600),
    );
    client.connect();
    await _until(() => client.isConnected);
    api = LinkChatApi(client);
  });

  tearDown(() async {
    await client.dispose();
    await server.stop();
  });

  group('发送主链路（Link 事件流）', () {
    test('chat.send 立返 → started → delta 流式 → completed 定屏', () async {
      final c = ChatController(conversationId: 'mobile:t1', api: api);
      c.attach();
      await c.send('你好');
      await _until(() => !c.sending);
      expect(c.error, isNull);
      expect(c.messages, hasLength(2));
      final assistant = c.messages[0];
      expect(assistant.role, MessageRole.assistant);
      expect(assistant.text, '回复：你好');
      expect(assistant.sendState, MessageSendState.settled);
      expect(assistant.runId, isNotEmpty);
      // 轨迹：think 步落行
      expect(assistant.trace, isNotEmpty);
      expect(assistant.trace.first.kind, 'think');
      // 用户消息在 index 1
      expect(c.messages[1].role, MessageRole.user);
      expect(c.messages[1].text, '你好');
      c.dispose();
    });

    test('事件先于 rpc.response 到达（竞态）：按 conversation 认领 run', () async {
      server.chatResponseDelayMs = 100; // 事件先推、响应后到
      final c = ChatController(conversationId: 'mobile:t2', api: api);
      c.attach();
      await c.send('竞态');
      await _until(() => !c.sending, timeoutMs: 3000);
      expect(c.messages[0].text, '回复：竞态');
      expect(c.messages[0].sendState, MessageSendState.settled);
      c.dispose();
    });

    test('chat.send RPC 错误 → 显式失败占位（不重发）', () async {
      server.forcedError['chat.send'] = {
        'code': 'rate_limited',
        'message': '预算用尽',
      };
      final before = server.frames.where((f) =>
          f['type'] == 'rpc.request' &&
          (f['payload'] as Map)['method'] == 'chat.send');
      final beforeCount = before.length;
      final c = ChatController(conversationId: 'mobile:t3', api: api);
      c.attach();
      await c.send('会失败');
      expect(c.sending, isFalse);
      expect(c.messages[0].sendState, MessageSendState.failed);
      expect(c.messages[0].errorMessage, contains('rate_limited'));
      expect(c.error, isNotNull);
      final afterCount = server.frames.where((f) =>
          f['type'] == 'rpc.request' &&
          (f['payload'] as Map)['method'] == 'chat.send').length;
      expect(afterCount, beforeCount + 1, reason: '写路径绝不自动重放');
      c.dispose();
    });

    test('打断：cancelActive → run.cancelled → 占位落定已打断', () async {
      // 脚本只推 started（不 completed），等打断
      server.chatScript = (params, convId, runId) => [
        {'topic': 'run.started', 'data': {'run': server.runs[runId]}},
      ];
      final c = ChatController(conversationId: 'mobile:t4', api: api);
      c.attach();
      final sendFuture = c.send('长时间任务');
      await _until(() => c.activeRunId != null);
      expect(c.canCancel, isTrue);
      await c.cancelActive();
      await _until(() => !c.sending);
      await sendFuture;
      expect(c.messages[0].sendState, MessageSendState.cancelled);
      c.dispose();
    });
  });

  group('历史与分页（before=毫秒 ts）', () {
    test('loadInitial 新→旧 + loadOlder 按 before 翻页 + 去重', () async {
      // 预置 60 条历史（ts 1000..1059，正序落库）
      final msgs = <Map<String, dynamic>>[
        for (var i = 0; i < 60; i++)
          <String, dynamic>{
            'id': 'msg_h$i',
            'conversation_id': 'mobile:hist',
            'role': i.isEven ? 'user' : 'assistant',
            'content': '历史 $i',
            'run_id': null,
            'ts': 1000 + i,
            'meta': <String, dynamic>{},
          },
      ];
      server.store['mobile:hist'] = msgs;
      server.conversations['mobile:hist'] = {
        'id': 'mobile:hist',
        'title': '历史会话',
        'channel': 'mobile',
        'peer': 'owner',
        'persona_id': null,
        'created_at': 1000,
        'updated_at': 1059,
        'turns': 30,
        'meta': <String, dynamic>{},
      };

      final c = ChatController(conversationId: 'mobile:hist', api: api);
      c.attach();
      await c.loadInitial();
      expect(c.messages, hasLength(50));
      expect(c.hasMore, isTrue);
      // 新→旧：index 0 = 最新（历史 59）
      expect(c.messages.first.text, '历史 59');
      expect(c.messages.last.text, '历史 10');
      await c.loadOlder();
      expect(c.messages, hasLength(60));
      expect(c.messages.last.text, '历史 0');
      expect(c.hasMore, isFalse);
      // 再翻页不再请求（无更多）
      final reqCount = server.frames.where((f) =>
          f['type'] == 'rpc.request' &&
          (f['payload'] as Map)['method'] == 'conversations.messages').length;
      await c.loadOlder();
      expect(
          server.frames.where((f) =>
              f['type'] == 'rpc.request' &&
              (f['payload'] as Map)['method'] ==
                  'conversations.messages').length,
          reqCount);
      c.dispose();
    });

    test('历史失败显式置 error', () async {
      server.forcedError['conversations.messages'] = {
        'code': 'internal',
        'message': 'db down',
      };
      final c = ChatController(conversationId: 'mobile:x', api: api);
      c.attach();
      await c.loadInitial();
      expect(c.error, contains('加载历史失败'));
      expect(c.loadingInitial, isFalse);
      c.dispose();
    });
  });

  group('断线补齐', () {
    test('在飞 run 断线：重连后以补齐的 assistant 消息为准定屏', () async {
      // 脚本只推 started；完成发生在断线窗口
      server.chatScript = (params, convId, runId) => [
        {'topic': 'run.started', 'data': {'run': server.runs[runId]}},
      ];
      final c = ChatController(conversationId: 'mobile:cut', api: api);
      c.attach();
      await c.send('断线局');
      await _until(() => c.activeRunId != null);
      final runId = c.activeRunId!;
      expect(c.messages[0].sendState, MessageSendState.streaming);

      // 断线（chat.send 的 rpc.response 已收，run 在飞）
      await server.dropAll();
      await _until(() => client.state == LinkState.degraded ||
          client.state == LinkState.disconnected);
      // 断线窗口内服务端完成 run（assistant 消息落库 + 事件日志）
      server.completeRunOffline(runId, '断线期间的完整回复', convId: 'mobile:cut');

      // 重连 → welcome(isReconnect) → refresh 补齐
      await _until(() => client.isConnected, timeoutMs: 3000);
      await _until(() => !c.sending, timeoutMs: 3000);
      expect(c.messages[0].text, '断线期间的完整回复');
      expect(c.messages[0].sendState, MessageSendState.settled);
      expect(c.messages[0].runId, runId);
      c.dispose();
    });

    test('补齐三级回落：无 assistant 消息时按 events.query 的 run.failed 标失败',
        () async {
      server.chatScript = (params, convId, runId) => [
        {'topic': 'run.started', 'data': {'run': server.runs[runId]}},
      ];
      final c = ChatController(conversationId: 'mobile:cut2', api: api);
      c.attach();
      await c.send('失败局');
      await _until(() => c.activeRunId != null);
      final runId = c.activeRunId!;

      await server.dropAll();
      await _until(() => client.state != LinkState.connected);
      // 断线窗口：run 失败但 assistant 消息不落库（core failed 路径只落 system 消息）
      final run = server.runs[runId]!;
      run['status'] = 'failed';
      run['error'] = 'model exploded';
      run['ended_at'] = DateTime.now().millisecondsSinceEpoch;
      server.logEvent('run.failed', {'run': Map<String, dynamic>.of(run)});

      await _until(() => client.isConnected, timeoutMs: 3000);
      await _until(() => !c.sending, timeoutMs: 3000);
      expect(c.messages[0].sendState, MessageSendState.failed);
      expect(c.messages[0].errorMessage, contains('model exploded'));
      c.dispose();
    });
  });

  group('SSE 退路（弱网/无长连）', () {
    /// 断开 Link 后发送 → ChatStreamV3（注入假字节流）驱动同一状态机。
    test('Link 断开 → SSE 流式上屏 → done 定屏', () async {
      await client.dispose(); // 无长连
      final sseFrames = [
        ('run', {'run_id': 'run_sse_1', 'conversation_id': 'mobile:sse'}),
        ('step', {
          'id': 'st1', 'run_id': 'run_sse_1', 'seq': 1, 'kind': 'think',
          'title': '思考', 'status': 'done', 'input': <String, dynamic>{},
          'output': <String, dynamic>{},
        }),
        ('delta', {'text': '弱网'}),
        ('delta', {'text': '回复'}),
        ('done', {
          'run_id': 'run_sse_1', 'status': 'completed',
          'text': '弱网回复', 'conversation_id': 'mobile:sse', 'error': '',
        }),
      ];
      final c = ChatController(
        conversationId: 'mobile:sse',
        api: api,
        sse: _FakeSse(sseFrames),
      );
      await c.send('你好');
      expect(c.messages[0].text, '弱网回复');
      expect(c.messages[0].sendState, MessageSendState.settled);
      expect(c.messages[0].trace, isNotEmpty);
      c.dispose();
    });

    test('SSE 断线（done 前关闭）→ 显式失败，不伪装完成', () async {
      await client.dispose();
      final fakeSse = _FakeSse([
        ('run', {'run_id': 'r', 'conversation_id': 'mobile:sse2'}),
        ('delta', {'text': '半截'}),
      ]);
      final c = ChatController(
          conversationId: 'mobile:sse2', api: api, sse: fakeSse);
      await c.send('hi');
      expect(c.messages[0].sendState, MessageSendState.failed);
      expect(c.messages[0].errorMessage, contains('连接中断'));
      expect(c.error, isNotNull);
      c.dispose();
    });

    test('SSE done 前任意分包切断仍能解析（1 字节分块）', () async {
      await client.dispose();
      final frames = [
        ('delta', {'text': '完整'}),
        ('done', {
          'run_id': 'r2', 'status': 'completed', 'text': '完整',
          'conversation_id': 'mobile:sse3', 'error': '',
        }),
      ];
      final c = ChatController(
        conversationId: 'mobile:sse3',
        api: api,
        sse: _FakeSse(frames, splitEvery: 1),
      );
      await c.send('hi');
      expect(c.messages[0].text, '完整');
      expect(c.messages[0].sendState, MessageSendState.settled);
      c.dispose();
    });
  });

  group('模型解析', () {
    test('ChatMessage.fromJson v3 形状（meta.images/memories/tools）', () {
      final m = ChatMessage.fromJson(const {
        'id': 'msg_1',
        'conversation_id': 'mobile:a',
        'role': 'assistant',
        'content': '正文',
        'run_id': 'run_1',
        'ts': 1795400000000,
        'meta': {
          'memories': ['mem_1', 'mem_2'],
          'tools': [
            {'name': 'web.search', 'status': 'ok'},
          ],
        },
      });
      expect(m.messageId, 'msg_1');
      expect(m.memoryIds, hasLength(2));
      expect(m.traceSummary, contains('想起了 2 条'));
      expect(m.traceSummary, contains('web.search'));
    });

    test('用户消息图片计数（meta.images）', () {
      final m = ChatMessage.fromJson(const {
        'id': 'msg_2',
        'role': 'user',
        'content': '看图',
        'ts': 1,
        'meta': {'images': 2},
      });
      expect(m.imageCount, 2);
    });
  });
}

/// 假 SSE：直接给假字节流（跳过网络）。
class _FakeSse extends ChatStreamV3 {
  _FakeSse(this.frames, {this.splitEvery = 0}) : super(baseUrl: 'http://x');

  final List<(String, Map<String, dynamic>)> frames;
  final int splitEvery;

  @override
  Stream<RunFeedEvent> streamTurn({
    required String text,
    String? conversationId,
    List<String> images = const [],
    String channel = 'mobile',
    Stream<List<int>> Function()? openStream,
  }) {
    return super.streamTurn(
      text: text,
      conversationId: conversationId,
      images: images,
      channel: channel,
      openStream: () {
        final raw = frames
            .map((f) => 'event: ${f.$1}\ndata: ${jsonEncode(f.$2)}\n\n')
            .join();
        final bytes = utf8.encode(raw);
        if (splitEvery > 0 && bytes.length > splitEvery) {
          final chunks = <List<int>>[];
          for (var i = 0; i < bytes.length; i += splitEvery) {
            chunks.add(bytes.sublist(i,
                i + splitEvery > bytes.length ? bytes.length : i + splitEvery));
          }
          return Stream.fromIterable(chunks);
        }
        return Stream.value(bytes);
      },
    );
  }
}

Future<void> _until(bool Function() cond, {int timeoutMs = 3000}) async {
  final deadline = DateTime.now().millisecondsSinceEpoch + timeoutMs;
  while (!cond()) {
    if (DateTime.now().millisecondsSinceEpoch > deadline) {
      fail('condition not met within ${timeoutMs}ms');
    }
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
