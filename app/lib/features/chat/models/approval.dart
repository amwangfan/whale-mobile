/// 审批模型（v3：approval.requested/resolved 事件 + approvals.list 行）。
///
/// Core 行形状：`{id, run_id, capability, args(Map), reason, risk, status,
/// created_at, resolved_at, resolved_by, note}`；事件帧额外带 `title`/`provider`
/// （DB 行无此两列，列表回落 capability 作展示名）。
///
/// 纪律（M1/§5）：一次性核销（重复 resolve → conflict）；600s 未决服务端按
/// 超时拒绝（approval.resolved status=denied/by=system/note=timed out）。
library;

class Approval {
  const Approval({
    required this.id,
    this.runId,
    required this.capability,
    this.args = const {},
    this.reason = '',
    this.risk = 'external',
    this.status = 'pending',
    this.createdAt,
    this.resolvedAt,
    this.resolvedBy,
    this.note,
    this.title,
    this.provider,
  });

  final String id;
  final String? runId;
  final String capability;
  final Map<String, dynamic> args;
  final String reason;
  final String risk;

  /// pending / approved / denied / expired。
  final String status;
  final int? createdAt;
  final int? resolvedAt;
  final String? resolvedBy;
  final String? note;

  /// 仅事件帧携带；列表行回落 capability。
  final String? title;
  final String? provider;

  bool get isPending => status == 'pending';

  /// 展示名（M1 审批卡标题）。
  String get displayTitle =>
      (title != null && title!.isNotEmpty) ? title! : capability;

  /// 600s 服务端超时口径：剩余毫秒（<0 表示已过；仅供 UI 倒计时展示，
  /// 真实超时以服务端 approval.resolved 为准）。
  int remainingMs(int nowMs) {
    final created = createdAt;
    if (created == null) return 0;
    return created + 600 * 1000 - nowMs;
  }

  factory Approval.fromJson(Map<String, dynamic> json) {
    return Approval(
      id: json['id'] as String? ?? '',
      runId: json['run_id'] as String?,
      capability: json['capability'] as String? ?? '',
      args: (json['args'] as Map?)?.cast<String, dynamic>() ?? const {},
      reason: json['reason'] as String? ?? '',
      risk: json['risk'] as String? ?? 'external',
      status: json['status'] as String? ?? 'pending',
      createdAt: (json['created_at'] as num?)?.toInt(),
      resolvedAt: (json['resolved_at'] as num?)?.toInt(),
      resolvedBy: json['resolved_by'] as String?,
      note: json['note'] as String?,
      title: json['title'] as String?,
      provider: json['provider'] as String?,
    );
  }
}
