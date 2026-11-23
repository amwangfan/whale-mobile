/// 聊天消息模型（v3 统一主干：`conversations.messages` 返回形状）。
///
/// Core 消息条目：`{id, conversation_id, role, content, run_id, ts, meta}`，
/// meta 携带：`images`（用户消息图片数）、`memories`（assistant 召回的记忆 id 列表）、
/// `tools`（assistant 工具调用 [{name,status}]）。
/// 本地流式期间的进行中消息（run_id 已分配、assistant 消息尚未落库）同模型表达。
library;

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

/// 本地发送状态（UI 态，与服务端 run.status 区分）。
enum MessageSendState {
  /// 正常落库消息（历史或已完成）。
  settled,

  /// 乐观插入、等待/进行流式回复（run 在飞）。
  streaming,

  /// 断线/失败：显式标记失败，不静默重试、不重发。
  failed,

  /// 主人打断（chat.cancel → run.cancelled）。
  cancelled,
}

/// 回合轨迹行（run.step 流的 UI 凝缩：kind + title + status）。
///
/// M1 可见性：记忆提示/工具轨迹手机可见、默认折叠成一行摘要，点击展开
/// 看到的就是这个列表。
class TraceLine {
  const TraceLine({
    this.stepId = '',
    required this.kind,
    required this.title,
    this.status = 'running',
    this.detail,
  });

  /// 对应 step.id（开始/结束两帧按它原位更新）。
  final String stepId;

  /// recall / think / tool / approval / reply。
  final String kind;
  final String title;

  /// running / done / error / denied（step.status）。
  final String status;

  /// 展开详情（step.output 摘要：tool 的 summary/error 等）。
  final String? detail;

  TraceLine copyWith({String? status, String? title, String? detail}) =>
      TraceLine(
        stepId: stepId,
        kind: kind,
        title: title ?? this.title,
        status: status ?? this.status,
        detail: detail ?? this.detail,
      );
}

/// 本地图片回声（发送中的 data: URL 图片；历史消息 core 只存数量不存内容）。
class LocalImage {
  const LocalImage({required this.dataUrl, required this.mime});

  final String dataUrl;
  final String mime;
}

class ChatMessage {
  ChatMessage({
    this.messageId,
    required this.role,
    required this.text,
    this.createdAt,
    this.runId,
    this.imageCount = 0,
    this.memoryIds = const [],
    this.tools = const [],
    this.localImages = const [],
    this.sendState = MessageSendState.settled,
    this.trace = const [],
    this.errorMessage,
  });

  /// 服务端 message id（msg_ULID）；本地乐观插入时为 null。
  final String? messageId;
  final MessageRole role;
  final String text;

  /// Unix 毫秒。
  final int? createdAt;

  /// 产生该消息的 run（assistant/system 消息携带）。
  final String? runId;

  /// 用户消息的图片数（meta.images；历史回声占位用）。
  final int imageCount;

  /// assistant 消息召回的记忆 id（meta.memories）。
  final List<String> memoryIds;

  /// assistant 消息的工具调用摘要（meta.tools：[{name,status}]）。
  final List<Map<String, dynamic>> tools;

  /// 本地回声图片（发送中/刚发完；core 不落图片本体）。
  final List<LocalImage> localImages;

  /// 本地 UI 发送状态。
  final MessageSendState sendState;

  /// 流式过程累积的回合轨迹（M1 折叠展开数据源；不持久化）。
  final List<TraceLine> trace;

  /// 失败原因（sendState=failed 时展示）。
  final String? errorMessage;

  bool get isUser => role == MessageRole.user;
  bool get isStreaming => sendState == MessageSendState.streaming;
  bool get isFailed => sendState == MessageSendState.failed;

  /// M1 折叠摘要行：「想起了 N 条 · 调用了 web.search」。
  String get traceSummary {
    final parts = <String>[];
    if (memoryIds.isNotEmpty) parts.add('想起了 ${memoryIds.length} 条');
    final toolNames = tools.isNotEmpty
        ? tools.map((t) => t['name'] as String? ?? '').where((s) => s.isNotEmpty)
        : trace.where((t) => t.kind == 'tool').map((t) => t.title);
    if (toolNames.isNotEmpty) parts.add('调用了 ${toolNames.join('、')}');
    return parts.join(' · ');
  }

  /// v3 历史消息解析（conversations.messages 条目）。
  factory ChatMessage.fromJson(Map<String, dynamic> json) {
    final meta = (json['meta'] as Map?)?.cast<String, dynamic>() ?? const {};
    return ChatMessage(
      messageId: json['id'] as String?,
      role: MessageRole.fromWire(json['role'] as String?),
      text: json['content'] as String? ?? '',
      createdAt: (json['ts'] as num?)?.toInt(),
      runId: json['run_id'] as String?,
      imageCount: (meta['images'] as num?)?.toInt() ?? 0,
      memoryIds:
          (meta['memories'] as List?)?.whereType<String>().toList() ?? const [],
      tools: (meta['tools'] as List?)
              ?.whereType<Map>()
              .map((m) => m.cast<String, dynamic>())
              .toList() ??
          const [],
    );
  }

  ChatMessage copyWith({
    String? messageId,
    String? text,
    int? createdAt,
    String? runId,
    int? imageCount,
    List<String>? memoryIds,
    List<Map<String, dynamic>>? tools,
    List<LocalImage>? localImages,
    MessageSendState? sendState,
    List<TraceLine>? trace,
    String? errorMessage,
  }) {
    return ChatMessage(
      messageId: messageId ?? this.messageId,
      role: role,
      text: text ?? this.text,
      createdAt: createdAt ?? this.createdAt,
      runId: runId ?? this.runId,
      imageCount: imageCount ?? this.imageCount,
      memoryIds: memoryIds ?? this.memoryIds,
      tools: tools ?? this.tools,
      localImages: localImages ?? this.localImages,
      sendState: sendState ?? this.sendState,
      trace: trace ?? this.trace,
      errorMessage: errorMessage,
    );
  }
}
