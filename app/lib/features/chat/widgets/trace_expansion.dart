import 'package:flutter/material.dart';

import '../models/message.dart';

/// 记忆提示/工具轨迹（M1 可见性）：默认折叠成「想起了 N 条 · 调用了 xxx」
/// 一行，点击展开看 run.step 轨迹明细（think/tool/recall/approval/reply，
/// 各带 title/status/detail）。全功能端看得到、不吵。
class TraceExpansion extends StatefulWidget {
  const TraceExpansion({super.key, required this.message});

  final ChatMessage message;

  @override
  State<TraceExpansion> createState() => _TraceExpansionState();
}

class _TraceExpansionState extends State<TraceExpansion> {
  bool _open = false;

  IconData _iconOf(TraceLine line) {
    return switch (line.kind) {
      'recall' => Icons.psychology_alt,
      'think' => Icons.psychology,
      'tool' => Icons.build_circle_outlined,
      'approval' => Icons.verified_user_outlined,
      'reply' => Icons.reply,
      _ => Icons.notes,
    };
  }

  Color _statusColor(BuildContext context, TraceLine line) {
    final scheme = Theme.of(context).colorScheme;
    return switch (line.status) {
      'running' => scheme.primary,
      'error' || 'denied' => scheme.error,
      _ => scheme.outline,
    };
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.message;
    final scheme = Theme.of(context).colorScheme;
    final summary = m.traceSummary.isNotEmpty
        ? m.traceSummary
        : (m.trace.isNotEmpty ? '回合轨迹 ${m.trace.length} 步' : '');
    if (summary.isEmpty) return const SizedBox.shrink();
    return Container(
      margin: const EdgeInsets.only(bottom: 4),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: () => setState(() => _open = !_open),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.route, size: 14, color: scheme.outline),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      summary,
                      style:
                          TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  Icon(
                    _open ? Icons.expand_less : Icons.expand_more,
                    size: 16,
                    color: scheme.outline,
                  ),
                ],
              ),
            ),
          ),
          if (_open)
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 0, 10, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (m.trace.isEmpty)
                    Text('（历史消息：轨迹未留痕，仅摘要）',
                        style: TextStyle(fontSize: 11, color: scheme.outline)),
                  for (final line in m.trace)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(_iconOf(line),
                              size: 14, color: _statusColor(context, line)),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '${line.title}${line.status == 'running' ? ' …' : ''}',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: _statusColor(context, line),
                                  ),
                                ),
                                if (line.detail != null &&
                                    line.detail!.isNotEmpty)
                                  Text(
                                    line.detail!,
                                    maxLines: 3,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                        fontSize: 11, color: scheme.outline),
                                  ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
