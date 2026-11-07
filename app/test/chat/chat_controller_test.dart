import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/controllers/chat_controller.dart';
import 'package:whale_mobile/features/chat/models/attachment.dart';
import 'package:whale_mobile/features/chat/models/conversation.dart';
import 'package:whale_mobile/features/chat/models/message.dart';
import 'package:whale_mobile/features/chat/models/persona.dart';
import 'package:whale_mobile/features/chat/services/chat_api.dart';
import 'package:whale_mobile/features/chat/services/chat_stream.dart';

/// 假 ChatApi：内存历史存储，验证分页游标与幂等对齐（不发真实网络）。
class FakeChatApi implements ChatApi {
  /// conversationId → 消息（新→旧，模拟 Core 倒序）。
  final Map<String, List<ChatMessage>> history = <String, List<ChatMessage>>{};

  int listMessagesCalls = 0;
  bool failNext = false;

  static const int pageSize = 3;

  @override
  Future<MessagePage> listMessages(String conversationId,
      {String? cursor, int limit = 50}) async {
    listMessagesCalls++;
    if (failNext) {
      failNext = false;
      throw const ChatApiException('network_error', '模拟网络失败');
    }
    final all = history[conversationId] ?? const <ChatMessage>[];
    final offset = cursor == null ? 0 : int.parse(cursor);
    final effectiveLimit = limit < pageSize ? limit : pageSize;
    final page = all.skip(offset).take(effectiveLimit).toList();
    final next =
        offset + effectiveLimit < all.length ? '${offset + effectiveLimit}' : null;
    return MessagePage(messages: page, nextCursor: next);
  }

  @override
  Future<ConversationPage> listConversations({String? cursor, int limit = 20}) =>
      throw UnimplementedError();

  @override
  Future<List<Persona>> listPersonas() => throw UnimplementedError();

  @override
  Future<void> bindPersona(String conversationKey, String? personaId) =>
      throw UnimplementedError();

  @override
  Future<void> deleteAttachment(String attachmentId) =>
      throw UnimplementedError();

  @override
  Future<Uri> attachmentUri(String attachmentId) =>
      Future.value(Uri.parse('http://x/$attachmentId'));

  @override
  Future<Map<String, String>> authHeaders() async => const {};

  @override
  void close() {}
}

/// 编码 SSE 字节流（模拟 Core `data: {...}\n\n` 帧，可指定分块切断点）。
Stream<List<int>> fakeSseBytes(List<Map<String, dynamic>> frames,
    {int splitEvery = 0}) {
  final raw = frames
      .map((f) => 'data: ${jsonEncode(f)}\n\n')
      .join();
  var bytes = utf8.encode(raw);
  if (splitEvery > 0 && bytes.length > splitEvery) {
    // 把字节流切成小块（模拟网络分包任意切断）
    final chunks = <List<int>>[];
    for (var i = 0; i < bytes.length; i += splitEvery) {
      chunks.add(bytes.sublist(
          i, i + splitEvery > bytes.length ? bytes.length : i + splitEvery));
    }
    return Stream.fromIterable(chunks);
  }
  return Stream.value(bytes);
}

void main() {
  group('ChatController 流式状态机', () {
    test('delta 累积 → done 落终态，用户消息回填 message_id', () async {
      final api = FakeChatApi();
      // 注入假字节流：phase → delta×2 → done（真解析器 + 假字节流）
      final streamClient = _FakeByteStreamClient(
        frames: [
          {'type': 'phase', 'phase': 'received'},
          {'type': 'delta', 'text': '你好'},
          {'type': 'delta', 'text': '，世界'},
          {
            'type': 'done',
            'reply': '你好，世界',
            'status': 'ok',
            'duplicate': false,
            'message_ids': {'user': 'evt_u1', 'assistant': 'evt_a1'},
          },
        ],
      );
      final c = ChatController(
        conversationId: 'mobile:test',
        api: api,
        streamClient: streamClient,
      );

      await c.send('hello');

      expect(c.sending, isFalse);
      expect(c.error, isNull);
      // 新→旧：assistant 在 index 0，user 在 index 1
      expect(c.messages.length, 2);
      expect(c.messages[0].role, MessageRole.assistant);
      expect(c.messages[0].text, '你好，世界');
      expect(c.messages[0].sendState, MessageSendState.settled);
      expect(c.messages[0].messageId, 'evt_a1');
      expect(c.messages[1].role, MessageRole.user);
      expect(c.messages[1].messageId, 'evt_u1'); // 幂等对齐回填
      expect(c.messages[1].clientMessageId, isNotNull);
      c.dispose();
    });

    test('tool 事件进入独立 toolEvents，不混入正文', () async {
      final c = ChatController(
        conversationId: 'mobile:test',
        api: FakeChatApi(),
        streamClient: _FakeByteStreamClient(frames: [
          {'type': 'tool.started', 'tool_call_id': 't1', 'tool': 'shell.run'},
          {'type': 'tool.progress', 'tool_call_id': 't1', 'progress': 50},
          {
            'type': 'tool.result',
            'tool_call_id': 't1',
            'tool': 'shell.run',
            'receipt': 'succeeded: ok'
          },
          {'type': 'delta', 'text': '完成'},
          {'type': 'done', 'reply': '完成', 'status': 'ok', 'duplicate': false},
        ]),
      );
      await c.send('跑个命令');
      final assistant = c.messages[0];
      expect(assistant.text, '完成');
      expect(assistant.toolEvents.length, 3);
      expect(assistant.toolEvents[0].kind, 'started');
      expect(assistant.toolEvents[1].progress, 50);
      expect(assistant.toolEvents[2].receipt, contains('succeeded'));
      c.dispose();
    });

    test('attachment.created 事件挂到消息附件', () async {
      final c = ChatController(
        conversationId: 'mobile:test',
        api: FakeChatApi(),
        streamClient: _FakeByteStreamClient(frames: [
          {
            'type': 'attachment.created',
            'attachment': {
              'attachment_id': 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
              'type': 'image',
              'mime': 'image/webp',
              'size': 100,
              'width': 64,
              'height': 64,
            }
          },
          {'type': 'done', 'reply': '', 'status': 'ok', 'duplicate': false},
        ]),
      );
      await c.send('画张图');
      expect(c.messages[0].attachments.length, 1);
      expect(c.messages[0].attachments[0].type, AttachmentType.image);
      c.dispose();
    });

    test('断线（终态前连接关闭）→ 显式标记失败，不重发', () async {
      final c = ChatController(
        conversationId: 'mobile:test',
        api: FakeChatApi(),
        streamClient: _FakeByteStreamClient(frames: [
          {'type': 'phase', 'phase': 'received'},
          {'type': 'delta', 'text': '半截回复'},
          // 无 done：连接直接关闭
        ]),
      );
      await c.send('hello');
      expect(c.messages[0].sendState, MessageSendState.failed);
      expect(c.messages[0].errorMessage, contains('stream_disconnected'));
      expect(c.error, isNotNull); // 顶部横幅可见
      expect(c.sending, isFalse);
      c.dispose();
    });

    test('error 事件 → 标记失败', () async {
      final c = ChatController(
        conversationId: 'mobile:test',
        api: FakeChatApi(),
        streamClient: _FakeByteStreamClient(frames: [
          {'type': 'error', 'error': 'unavailable'},
        ]),
      );
      await c.send('hello');
      expect(c.messages[0].sendState, MessageSendState.failed);
      expect(c.error, contains('unavailable'));
      c.dispose();
    });

    test('字节流任意分包切断仍能正确解析（分块=1 字节）', () async {
      final c = ChatController(
        conversationId: 'mobile:test',
        api: FakeChatApi(),
        streamClient: _FakeByteStreamClient(
          frames: [
            {'type': 'delta', 'text': '完整'},
            {'type': 'done', 'reply': '完整', 'status': 'ok', 'duplicate': false},
          ],
          splitEvery: 1, // 极端分包
        ),
      );
      await c.send('hi');
      expect(c.messages[0].text, '完整');
      expect(c.messages[0].sendState, MessageSendState.settled);
      c.dispose();
    });
  });

  group('ChatController 分页与幂等对齐', () {
    test('游标分页：逐页取回直到无 next_cursor', () async {
      final api = FakeChatApi();
      // 造 7 条历史（pageSize=3 → 3 页）
      api.history['mobile:test'] = List.generate(
        7,
        (i) => ChatMessage(
          messageId: 'evt_$i',
          role: i.isEven ? MessageRole.user : MessageRole.assistant,
          text: '消息 $i',
          createdAt: 1000 - i,
        ),
      );
      final c = ChatController(
        conversationId: 'mobile:test',
        api: api,
        streamClient: ChatStreamClient(baseUrl: 'http://x', token: 't'),
      );
      await c.loadInitial();
      expect(c.messages.length, 3);
      expect(c.hasMore, isTrue);
      await c.loadOlder();
      expect(c.messages.length, 6);
      expect(c.hasMore, isTrue);
      await c.loadOlder();
      expect(c.messages.length, 7);
      expect(c.hasMore, isFalse);
      // 再调 loadOlder 不应再发请求（无游标）
      final calls = api.listMessagesCalls;
      await c.loadOlder();
      expect(api.listMessagesCalls, calls);
      c.dispose();
    });

    test('幂等对齐：refresh 后 client_message_id 重复的历史不重复插入', () async {
      final api = FakeChatApi();
      final c = ChatController(
        conversationId: 'mobile:test',
        api: api,
        streamClient: _FakeByteStreamClient(frames: [
          {
            'type': 'done',
            'reply': '回复',
            'status': 'ok',
            'duplicate': false,
            'message_ids': {'user': 'evt_u', 'assistant': 'evt_a'},
          },
        ]),
      );
      await c.send('你好');
      expect(c.messages.length, 2);
      final clientId = c.messages[1].clientMessageId;

      // Core 历史里出现同 client_message_id 的用户消息 + 助手回复
      api.history['mobile:test'] = [
        ChatMessage(
            messageId: 'evt_a',
            role: MessageRole.assistant,
            text: '回复',
            createdAt: 2),
        ChatMessage(
            messageId: 'evt_u_dup',
            role: MessageRole.user,
            text: '你好',
            createdAt: 1,
            clientMessageId: clientId),
      ];
      await c.refresh();
      // 本地 user 消息已有 messageId=evt_u1？（done 回填的 evt_u）
      // refresh 保留 settled 的本地消息并与历史按 messageId/clientId 去重
      final userMsgs =
          c.messages.where((m) => m.role == MessageRole.user).toList();
      expect(userMsgs.length, 1, reason: '同 client_message_id 不得重复插入');
      final assistantMsgs =
          c.messages.where((m) => m.role == MessageRole.assistant).toList();
      expect(assistantMsgs.length, 1, reason: '同 message_id 不得重复插入');
      c.dispose();
    });

    test('历史加载失败：error 显式可见', () async {
      final api = FakeChatApi()..failNext = true;
      final c = ChatController(
        conversationId: 'mobile:test',
        api: api,
        streamClient: ChatStreamClient(baseUrl: 'http://x', token: 't'),
      );
      await c.loadInitial();
      expect(c.error, contains('加载历史失败'));
      expect(c.loadingInitial, isFalse);
      c.dispose();
    });
  });
}

/// 用真 ChatStreamClient + 假字节流（openStream 注入）的控制器驱动。
class _FakeByteStreamClient extends ChatStreamClient {
  _FakeByteStreamClient({required this.frames, this.splitEvery = 0})
      : super(baseUrl: 'http://x', token: 't');

  final List<Map<String, dynamic>> frames;
  final int splitEvery;

  @override
  Stream<ChatStreamEvent> streamReply({
    required String conversationId,
    required String clientMessageId,
    required String text,
    List<AttachmentRef> attachments = const [],
    Stream<List<int>> Function()? openStream,
  }) {
    return super.streamReply(
      conversationId: conversationId,
      clientMessageId: clientMessageId,
      text: text,
      attachments: attachments,
      openStream: () => fakeSseBytes(frames, splitEvery: splitEvery),
    );
  }
}
