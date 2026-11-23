import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../models/approval.dart';

/// 审批卡（M1：手机全量审批——args 原文展开 + note + 600s 超时倒计时）。
///
/// 一次性核销：点击后禁用按钮；重复核销/超时由服务端回 conflict，调用方
/// 显式提示并刷新权威状态。
class ApprovalCard extends StatefulWidget {
  const ApprovalCard({
    super.key,
    required this.approval,
    required this.onResolve,
  });

  final Approval approval;

  /// 裁决回调（approve, note）→ 错误文案（null=成功）。
  final Future<String?> Function(bool approve, String note) onResolve;

  @override
  State<ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends State<ApprovalCard> {
  final TextEditingController _note = TextEditingController();
  bool _busy = false;
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    // 600s 倒计时展示（真实超时以服务端 approval.resolved 为准）
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _note.dispose();
    super.dispose();
  }

  Future<void> _resolve(bool approve) async {
    if (_busy) return;
    setState(() => _busy = true);
    final err = await widget.onResolve(approve, _note.text.trim());
    if (!mounted) return;
    if (err != null) {
      setState(() => _busy = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(err)));
    }
  }

  String _countdown() {
    final remain =
        widget.approval.remainingMs(DateTime.now().millisecondsSinceEpoch);
    if (remain <= 0) return '已超时';
    final s = remain ~/ 1000;
    return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final a = widget.approval;
    final danger = a.risk == 'dangerous';
    final color = danger ? scheme.error : scheme.primary;
    final prettyArgs =
        const JsonEncoder.withIndent('  ').convert(a.args);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: BorderSide(color: color.withValues(alpha: 0.5)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.gpp_maybe, size: 18, color: color),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    a.displayTitle,
                    style: TextStyle(fontWeight: FontWeight.bold, color: color),
                  ),
                ),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    danger ? '危险' : a.risk,
                    style: TextStyle(fontSize: 11, color: color),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(a.capability,
                style: TextStyle(fontSize: 12, color: scheme.outline)),
            if (a.reason.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text('缘由：${a.reason}',
                  style: Theme.of(context).textTheme.bodySmall),
            ],
            const SizedBox(height: 6),
            // args 原文展开（回声语义）
            Theme(
              data: Theme.of(context)
                  .copyWith(dividerColor: Colors.transparent),
              child: ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: const Text('参数原文', style: TextStyle(fontSize: 12)),
                children: [
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: scheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: SelectableText(
                      prettyArgs,
                      style: const TextStyle(
                          fontSize: 12, fontFamily: 'monospace'),
                    ),
                  ),
                ],
              ),
            ),
            TextField(
              controller: _note,
              decoration: const InputDecoration(
                hintText: '备注（可选，随裁决留痕）',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              style: const TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _busy ? null : () => _resolve(true),
                    icon: const Icon(Icons.check, size: 16),
                    label: const Text('批准'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _busy ? null : () => _resolve(false),
                    icon: const Icon(Icons.close, size: 16),
                    label: const Text('拒绝'),
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  _countdown(),
                  style: TextStyle(fontSize: 12, color: scheme.outline),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
