import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/attachment.dart';
import '../models/message.dart';
import '../services/chat_api.dart';
import '../services/chat_stream.dart';

/// 聊天控制器：消息列表状态 + 流式追加 + 乐观插入 + client_message_id 幂等。
///
/// 约定：
/// - [messages] 新→旧（index 0 最新），与 Core 分页顺序一致，配合
///   `ListView(reverse: true)`。
/// - 网络异常全部显式可见（[error] + 失败消息标记），不静默重试；
///   流式断线（aborted）把该轮标记失败，不重发（§5.7 验收）。
/// - client_message_id 在会话内唯一（§3 idempotency_key 同款 pattern），
///   历史对齐时按它去重，不重复插入（§5.7）。
class ChatController extends ChangeNotifier {
  ChatController({
    required this.conversationId,
    ChatApi? api,
    ChatStreamClient? streamClient,
  })  : _api = api ?? ChatApi(),
        _stream = streamClient ?? ChatStreamClient();

  final String conversationId;
  final ChatApi _api;
  final ChatStreamClient _stream;

  /// 消息列表（新→旧）。
  final List<ChatMessage> messages = <ChatMessage>[];

  bool loadingInitial = false;
  bool loadingOlder = false;
  bool sending = false;
  String? error;
  String? _nextCursor;
  String currentPhase = '';

  /// 流式过程中累积的生成附件（attachment.created）。
  final List<AttachmentRef> createdAttachments = <AttachmentRef>[];

  int _idemCounter = 0;

  bool get hasMore => _nextCursor != null;

  /// 生成幂等键：`^[0-9A-Za-z:_-]{8,128}$`（§3）。
  String _newClientMessageId() {
    _idemCounter += 1;
    return 'mob_${DateTime.now().millisecondsSinceEpoch}_$_idemCounter';
  }

  // ---------------------------------------------------------------- 历史

  /// 首屏加载（失败显式置 [error]，UI 展示重试按钮）。
  Future<void> loadInitial() async {
    if (loadingInitial) return;
    loadingInitial = true;
    error = null;
    notifyListeners();
    try {
      final page = await _api.listMessages(conversationId, limit: 50);
      messages
        ..clear()
        ..addAll(page.messages);
      _nextCursor = page.nextCursor;
    } on ChatApiException catch (e) {
      error = '加载历史失败：$e';
    }
    loadingInitial = false;
    notifyListeners();
  }

  /// 向更旧翻页（游标在 Core 侧不透明；重复调用安全——按 cursor 幂等）。
  Future<void> loadOlder() async {
    if (loadingOlder || loadingInitial || _nextCursor == null) return;
    loadingOlder = true;
    notifyListeners();
    try {
      final page = await _api.listMessages(
        conversationId,
        cursor: _nextCursor,
        limit: 50,
      );
      _mergeHistory(page.messages);
      _nextCursor = page.nextCursor;
    } on ChatApiException catch (e) {
      error = '加载更多失败：$e';
    }
    loadingOlder = false;
    notifyListeners();
  }

  /// 历史合并：按 message_id / client_message_id 去重（§5.7 不重复插入）。
  void _mergeHistory(List<ChatMessage> older) {
    final seenIds = messages
        .map((m) => m.messageId)
        .whereType<String>()
        .toSet();
    final seenClientIds = messages
        .map((m) => m.clientMessageId)
        .whereType<String>()
        .toSet();
    for (final m in older) {
      if (m.messageId != null && seenIds.contains(m.messageId)) continue;
      if (m.clientMessageId != null && seenClientIds.contains(m.clientMessageId)) {
        continue;
      }
      messages.add(m); // older 为更旧的消息，追加到尾部（尾部=最旧）
    }
  }

  /// 刷新（发送完成后/下拉刷新）：重取第一页并按幂等键对齐本地乐观消息。
  Future<void> refresh() async {
    try {
      final page = await _api.listMessages(conversationId, limit: 50);
      // 保留仍在进行中的本地消息（流式中的不应被历史覆盖）
      final inflight = messages
          .where((m) => m.sendState != MessageSendState.settled)
          .toList();
      messages
        ..clear()
        ..addAll(inflight);
      _mergeHistory(page.messages);
      // messages 可能不再保持新→旧：inflight 在最前，历史新→旧，满足顺序
      _nextCursor = page.nextCursor;
      error = null;
    } on ChatApiException catch (e) {
      error = '刷新失败：$e';
    }
    notifyListeners();
  }

  // ---------------------------------------------------------------- 发送

  /// 发送文字（可带附件）。乐观插入用户消息 + 助手占位，随后消费 SSE。
  ///
  /// 断线 / error 事件 → 该轮显式标记失败（不重发）。
  Future<void> send(String text,
      {List<AttachmentRef> attachments = const []}) async {
    if (sending) return;
    final trimmed = text.trim();
    if (trimmed.isEmpty && attachments.isEmpty) return;

    sending = true;
    error = null;
    currentPhase = '';
    final clientMessageId = _newClientMessageId();

    // 乐观插入：用户消息（新→旧列表头）
    final userMsg = ChatMessage(
      role: MessageRole.user,
      text: trimmed,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      clientMessageId: clientMessageId,
      attachments: attachments,
      sendState: MessageSendState.settled,
    );
    // 助手占位：流式目标（可变：流式过程反复替换内容）
    var assistantMsg = ChatMessage(
      role: MessageRole.assistant,
      text: '',
      createdAt: DateTime.now().millisecondsSinceEpoch,
      sendState: MessageSendState.streaming,
    );
    messages.insert(0, assistantMsg);
    messages.insert(1, userMsg);
    notifyListeners();

    final buffer = StringBuffer();
    final toolEvents = <ToolEvent>[];
    final created = <AttachmentRef>[];

    void updateAssistant({MessageSendState? state, String? errMsg}) {
      final i = messages.indexOf(assistantMsg);
      if (i < 0) return;
      messages[i] = assistantMsg = assistantMsg.copyWith(
        text: buffer.toString(),
        sendState: state,
        toolEvents: List<ToolEvent>.unmodifiable(toolEvents),
        attachments: created.isEmpty ? null : List.unmodifiable(created),
        errorMessage: errMsg,
      );
      notifyListeners();
    }

    Stream<ChatStreamEvent> events;
    try {
      events = _stream.streamReply(
        conversationId: conversationId,
        clientMessageId: clientMessageId,
        text: trimmed,
        attachments: attachments,
      );
    } on ChatStreamException catch (e) {
      updateErr(e);
      return;
    }

    try {
      await for (final ev in events) {
        switch (ev.type) {
          case 'phase':
            currentPhase = ev.phase ?? '';
            notifyListeners();
            break;
          case 'delta':
            buffer.write(ev.text ?? '');
            updateAssistant();
            break;
          case 'tool.started':
            toolEvents.add(ToolEvent(
              kind: 'started',
              toolCallId: ev.toolCallId,
              toolName: ev.toolName,
            ));
            updateAssistant();
            break;
          case 'tool.progress':
            toolEvents.add(ToolEvent(
              kind: 'progress',
              toolCallId: ev.toolCallId,
              summary: ev.summary,
              progress: ev.progress,
            ));
            updateAssistant();
            break;
          case 'tool.result':
            toolEvents.add(ToolEvent(
              kind: 'result',
              toolCallId: ev.toolCallId,
              toolName: ev.toolName,
              summary: ev.summary,
              receipt: ev.receipt,
            ));
            updateAssistant();
            break;
          case 'attachment.created':
            if (ev.attachment != null) {
              created.add(ev.attachment!);
              createdAttachments.add(ev.attachment!);
              updateAssistant();
            }
            break;
          case 'done':
            final i = messages.indexOf(assistantMsg);
            if (i >= 0) {
              messages[i] = assistantMsg = ChatMessage(
                messageId: ev.assistantMessageId,
                role: MessageRole.assistant,
                text: buffer.isNotEmpty ? buffer.toString() : (ev.reply ?? ''),
                createdAt: DateTime.now().millisecondsSinceEpoch,
                status: ev.status,
                taskId: ev.taskId,
                sendState: MessageSendState.settled,
                toolEvents: List<ToolEvent>.unmodifiable(toolEvents),
                attachments: List<AttachmentRef>.unmodifiable(created),
              );
            }
            // 用户消息回填服务端 message_id（幂等对齐的证据）
            final ui = messages.indexOf(userMsg);
            if (ui >= 0 && ev.userMessageId != null) {
              messages[ui] = userMsg.copyWith(messageId: ev.userMessageId);
            }
            currentPhase = '';
            break;
          case 'error':
            updateErr(ChatStreamException(
                ev.errorCode ?? 'error', '模型或编排失败：${ev.errorCode}'));
            return;
          case 'aborted':
            updateErr(ChatStreamException(
                'stream_disconnected', '连接中断：本轮回复未完成（已标记失败，未重发）'));
            return;
        }
      }
      sending = false;
      notifyListeners();
    } on ChatStreamException catch (e) {
      updateErr(e);
    }
  }

  /// 发送失败收尾：助手占位标记失败（红色可见），sending 复位。
  void updateErr(ChatStreamException e) {
    sending = false;
    error = e.toString();
    final i =
        messages.indexWhere((m) => m.sendState == MessageSendState.streaming);
    if (i >= 0) {
      messages[i] = messages[i].copyWith(
        sendState: MessageSendState.failed,
        errorMessage: e.toString(),
      );
    }
    currentPhase = '';
    notifyListeners();
  }

  @override
  void dispose() {
    _api.close();
    _stream.close();
    super.dispose();
  }
}
