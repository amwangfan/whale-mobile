/// 聊天消息模型（Phase 4）。
///
/// 对应 Core `GET /api/v2/conversations/{id}/messages` 返回的消息条目，
/// 以及本地流式期间的进行中消息（message_id 尚未由服务端分配）。
library;

import 'attachment.dart';

/// 消息角色。
enum MessageRole {
  user,
  assistant,
  system;

  static MessageRole fromWire(String? value) {
    switch (value) {
      case 'user':
        return MessageRole.user;
      case 'assistant':
        return MessageRole.assistant;
      default:
        return MessageRole.system;
    }
  }

  String get wire => name;
}

/// 本地发送状态（UI 态，与服务端 status 区分）。
enum MessageSendState {
  /// 正常落库消息（历史或已完成）。
  settled,

  /// 乐观插入、等待/进行流式回复。
  streaming,

  /// 流式断线或 error 事件：显式标记失败，不静默重试、不重发。
  failed,
}

/// 流式期间的一次工具事件（规划 §5.5：tool.started/progress/result）。
class ToolEvent {
  const ToolEvent({
    required this.kind,
    this.toolCallId,
    this.toolName,
    this.summary,
    this.progress,
    this.receipt,
  });

  /// started / progress / result。
  final String kind;
  final String? toolCallId;
  final String? toolName;

  /// 结果摘要或进度说明。
  final String? summary;

  /// 0..100 进度（tool.progress 可携带）。
  final int? progress;

  /// tool.result 的原始回执文本。
  final String? receipt;
}

/// 流式期间由 `attachment.created` 事件产生的附件（生成图/视频等）。
class ChatMessage {
  ChatMessage({
    this.messageId,
    required this.role,
    required this.text,
    this.createdAt,
    this.clientMessageId,
    this.attachments = const [],
    this.status,
    this.taskId,
    this.via,
    this.sendState = MessageSendState.settled,
    this.toolEvents = const [],
    this.errorMessage,
  });

  /// 服务端 message_id（evt_ULID）；本地乐观插入时为 null。
  final String? messageId;
  final MessageRole role;
  final String text;

  /// Unix 毫秒；历史回落（via=history）时可为 null。
  final int? createdAt;

  /// 幂等键（^[0-9A-Za-z:_-]{8,128}$）。
  final String? clientMessageId;
  final List<AttachmentRef> attachments;

  /// 服务端回执状态（done.status，如 ok / unavailable）。
  final String? status;
  final String? taskId;

  /// 历史回落标记（via=history 表示来自 chat:history，无 id/时间戳）。
  final String? via;

  /// 本地 UI 发送状态。
  final MessageSendState sendState;

  /// 流式过程累积的工具事件（独立状态卡片数据源，不混入正文）。
  final List<ToolEvent> toolEvents;

  /// 失败原因（sendState=failed 时展示）。
  final String? errorMessage;

  bool get isUser => role == MessageRole.user;
  bool get isStreaming => sendState == MessageSendState.streaming;
  bool get isFailed => sendState == MessageSendState.failed;

  factory ChatMessage.fromJson(Map<String, dynamic> json) {
    final atts = (json['attachments'] as List?)
            ?.whereType<Map>()
            .map((m) => AttachmentRef.fromJson(m.cast<String, dynamic>()))
            .toList() ??
        const <AttachmentRef>[];
    return ChatMessage(
      messageId: json['message_id'] as String?,
      role: MessageRole.fromWire(json['role'] as String?),
      text: json['text'] as String? ?? '',
      createdAt: (json['created_at'] as num?)?.toInt(),
      clientMessageId: json['client_message_id'] as String?,
      attachments: atts,
      status: json['status'] as String?,
      taskId: json['task_id'] as String?,
      via: json['via'] as String?,
    );
  }

  ChatMessage copyWith({
    String? messageId,
    String? text,
    int? createdAt,
    String? status,
    String? taskId,
    MessageSendState? sendState,
    List<ToolEvent>? toolEvents,
    List<AttachmentRef>? attachments,
    String? errorMessage,
  }) {
    return ChatMessage(
      messageId: messageId ?? this.messageId,
      role: role,
      text: text ?? this.text,
      createdAt: createdAt ?? this.createdAt,
      clientMessageId: clientMessageId,
      attachments: attachments ?? this.attachments,
      status: status ?? this.status,
      taskId: taskId ?? this.taskId,
      via: via,
      sendState: sendState ?? this.sendState,
      toolEvents: toolEvents ?? this.toolEvents,
      errorMessage: errorMessage,
    );
  }
}

/// 消息分页结果（cursor 不透明，由 Core 生成）。
class MessagePage {
  const MessagePage({required this.messages, this.nextCursor});

  /// 新→旧顺序（Core 倒序返回）。
  final List<ChatMessage> messages;

  /// 继续向更旧翻页的游标；null 表示没有更多。
  final String? nextCursor;

  bool get hasMore => nextCursor != null;
}
