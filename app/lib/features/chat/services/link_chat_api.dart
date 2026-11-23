import 'dart:async';

import '../../../transport/link/link_client.dart';
import '../models/approval.dart';
import '../models/conversation.dart';
import '../models/message.dart';
import '../models/persona.dart';
import '../models/run.dart';

/// 聊天面后端抽象（v3 方法面 + 事件流）。
///
/// 生产实现 = [LinkChatApi]（Link RPC over WS）；widget 测试注入内存假实现
/// （TestWidgetsFlutterBinding 的 HttpOverrides 会拦真实 WS/HTTP）。
abstract class ChatBackend {
  /// 事件流（run.*/approval.*/notify/task.* 由 hello 订阅）。
  Stream<LinkEvent> get events;

  /// welcome 流（isReconnect=true 时上层做断线补齐）。
  Stream<LinkWelcome> get welcomes;

  LinkState get state;
  bool get isConnected;

  /// chat.send（wait:false 立返 run_id；回复走事件流）。
  Future<ChatSendResult> sendChat({
    required String text,
    String? conversationId,
    List<String> images = const [],
    String? personaId,
    String? modelRole,
    bool remember = true,
  });

  /// chat.cancel：打断在飞 run。
  Future<bool> cancelChat(String runId);

  /// conversations.list（updated_at 倒序）。
  Future<List<Conversation>> listConversations({int limit = 50, String? channel});

  /// conversations.messages（before=毫秒 ts 分页；返回旧→新正序）。
  Future<ConversationMessages> conversationMessages(
    String conversationId, {
    int limit = 50,
    int? before,
  });

  /// conversations.update（改标题/绑人格）。
  Future<Conversation> updateConversation(String id,
      {String? title, String? personaId});

  /// conversations.delete。
  Future<void> deleteConversation(String id);

  /// runs.get（全步骤+子 run）。
  Future<RunDetail> getRun(String runId);

  /// runs.list。
  Future<List<RunRecord>> listRuns({int limit = 50, String? status, String? mode});

  /// approvals.list（status='pending' 待决；null 全部）。
  Future<List<Approval>> listApprovals({String? status});

  /// approvals.resolve（一次性；重复核销 conflict）。
  Future<Approval> resolveApproval(String id, bool approve, {String note = ''});

  /// personas.list。
  Future<List<Persona>> listPersonas();

  /// events.query（断线补齐；before=event_id）。
  Future<List<LinkEvent>> queryEvents({
    String pattern = '*',
    int limit = 100,
    String? before,
  });

  /// system.status。
  Future<Map<String, dynamic>> systemStatus();
}

/// 聊天面高层 RPC 封装（v3 统一主干方法面；契约 §1 逐项映射）。
///
/// 全部方法走 LinkClient.request（corr 配对 + 130s 自超时）；
/// **写路径**（chat.send/cancel、approvals.resolve、conversations.update/delete）
/// 单次发送、绝不自动重放；**读路径**（list/get/query）仅在服务端明确
/// `retryable:true` 时自动重试一次（纪律 §6：`timeout` 绝不重放）。
class LinkChatApi implements ChatBackend {
  LinkChatApi(this._link);

  final LinkClient _link;

  /// 事件流（run.*/approval.*/notify/task.* 由 hello 订阅）。
  @override
  Stream<LinkEvent> get events => _link.events;

  /// welcome 流（isReconnect=true 时上层做断线补齐）。
  @override
  Stream<LinkWelcome> get welcomes => _link.welcomes;

  @override
  LinkState get state => _link.state;
  @override
  bool get isConnected => _link.isConnected;

  // ---------------------------------------------------------------- 读写策略

  /// 读路径：retryable:true 才自动重试（最多 1 次，间隔 500ms）；
  /// timeout/结果未知一律不重放。
  Future<Map<String, dynamic>> _read(String method,
      [Map<String, dynamic>? params]) async {
    try {
      return await _link.request(method, params);
    } on LinkError catch (e) {
      if (e.retryable && e.code != LinkErrorCode.timeout) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
        return _link.request(method, params);
      }
      rethrow;
    }
  }

  // ---------------------------------------------------------------- 聊天

  /// chat.send（wait:false 立返 run_id；回复走事件流）。
  Future<ChatSendResult> sendChat({
    required String text,
    String? conversationId,
    List<String> images = const [],
    String? personaId,
    String? modelRole,
    bool remember = true,
  }) async {
    final result = await _link.request('chat.send', <String, dynamic>{
      'text': text,
      if (conversationId != null) 'conversation_id': conversationId,
      if (images.isNotEmpty) 'images': images,
      if (personaId != null) 'persona_id': personaId,
      'wait': false,
      if (modelRole != null) 'model_role': modelRole,
      'remember': remember,
    });
    return ChatSendResult(
      conversationId: result['conversation_id'] as String? ?? '',
      runId: result['run_id'] as String? ?? '',
      accepted: result['accepted'] as bool? ?? false,
    );
  }

  /// chat.cancel：打断在飞 run（run 落 cancelled 终态）。
  @override
  Future<bool> cancelChat(String runId) async {
    final result =
        await _link.request('chat.cancel', <String, dynamic>{'run_id': runId});
    return result['cancelled'] as bool? ?? false;
  }

  // ---------------------------------------------------------------- 历史

  /// conversations.list {limit, channel?}（updated_at 倒序）。
  @override
  Future<List<Conversation>> listConversations({int limit = 50, String? channel}) async {
    final result = await _read('conversations.list', <String, dynamic>{
      'limit': limit,
      if (channel != null) 'channel': channel,
    });
    return (result['conversations'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => Conversation.fromJson(m.cast<String, dynamic>()))
        .toList();
  }

  /// conversations.messages {id, limit, before?}：before=毫秒 ts 分页
  /// （返回旧→新正序）。返回会话本体 + 消息页。
  @override
  Future<ConversationMessages> conversationMessages(
    String conversationId, {
    int limit = 50,
    int? before,
  }) async {
    final result = await _read('conversations.messages', <String, dynamic>{
      'id': conversationId,
      'limit': limit,
      if (before != null) 'before': before,
    });
    final conv = (result['conversation'] as Map?)?.cast<String, dynamic>();
    final messages = (result['messages'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => ChatMessage.fromJson(m.cast<String, dynamic>()))
        .toList();
    return ConversationMessages(
      conversation:
          conv != null ? Conversation.fromJson(conv) : null,
      messages: messages, // core 返回正序（旧→新）
    );
  }

  /// conversations.update：改标题/绑人格（mobile 白名单含 conversations.*）。
  @override
  Future<Conversation> updateConversation(String id,
      {String? title, String? personaId}) async {
    final result = await _link.request('conversations.update', <String, dynamic>{
      'id': id,
      if (title != null) 'title': title,
      if (personaId != null) 'persona_id': personaId,
    });
    return Conversation.fromJson(result);
  }

  /// conversations.delete。
  @override
  Future<void> deleteConversation(String id) =>
      _link.request('conversations.delete', <String, dynamic>{'id': id});

  // ---------------------------------------------------------------- 观测

  /// runs.get：全步骤 + 子 run。
  @override
  Future<RunDetail> getRun(String runId) async {
    final result = await _read('runs.get', <String, dynamic>{'id': runId});
    return RunDetail(
      run: RunRecord.fromJson(
          (result['run'] as Map?)?.cast<String, dynamic>() ?? const {}),
      steps: (result['steps'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => RunStep.fromJson(m.cast<String, dynamic>()))
          .toList(),
      children: (result['children'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => RunRecord.fromJson(m.cast<String, dynamic>()))
          .toList(),
    );
  }

  /// runs.list。
  @override
  Future<List<RunRecord>> listRuns(
      {int limit = 50, String? status, String? mode}) async {
    final result = await _read('runs.list', <String, dynamic>{
      'limit': limit,
      if (status != null) 'status': status,
      if (mode != null) 'mode': mode,
    });
    return (result['runs'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => RunRecord.fromJson(m.cast<String, dynamic>()))
        .toList();
  }

  // ---------------------------------------------------------------- 审批

  /// approvals.list {status}：传 `'pending'` 取待决；传 null 取全部（历史列表）。
  /// （core 默认 pending；显式 null 需在 params 里带 JSON null。）
  @override
  Future<List<Approval>> listApprovals({String? status}) async {
    final result = await _read('approvals.list', <String, dynamic>{
      'status': status,
    });
    return (result['approvals'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => Approval.fromJson(m.cast<String, dynamic>()))
        .toList();
  }

  /// approvals.resolve {id, approve, note?}：一次性；重复核销服务端回 conflict。
  @override
  Future<Approval> resolveApproval(String id, bool approve,
      {String note = ''}) async {
    final result = await _link.request('approvals.resolve', <String, dynamic>{
      'id': id,
      'approve': approve,
      'note': note,
    });
    return Approval.fromJson(result);
  }

  // ---------------------------------------------------------------- 其他

  /// personas.list（persona_id 绑定在 conversations.update）。
  @override
  Future<List<Persona>> listPersonas() async {
    final result = await _read('personas.list');
    return (result['personas'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => Persona.fromJson(m.cast<String, dynamic>()))
        .toList();
  }

  /// events.query {pattern, limit, before?}：断线补齐事件用
  /// （before=event_id，ULID 字典序≈时间序）。
  @override
  Future<List<LinkEvent>> queryEvents({
    String pattern = '*',
    int limit = 100,
    String? before,
  }) async {
    final result = await _read('events.query', <String, dynamic>{
      'pattern': pattern,
      'limit': limit,
      if (before != null) 'before': before,
    });
    return (result['events'] as List? ?? const [])
        .whereType<Map>()
        .map((m) {
          final e = m.cast<String, dynamic>();
          return LinkEvent(
            topic: e['topic'] as String? ?? '',
            data: (e['data'] as Map?)?.cast<String, dynamic>() ?? const {},
            eventId: e['id'] as String?,
            ts: (e['ts'] as num?)?.toInt(),
          );
        })
        .toList();
  }

  /// system.status。
  @override
  Future<Map<String, dynamic>> systemStatus() => _read('system.status');
}

/// chat.send(wait:false) 立返。
class ChatSendResult {
  const ChatSendResult({
    required this.conversationId,
    required this.runId,
    required this.accepted,
  });

  final String conversationId;
  final String runId;
  final bool accepted;
}

/// conversations.messages 返回包。
class ConversationMessages {
  const ConversationMessages({required this.conversation, required this.messages});

  final Conversation? conversation;

  /// 旧→新正序（core messages() 语义）。
  final List<ChatMessage> messages;
}

/// runs.get 返回包。
class RunDetail {
  const RunDetail({required this.run, required this.steps, required this.children});

  final RunRecord run;
  final List<RunStep> steps;
  final List<RunRecord> children;
}
