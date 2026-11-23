import '../models/run.dart';

/// 回合事件流（Link 事件与 SSE 退路的统一抽象；ChatController 消费）。
///
/// Link 路径由 `run.*` 事件帧驱动；SSE 路径由 `POST /api/chat/stream` 的
/// `event: run|step|delta|done` 帧驱动——同一份 Run 语义，控制器不分辨来源。
sealed class RunFeedEvent {
  const RunFeedEvent();
}

/// run.started（思考中）。
class RunFeedStarted extends RunFeedEvent {
  const RunFeedStarted(this.run);
  final RunRecord run;
}

/// run.step（think/tool/recall/approval/reply 状态行；开始与结束各一次）。
class RunFeedStep extends RunFeedEvent {
  const RunFeedStep(this.step);
  final RunStep step;
}

/// run.delta（流式文本上屏）。
class RunFeedDelta extends RunFeedEvent {
  const RunFeedDelta({required this.runId, required this.delta});
  final String runId;
  final String delta;
}

/// run.completed / run.failed / run.cancelled（终态定屏）。
class RunFeedTerminal extends RunFeedEvent {
  const RunFeedTerminal(this.run);
  final RunRecord run;
}

/// 本地合成：流中断（连接在终态前关闭）。显式标失败，绝不伪装成完成——
/// 与 v2 aborted 同口径，不重发。
class RunFeedAborted extends RunFeedEvent {
  const RunFeedAborted(this.reason);
  final String reason;
}
