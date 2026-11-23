/// Run / Step 模型（v3 观测面：run.* 事件 + runs.get/list 返回形状）。
///
/// run 形状：`{id, conversation_id, parent_id, task_id, mode, status, input,
/// output, error, channel, model, created_at, ended_at, stats}`。
/// step 形状：`{id, run_id, seq, kind, title, status, input, output,
/// started_at, ended_at}`（开始与结束各推一次 run.step）。
library;

/// 回合状态机：running/waiting → completed/failed/cancelled/interrupted。
class RunRecord {
  const RunRecord({
    required this.id,
    this.conversationId,
    this.mode = 'chat',
    this.status = 'running',
    this.input = '',
    this.output = '',
    this.error = '',
    this.channel = '',
    this.createdAt,
    this.endedAt,
    this.stats = const {},
  });

  final String id;
  final String? conversationId;
  final String mode;
  final String status;
  final String input;
  final String output;
  final String error;
  final String channel;
  final int? createdAt;
  final int? endedAt;
  final Map<String, dynamic> stats;

  bool get isTerminal =>
      status == 'completed' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'interrupted';

  factory RunRecord.fromJson(Map<String, dynamic> json) {
    return RunRecord(
      id: json['id'] as String? ?? '',
      conversationId: json['conversation_id'] as String?,
      mode: json['mode'] as String? ?? 'chat',
      status: json['status'] as String? ?? 'running',
      input: json['input'] as String? ?? '',
      output: json['output'] as String? ?? '',
      error: json['error'] as String? ?? '',
      channel: json['channel'] as String? ?? '',
      createdAt: (json['created_at'] as num?)?.toInt(),
      endedAt: (json['ended_at'] as num?)?.toInt(),
      stats: (json['stats'] as Map?)?.cast<String, dynamic>() ?? const {},
    );
  }
}

/// 步骤轨迹（kind=recall/think/tool/approval/reply/delegate/plan/note）。
class RunStep {
  const RunStep({
    required this.id,
    required this.runId,
    required this.seq,
    required this.kind,
    this.title = '',
    this.status = 'running',
    this.input = const {},
    this.output = const {},
    this.startedAt,
    this.endedAt,
  });

  final String id;
  final String runId;
  final int seq;
  final String kind;
  final String title;
  final String status;
  final Map<String, dynamic> input;
  final Map<String, dynamic> output;
  final int? startedAt;
  final int? endedAt;

  factory RunStep.fromJson(Map<String, dynamic> json) {
    return RunStep(
      id: json['id'] as String? ?? '',
      runId: json['run_id'] as String? ?? '',
      seq: (json['seq'] as num?)?.toInt() ?? 0,
      kind: json['kind'] as String? ?? '',
      title: json['title'] as String? ?? '',
      status: json['status'] as String? ?? 'running',
      input: (json['input'] as Map?)?.cast<String, dynamic>() ?? const {},
      output: (json['output'] as Map?)?.cast<String, dynamic>() ?? const {},
      startedAt: (json['started_at'] as num?)?.toInt(),
      endedAt: (json['ended_at'] as num?)?.toInt(),
    );
  }

  /// 凝缩成 UI 轨迹行（M1 折叠展开的数据源）。
  String get detailSummary {
    final err = output['error'] as String?;
    if (err != null && err.isNotEmpty) return err;
    final summary = output['summary'] as String?;
    if (summary != null && summary.isNotEmpty) return summary;
    final decision = output['decision'] as String?;
    if (kind == 'think' && decision != null) return 'decision: $decision';
    final text = output['text'] as String?;
    if (kind == 'reply' && text != null) {
      return text.length > 80 ? '${text.substring(0, 80)}…' : text;
    }
    return '';
  }
}
