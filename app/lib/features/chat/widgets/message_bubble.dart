import 'package:flutter/material.dart';

import '../models/attachment.dart';
import '../models/message.dart';
import 'image_message.dart';
import 'tool_result_card.dart';

/// 消息气泡。
///
/// 需要 [resolveAttachmentUrl] / [attachmentHeaders] 渲染图片附件
/// （由 chat_page 从 ChatApi 注入鉴权信息）。
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    this.currentPhase,
    this.resolveAttachmentUrl,
    this.attachmentHeaders = const {},
    this.onAttachmentTap,
  });

  final ChatMessage message;

  /// 当前流式阶段（仅当 message 是流式占位时有意义）。
  final String? currentPhase;

  /// attachment_id → 下载 URL。
  final String Function(String attachmentId)? resolveAttachmentUrl;
  final Map<String, String> attachmentHeaders;
  final ValueChanged<AttachmentRef>? onAttachmentTap;

  String _phaseLabel(String phase) {
    switch (phase) {
      case 'received':
        return '已接收，排队中…';
      case 'thinking':
        return '模型正在生成…';
      default:
        return phase;
    }
  }

  @override
  Widget build(BuildContext context) {
    final isUser = message.isUser;
    final scheme = Theme.of(context).colorScheme;
    final bubbleColor = message.isFailed
        ? scheme.errorContainer
        : isUser
            ? scheme.primaryContainer
            : scheme.surfaceContainerHighest;
    final textColor = message.isFailed
        ? scheme.onErrorContainer
        : isUser
            ? scheme.onPrimaryContainer
            : scheme.onSurface;

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        constraints: const BoxConstraints(maxWidth: 320),
        child: Column(
          crossAxisAlignment:
              isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          children: [
            // 工具调用状态卡片（独立于正文气泡，不伪装成文字）
            for (final ev in message.toolEvents)
              ToolResultCard(
                state: switch (ev.kind) {
                  'started' => ToolCardState.running,
                  'progress' => ToolCardState.running,
                  'result' => ToolResultCard.stateFromResult(ev.receipt),
                  _ => ToolCardState.done,
                },
                toolName: ev.toolName,
                summary: ev.summary,
                progress: ev.progress,
                receipt: ev.receipt,
              ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: bubbleColor,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 附件（图片内嵌，其余文件卡片）
                  for (final att in message.attachments) ...[
                    if (att.type == AttachmentType.image &&
                        resolveAttachmentUrl != null)
                      ImageMessage(
                        attachment: att,
                        imageUrl: resolveAttachmentUrl!(att.attachmentId),
                        headers: attachmentHeaders,
                        onTap: () => onAttachmentTap?.call(att),
                      )
                    else
                      _fileChip(context, att),
                    const SizedBox(height: 6),
                  ],
                  if (message.text.isNotEmpty)
                    SelectableText(
                      message.text,
                      style: TextStyle(color: textColor, fontSize: 15),
                    ),
                  // 流式中：阶段 + 光标
                  if (message.isStreaming)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SizedBox(
                            width: 12,
                            height: 12,
                            child: CircularProgressIndicator(
                              strokeWidth: 1.5,
                              color: textColor,
                            ),
                          ),
                          const SizedBox(width: 6),
                          Text(
                            currentPhase == null || currentPhase!.isEmpty
                                ? '生成中…'
                                : _phaseLabel(currentPhase!),
                            style: TextStyle(color: textColor, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                  // 失败：显式红标 + 原因（不重发、不静默）
                  if (message.isFailed)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        '发送失败：${message.errorMessage ?? "未知错误"}（未自动重发，可手动重试）',
                        style: TextStyle(color: textColor, fontSize: 12),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _fileChip(BuildContext context, AttachmentRef att) {
    return InkWell(
      onTap: () => onAttachmentTap?.call(att),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              switch (att.type) {
                AttachmentType.video => Icons.videocam,
                AttachmentType.audio => Icons.audiotrack,
                _ => Icons.insert_drive_file,
              },
              size: 18,
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                '${att.type.label} · ${att.mime} · ${att.sizeLabel}',
                style: const TextStyle(fontSize: 12),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
