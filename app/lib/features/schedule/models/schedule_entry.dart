/// 日程溯源契约模型（云端 /v1/schedule/* 最终版，2026-11-19；mock 先行，HTTP 后接零 UI 改动）。
library;

/// 来源引用：本条日程由哪条聊天消息提取（分屏高亮 + 连线用）。
class ScheduleSource {
  const ScheduleSource({
    required this.conversation,
    required this.messageId,
    required this.at,
    required this.excerpt,
  });

  final String conversation;
  final String messageId;
  final int at; // epoch 秒
  final String excerpt;

  factory ScheduleSource.fromMap(Map<String, dynamic> m) => ScheduleSource(
        conversation: m['conversation'] as String? ?? '',
        messageId: m['messageId'] as String? ?? '',
        at: (m['at'] as num?)?.toInt() ?? 0,
        excerpt: m['excerpt'] as String? ?? '',
      );
}

/// 候选（state=pending）与正式日程（state=added）同形（契约：/candidates 与 /list 条目同形）。
class ScheduleEntry {
  const ScheduleEntry({
    required this.id,
    required this.title,
    required this.date,
    required this.state,
    required this.createdAt,
    this.time,
    this.source,
  });

  final String id;
  final String title;
  final String date; // ISO yyyy-MM-dd
  final String? time; // HH:mm，可空
  final ScheduleSource? source;
  final String state; // pending / added
  final int createdAt;

  factory ScheduleEntry.fromMap(Map<String, dynamic> m) => ScheduleEntry(
        id: m['id'] as String? ?? '',
        title: m['title'] as String? ?? '',
        date: m['date'] as String? ?? '',
        time: m['time'] as String?,
        source: m['source'] is Map<String, dynamic>
            ? ScheduleSource.fromMap(m['source'] as Map<String, dynamic>)
            : null,
        state: m['state'] as String? ?? 'pending',
        createdAt: (m['createdAt'] as num?)?.toInt() ?? 0,
      );

  /// 去重键（用户令：与日历视图合并渲染时同事件不双卡）。
  /// 优先来源消息身份；无来源时退化为「归一化标题 + 日期」。
  String get dedupeKey {
    final m = source?.messageId ?? '';
    if (m.isNotEmpty) return 'm:$m';
    return 't:${title.trim().toLowerCase().replaceAll(RegExp(r'\s+'), '')}|$date';
  }
}

/// 来源上下文消息（GET /v1/schedule/source?conversation&messageId → ±10 条；owner-only 隐私门）。
class SourceMessage {
  const SourceMessage({
    required this.messageId,
    required this.at,
    required this.sender,
    required this.text,
  });

  final String messageId;
  final int at;
  final String sender;
  final String text;

  factory SourceMessage.fromMap(Map<String, dynamic> m) => SourceMessage(
        messageId: m['messageId'] as String? ?? '',
        at: (m['at'] as num?)?.toInt() ?? 0,
        sender: m['sender'] as String? ?? '',
        text: m['text'] as String? ?? '',
      );
}
