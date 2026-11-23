import 'dart:convert';

import 'package:flutter/material.dart';

import '../models/message.dart';
import 'trace_expansion.dart';

/// 消息气泡（v3 统一主干）。
///
/// - 图片：本地回声用 data: URL 直接解码；历史消息 core 只存图片数
///   （meta.images），以「📷 N 张图片」占位呈现，不伪造内容；
/// - 记忆提示/工具轨迹（M1）：默认折叠成「想起了 N 条 · 调用了 xxx」一行，
///   点击展开看 run.step 轨迹；历史消息按 meta 摘要展示。
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    this.statusLine,
    this.onArtifactTap,
  });

  final ChatMessage message;

  /// 当前状态行（仅流式占位有意义：思考中/步骤标题/等待审批）。
  final String? statusLine;

  /// artifact id 点击回调（下载/预览；art_ 引用）。
  final ValueChanged<String>? onArtifactTap;

  /// 文本中的 artifact 引用（art_/att_ + ULID；工具产物、媒体结果）。
  static final RegExp artifactRefPattern =
      RegExp(r'\b(?:art|att)_[0-9A-HJKMNP-TV-Z]{26}\b');

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
            // 记忆提示/工具轨迹（M1：可见、默认折叠）
            if (message.trace.isNotEmpty || message.traceSummary.isNotEmpty)
              TraceExpansion(message: message),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: bubbleColor,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 本地回声图片（发送中/刚发完）
                  for (final local in message.localImages) ...[
                    _localImage(local),
                    const SizedBox(height: 6),
                  ],
                  // 历史图片占位（core 只存数量不存内容，不伪造）
                  if (message.localImages.isEmpty && message.imageCount > 0)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text('📷 ${message.imageCount} 张图片',
                          style: TextStyle(color: textColor, fontSize: 13)),
                    ),
                  if (message.text.isNotEmpty)
                    SelectableText(
                      message.text,
                      style: TextStyle(color: textColor, fontSize: 15),
                    ),
                  // 文本中的 artifact 引用 → 可点芯片
                  if (!isUser) ..._artifactChips(context, textColor),
                  // 流式中：状态行 + 光标
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
                          Flexible(
                            child: Text(
                              statusLine == null || statusLine!.isEmpty
                                  ? '思考中…'
                                  : statusLine!,
                              style: TextStyle(color: textColor, fontSize: 12),
                            ),
                          ),
                        ],
                      ),
                    ),
                  if (message.sendState == MessageSendState.cancelled)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text('已打断',
                          style: TextStyle(color: textColor, fontSize: 12)),
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

  Widget _localImage(LocalImage local) {
    final comma = local.dataUrl.indexOf(',');
    if (comma < 0) return const SizedBox.shrink();
    final bytes = base64Decode(local.dataUrl.substring(comma + 1));
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 240, maxHeight: 320),
        child: Image.memory(bytes, fit: BoxFit.cover),
      ),
    );
  }

  /// 从正文提取 artifact 引用渲染为可点芯片（GET /api/artifacts/{id}）。
  List<Widget> _artifactChips(BuildContext context, Color textColor) {
    final ids = artifactRefPattern.allMatches(message.text).toList();
    if (ids.isEmpty) return const [];
    return [
      const SizedBox(height: 6),
      Wrap(
        spacing: 6,
        runSpacing: 4,
        children: [
          for (final m in ids)
            ActionChip(
              avatar: const Icon(Icons.attach_file, size: 14),
              label: Text('产物 ${m[0]!.substring(0, 10)}…',
                  style: const TextStyle(fontSize: 11)),
              onPressed: () => onArtifactTap?.call(m[0]!),
            ),
        ],
      ),
    ];
  }
}
