import 'package:flutter/material.dart';

/// 工具状态卡片四态（规划 §5.5 硬要求）：
/// 必须区分「模型正在生成」「工具正在执行」「工具执行失败」「任务已完成」，
/// 不能把工具调用过程伪装成普通文字。
enum ToolCardState {
  /// 模型正在生成（无工具调用，delta 流出中）。
  generating,

  /// 工具正在执行（tool.started / tool.progress）。
  running,

  /// 工具执行失败（tool.result 回执为失败/错误）。
  failed,

  /// 完成（done）。
  done,
}

/// 工具调用状态卡片：独立视觉（图标 + 状态色 + 进度），与聊天气泡区分。
class ToolResultCard extends StatelessWidget {
  const ToolResultCard({
    super.key,
    required this.state,
    this.toolName,
    this.summary,
    this.progress,
    this.receipt,
  });

  final ToolCardState state;

  /// 工具名（如 shell.run / screen.search）。
  final String? toolName;

  /// 进度说明或结果摘要。
  final String? summary;

  /// 0..100；null 表示无进度信息。
  final int? progress;

  /// 原始回执（tool.result 的 receipt 文本，可展开）。
  final String? receipt;

  /// 从流式回执文本判定失败（尽力而为；服务端回执是字符串摘要）。
  static ToolCardState stateFromResult(String? receipt) {
    if (receipt == null) return ToolCardState.done;
    final r = receipt.toLowerCase();
    if (r.contains('failed') || r.contains('error') || r.contains('timeout')) {
      return ToolCardState.failed;
    }
    return ToolCardState.done;
  }

  @override
  Widget build(BuildContext context) {
    final (icon, color, label) = switch (state) {
      ToolCardState.generating => (
          Icons.psychology,
          Colors.blueGrey,
          '模型正在生成…'
        ),
      ToolCardState.running => (Icons.build_circle, Colors.blue, '工具正在执行'),
      ToolCardState.failed => (Icons.error_outline, Colors.red, '工具执行失败'),
      ToolCardState.done => (Icons.check_circle_outline, Colors.green, '已完成'),
    };
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      color: color.withValues(alpha: 0.08),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: BorderSide(color: color.withValues(alpha: 0.4)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            state == ToolCardState.running || state == ToolCardState.generating
                ? SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: color,
                      value: state == ToolCardState.running &&
                              progress != null
                          ? progress! / 100
                          : null,
                    ),
                  )
                : Icon(icon, size: 20, color: color),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    toolName == null || toolName!.isEmpty
                        ? label
                        : '$label · $toolName',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: color,
                      fontSize: 13,
                    ),
                  ),
                  if (summary != null && summary!.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(summary!,
                          style: Theme.of(context).textTheme.bodySmall),
                    ),
                  if (receipt != null && receipt!.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        receipt!,
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: Theme.of(context).colorScheme.outline),
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
}
