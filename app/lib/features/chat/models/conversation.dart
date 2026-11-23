/// 会话模型（v3 统一主干：`conversations.list` 条目）。
///
/// Core 形状：`{id, title, channel, peer, persona_id, created_at, updated_at,
/// turns, meta, preview:{role,text}|null}`。
library;

/// 会话列表条目。
class Conversation {
  const Conversation({
    required this.conversationId,
    this.title = '',
    this.channel = 'mobile',
    this.personaId,
    this.createdAt,
    this.updatedAt,
    this.turns = 0,
    this.previewRole,
    this.previewText,
  });

  /// 会话 ID（客户端生成持有，如 `mobile:<ULID>`）。
  final String conversationId;

  /// 标题（首条用户消息前 40 字自动落；可 conversations.update 改）。
  final String title;

  /// 渠道（mobile/console/glasses/qq…）。
  final String channel;

  /// 当前绑定角色（null = 默认角色）。
  final String? personaId;

  /// Unix 毫秒。
  final int? createdAt;
  final int? updatedAt;
  final int turns;

  /// 预览（preview：role/text，text 已截 80 字）。
  final String? previewRole;
  final String? previewText;

  /// 展示名：title 优先，退化会话 id 去渠道前缀。
  String get displayName {
    if (title.isNotEmpty) return title;
    final idx = conversationId.indexOf(':');
    return idx >= 0 ? conversationId.substring(idx + 1) : conversationId;
  }

  factory Conversation.fromJson(Map<String, dynamic> json) {
    final preview = (json['preview'] as Map?)?.cast<String, dynamic>();
    return Conversation(
      conversationId: json['id'] as String? ?? '',
      title: json['title'] as String? ?? '',
      channel: json['channel'] as String? ?? 'mobile',
      personaId: json['persona_id'] as String?,
      createdAt: (json['created_at'] as num?)?.toInt(),
      updatedAt: (json['updated_at'] as num?)?.toInt(),
      turns: (json['turns'] as num?)?.toInt() ?? 0,
      previewRole: preview?['role'] as String?,
      previewText: preview?['text'] as String?,
    );
  }
}
