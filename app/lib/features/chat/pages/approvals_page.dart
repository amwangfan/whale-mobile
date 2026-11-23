import 'dart:convert';

import 'package:flutter/material.dart';

import '../controllers/approval_controller.dart';
import '../models/approval.dart';
import '../widgets/approval_card.dart';

/// 审批中心页（M1：手机全量审批——待决可裁决 + 历史留痕）。
///
/// 待决：approval.requested 事件驱动 + 断线补齐重拉；裁决走
/// approvals.resolve（一次性、600s 超时、重复 conflict 显式提示）。
/// 历史：approvals.list(status=null) 全量（已决含 note/resolved_by）。
class ApprovalsPage extends StatefulWidget {
  const ApprovalsPage({super.key, required this.controller});

  final ApprovalController controller;

  @override
  State<ApprovalsPage> createState() => _ApprovalsPageState();
}

class _ApprovalsPageState extends State<ApprovalsPage> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onUpdate);
    // 页面在导航动画中挂载：刷新推后一帧，避免通知触发他页 build 期 setState
    WidgetsBinding.instance.addPostFrameCallback((_) {
      widget.controller.refreshPending();
      widget.controller.loadHistory();
    });
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onUpdate);
    super.dispose();
  }

  void _onUpdate() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return Scaffold(
      appBar: AppBar(title: const Text('审批')),
      body: RefreshIndicator(
        onRefresh: () async {
          await c.refreshPending();
          await c.loadHistory();
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(12),
          children: [
            if (c.error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(c.error!,
                    style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                        fontSize: 12)),
              ),
            Text('待决（${c.pending.length}）',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 6),
            if (c.pending.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('没有待裁决的审批'),
              ),
            for (final a in c.pending)
              ApprovalCard(
                key: ValueKey(a.id),
                approval: a,
                onResolve: (approve, note) =>
                    c.resolve(a, approve, note: note),
              ),
            const SizedBox(height: 16),
            Text('历史（${c.history.length}）',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 6),
            if (c.history.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('暂无历史审批'),
              ),
            for (final a in c.history) _HistoryTile(approval: a),
          ],
        ),
      ),
    );
  }
}

/// 历史行：状态 + capability + 裁决人 + note + args 原文展开。
class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.approval});

  final Approval approval;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final a = approval;
    final (icon, color, label) = switch (a.status) {
      'approved' => (Icons.check_circle_outline, Colors.green, '已批准'),
      'denied' => (Icons.cancel_outlined, scheme.error, '已拒绝'),
      'expired' => (Icons.timer_off_outlined, scheme.outline, '已超时'),
      _ => (Icons.help_outline, scheme.outline, a.status),
    };
    final time = a.resolvedAt ?? a.createdAt;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 3),
      child: ExpansionTile(
        leading: Icon(icon, color: color, size: 20),
        title: Text(a.displayTitle, style: const TextStyle(fontSize: 14)),
        subtitle: Text(
          '$label · ${a.resolvedBy ?? '-'}'
          '${a.note != null && a.note!.isNotEmpty ? ' · 备注：${a.note}' : ''}'
          '${time != null ? '\n${_fmtTime(time)}' : ''}',
          style: const TextStyle(fontSize: 12),
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(a.capability,
                    style: TextStyle(fontSize: 12, color: scheme.outline)),
                const SizedBox(height: 4),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: SelectableText(
                    const JsonEncoder.withIndent('  ').convert(a.args),
                    style: const TextStyle(
                        fontSize: 12, fontFamily: 'monospace'),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _fmtTime(int ms) {
    final t = DateTime.fromMillisecondsSinceEpoch(ms).toLocal();
    return '${t.month}-${t.day} ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }
}
