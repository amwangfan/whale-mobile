/// 会话模型（Phase 4；Core GET /api/v2/conversations 条目）。
library;

/// 会话列表条目。
class Conversation {
  const Conversation({
    required this.conversationId,
    this.personaId,
    this.updatedAt,
    this.lastMessageRole,
    this.lastMessageText,
    this.lastMessageAt,
    this.messageCount = 0,
  });

  /// 会话 ID（如 `mobile:owner`）。
  final String conversationId;

  /// 当前绑定角色（可能为 null = 默认角色）。
  final String? personaId;

  /// Unix 毫秒；纯历史会话可能为 null。
  final int? updatedAt;

  /// 预览（last_message：role/text/at）。
  final String? lastMessageRole;
  final String? lastMessageText;
  final int? lastMessageAt;
  final int messageCount;

  factory Conversation.fromJson(Map<String, dynamic> json) {
    final last = json['last_message'];
    return Conversation(
      conversationId: json['conversation_id'] as String? ?? '',
      personaId: json['persona_id'] as String?,
      updatedAt: (json['updated_at'] as num?)?.toInt(),
      lastMessageRole: last is Map ? last['role'] as String? : null,
      lastMessageText: last is Map ? last['text'] as String? : null,
      lastMessageAt: last is Map ? (last['at'] as num?)?.toInt() : null,
      messageCount: (json['message_count'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 会话分页结果（cursor 不透明）。
class ConversationPage {
  const ConversationPage({required this.conversations, this.nextCursor});

  final List<Conversation> conversations;
  final String? nextCursor;

  bool get hasMore => nextCursor != null;
}
