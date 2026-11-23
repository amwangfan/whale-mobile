import 'dart:async';

import 'package:whale_mobile/features/chat/models/approval.dart';
import 'package:whale_mobile/features/chat/models/conversation.dart';
import 'package:whale_mobile/features/chat/models/message.dart';
import 'package:whale_mobile/features/chat/models/persona.dart';
import 'package:whale_mobile/features/chat/models/run.dart';
import 'package:whale_mobile/features/chat/services/link_chat_api.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

/// 内存假聊天面后端（widget 测试用；TestWidgetsFlutterBinding 拦真实 WS/HTTP）。
///
/// 与 FakeLinkServer 同脚本语义，但不出网：sendChat 后按脚本经
/// [events] 控制器推 run.* 事件；审批走内存表。
class FakeChatBackend implements ChatBackend {
  FakeChatBackend({this.connected = true});

  /// 连接开关（false 时控制器走 SSE 退路）。
  bool connected;

  final StreamController<LinkEvent> _events = StreamController<LinkEvent>.broadcast();
  final StreamController<LinkWelcome> _welcomes =
      StreamController<LinkWelcome>.broadcast();

  /// conversationId → 消息（正序，v3 形状）。
  final Map<String, List<ChatMessage>> store = {};

  /// 会话表。
  final Map<String, Conversation> conversations = {};

  /// 审批表（id → Approval）。
  final Map<String, Approval> approvals = {};

  /// 发送计数（断言不重发）。
  int sendCalls = 0;

  /// chat.send 强制错误（null=正常）。
  LinkError? sendError;

  /// 回复文本前缀（默认 '回复：<text>'）。
  String Function(String text)? replyBuilder;

  int _runCounter = 0;
  int _msgCounter = 0;

  @override
  Stream<LinkEvent> get events => _events.stream;

  @override
  Stream<LinkWelcome> get welcomes => _welcomes.stream;

  @override
  LinkState get state =>
      connected ? LinkState.connected : LinkState.disconnected;

  @override
  bool get isConnected => connected;

  /// 注入一个事件（如 approval.requested）。与真实服务端同语义：
  /// approval.requested 先落审批表再推事件。
  void emit(String topic, Map<String, dynamic> data) {
    if (topic == 'approval.requested') {
      approvals[data['id'] as String] = Approval.fromJson(data);
    }
    _events.add(LinkEvent(
      topic: topic,
      data: data,
      eventId: 'evt_${DateTime.now().microsecondsSinceEpoch}',
      ts: DateTime.now().millisecondsSinceEpoch,
    ));
  }

  /// 注入 welcome（isReconnect=true 触发断线补齐）。
  void emitReconnect() {
    _welcomes.add(const LinkWelcome(
      clientId: 'cli_fake',
      protocol: '1.0',
      serverName: 'fake',
      serverVersion: '0',
      methods: [],
      isReconnect: true,
    ));
  }

  @override
  Future<ChatSendResult> sendChat({
    required String text,
    String? conversationId,
    List<String> images = const [],
    String? personaId,
    String? modelRole,
    bool remember = true,
  }) async {
    sendCalls++;
    final err = sendError;
    if (err != null) throw err;
    final convId = conversationId ?? 'mobile:fake';
    final runId = 'run_fake_${++_runCounter}';
    conversations.putIfAbsent(
        convId,
        () => Conversation(
            conversationId: convId,
            channel: 'mobile',
            createdAt: DateTime.now().millisecondsSinceEpoch,
            updatedAt: DateTime.now().millisecondsSinceEpoch));
    store.putIfAbsent(convId, () => []).add(ChatMessage(
          messageId: 'msg_${++_msgCounter}',
          role: MessageRole.user,
          text: text,
          createdAt: DateTime.now().millisecondsSinceEpoch,
          runId: runId,
          imageCount: images.length,
        ));
    final reply = (replyBuilder ?? (t) => '回复：$t')(text);
    // 事件异步推（started → think 步 → delta×2 → completed）
    scheduleMicrotask(() async {
      await Future<void>.delayed(const Duration(milliseconds: 5));
      _emitRun('run.started', runId, convId, input: text);
      _events.add(LinkEvent(topic: 'run.step', data: {
        'step': {
          'id': 'step_$runId',
          'run_id': runId,
          'seq': 1,
          'kind': 'think',
          'title': '决定直接回答',
          'status': 'done',
          'input': <String, dynamic>{},
          'output': {'decision': 'reply'},
          'started_at': DateTime.now().millisecondsSinceEpoch,
          'ended_at': DateTime.now().millisecondsSinceEpoch,
        },
      }));
      _events.add(LinkEvent(topic: 'run.delta', data: {
        'run_id': runId,
        'conversation_id': convId,
        'delta': reply.substring(0, 3),
      }));
      _events.add(LinkEvent(topic: 'run.delta', data: {
        'run_id': runId,
        'conversation_id': convId,
        'delta': reply.substring(3),
      }));
      _emitRun('run.completed', runId, convId, output: reply);
      store[convId]!.add(ChatMessage(
        messageId: 'msg_${++_msgCounter}',
        role: MessageRole.assistant,
        text: reply,
        createdAt: DateTime.now().millisecondsSinceEpoch,
        runId: runId,
      ));
    });
    return ChatSendResult(conversationId: convId, runId: runId, accepted: true);
  }

  void _emitRun(String topic, String runId, String convId,
      {String input = '', String output = ''}) {
    _events.add(LinkEvent(topic: topic, data: {
      'run': {
        'id': runId,
        'conversation_id': convId,
        'mode': 'chat',
        'status': topic == 'run.completed' ? 'completed' : 'running',
        'input': input,
        'output': output,
        'error': '',
        'channel': 'mobile',
        'model': 'fake',
        'created_at': DateTime.now().millisecondsSinceEpoch,
        'ended_at': null,
        'stats': <String, dynamic>{},
      },
    }));
  }

  @override
  Future<bool> cancelChat(String runId) async => true;

  @override
  Future<List<Conversation>> listConversations(
      {int limit = 50, String? channel}) async {
    var list = conversations.values.toList();
    if (channel != null) {
      list = list.where((c) => c.channel == channel).toList();
    }
    list.sort((a, b) => (b.updatedAt ?? 0).compareTo(a.updatedAt ?? 0));
    return list.take(limit).toList();
  }

  @override
  Future<ConversationMessages> conversationMessages(
    String conversationId, {
    int limit = 50,
    int? before,
  }) async {
    var msgs = store[conversationId] ?? const <ChatMessage>[];
    if (before != null) {
      msgs = msgs.where((m) => (m.createdAt ?? 0) < before).toList();
    }
    final sorted = List.of(msgs)
      ..sort((a, b) => (b.createdAt ?? 0).compareTo(a.createdAt ?? 0));
    final page = sorted.take(limit).toList().reversed.toList();
    return ConversationMessages(
      conversation: conversations[conversationId],
      messages: page,
    );
  }

  @override
  Future<Conversation> updateConversation(String id,
      {String? title, String? personaId}) async {
    final c = conversations[id];
    if (c == null) {
      throw const LinkError(LinkErrorCode.notFound, 'conversation');
    }
    final updated = Conversation(
      conversationId: c.conversationId,
      title: title ?? c.title,
      channel: c.channel,
      personaId: personaId ?? c.personaId,
      createdAt: c.createdAt,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
      turns: c.turns,
      previewRole: c.previewRole,
      previewText: c.previewText,
    );
    conversations[id] = updated;
    return updated;
  }

  @override
  Future<void> deleteConversation(String id) async {
    conversations.remove(id);
    store.remove(id);
  }

  @override
  Future<RunDetail> getRun(String runId) async {
    throw const LinkError(LinkErrorCode.notFound, 'run');
  }

  @override
  Future<List<RunRecord>> listRuns(
      {int limit = 50, String? status, String? mode}) async =>
      const [];

  @override
  Future<List<Approval>> listApprovals({String? status}) async {
    var list = approvals.values.toList();
    if (status != null) {
      list = list.where((a) => a.status == status).toList();
    }
    return list;
  }

  @override
  Future<Approval> resolveApproval(String id, bool approve,
      {String note = ''}) async {
    final a = approvals[id];
    if (a == null) {
      throw const LinkError(LinkErrorCode.notFound, 'approval');
    }
    if (a.status != 'pending') {
      throw const LinkError(LinkErrorCode.conflict, 'already resolved');
    }
    final resolved = Approval(
      id: a.id,
      runId: a.runId,
      capability: a.capability,
      args: a.args,
      reason: a.reason,
      risk: a.risk,
      status: approve ? 'approved' : 'denied',
      createdAt: a.createdAt,
      resolvedAt: DateTime.now().millisecondsSinceEpoch,
      resolvedBy: 'mobile',
      note: note,
      title: a.title,
      provider: a.provider,
    );
    approvals[id] = resolved;
    emit('approval.resolved', {
      'id': resolved.id,
      'run_id': resolved.runId,
      'capability': resolved.capability,
      'args': resolved.args,
      'reason': resolved.reason,
      'risk': resolved.risk,
      'status': resolved.status,
      'created_at': resolved.createdAt,
      'resolved_at': resolved.resolvedAt,
      'resolved_by': resolved.resolvedBy,
      'note': resolved.note,
    });
    return resolved;
  }

  @override
  Future<List<Persona>> listPersonas() async => const [
        Persona(id: 'default', name: '鲸鱼', builtin: true),
        Persona(id: 'p_custom', name: '助手', tagline: '自定义'),
      ];

  @override
  Future<List<LinkEvent>> queryEvents({
    String pattern = '*',
    int limit = 100,
    String? before,
  }) async =>
      const [];

  @override
  Future<Map<String, dynamic>> systemStatus() async => const {'version': 'fake'};

  void dispose() {
    _events.close();
    _welcomes.close();
  }
}
