import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../transport/link/link_client.dart';
import '../models/message.dart';
import '../models/run.dart';
import '../services/chat_stream_v3.dart';
import '../services/link_chat_api.dart';
import '../services/run_feed.dart';

/// 聊天控制器（v3 统一主干：Link RPC + 事件流；弱网退路 SSE）。
///
/// 口径（契约 §1/§6）：
/// - 发送 `chat.send{wait:false}` 立返 run_id → 事件流驱动：
///   run.started（思考中）→ run.step（状态行/轨迹）→ run.delta（流式上屏）→
///   run.completed（定屏）；打断 `chat.cancel{run_id}` → run.cancelled。
/// - 弱网/无长连退路 `POST /api/chat/stream`（SSE run/step/delta/done，
///   同一份 Run；SSE 帧无打断通道，退路下不提供打断）。
/// - 历史 `conversations.messages {id, limit, before=毫秒ts}` 分页。
/// - 断线补齐（§6）：重连 welcome 后 `conversations.messages` 补对话 +
///   `events.query` 补事件；在飞 run 的结果以补齐的 assistant 消息为准
///   （assistant 消息 → events.query → runs.get 三级回落）。
/// - 网络异常全部显式可见（[error] + 失败消息标记），不静默重试、不重发
///   （chat.send 是写路径：timeout/断线=结果未知，绝不自动重放）。
/// - [messages] 新→旧（index 0 最新），配合 `ListView(reverse: true)`。
class ChatController extends ChangeNotifier {
  ChatController({
    required this.conversationId,
    required ChatBackend api,
    ChatStreamV3? sse,
  })  : _api = api,
        _sse = sse ?? ChatStreamV3();

  /// 会话 id（客户端生成持有，如 `mobile:<ULID>`；服务端幂等）。
  final String conversationId;
  final ChatBackend _api;
  final ChatStreamV3 _sse;

  /// 消息列表（新→旧）。
  final List<ChatMessage> messages = <ChatMessage>[];

  bool loadingInitial = false;
  bool loadingOlder = false;
  bool sending = false;
  String? error;

  /// 当前状态行（run.started→思考中 / run.step→步骤标题 / 等待审批）。
  String statusLine = '';

  /// 在飞 run id（null=无；事件到达早于 rpc.response 时按 conversation 认领）。
  String? activeRunId;

  /// 在飞 run 使用的传输（link / sse）；SSE 退路无打断通道。
  String? get activeTransport => _activeTransport;
  String? _activeTransport;

  int? _oldestTs;
  bool _hasMore = true;

  StreamSubscription<LinkEvent>? _eventSub;
  StreamSubscription<LinkWelcome>? _welcomeSub;
  bool _listening = false;

  bool get hasMore => _hasMore;
  bool get canCancel => activeRunId != null && _activeTransport == 'link';

  /// 事件订阅（页面活跃期间调用一次；重复调用安全）。
  void attach() {
    if (_listening) return;
    _listening = true;
    _eventSub = _api.events.listen(_onLinkEvent);
    _welcomeSub = _api.welcomes.listen(_onWelcome);
  }

  // ---------------------------------------------------------------- 历史

  /// 首屏加载（失败显式置 [error]，UI 展示重试按钮）。
  Future<void> loadInitial() async {
    if (loadingInitial) return;
    loadingInitial = true;
    error = null;
    notifyListeners();
    try {
      final page = await _api.conversationMessages(conversationId, limit: 50);
      final incoming = page.messages.reversed.toList(); // core 正序 → 新→旧
      messages
        ..clear()
        ..addAll(incoming);
      _oldestTs = _oldestTsOf(messages);
      _hasMore = page.messages.length >= 50;
    } on LinkError catch (e) {
      error = '加载历史失败：$e';
    }
    loadingInitial = false;
    notifyListeners();
  }

  /// 向更旧翻页（before=毫秒 ts；服务端按 ts 排他分页，重复调用安全）。
  Future<void> loadOlder() async {
    if (loadingOlder || loadingInitial || !_hasMore) return;
    final before = _oldestTs;
    if (before == null) {
      _hasMore = false;
      return;
    }
    loadingOlder = true;
    notifyListeners();
    try {
      final page = await _api.conversationMessages(conversationId,
          limit: 50, before: before);
      _mergeHistory(page.messages.reversed.toList());
      _oldestTs = _oldestTsOf(messages) ?? before;
      if (page.messages.length < 50) _hasMore = false;
    } on LinkError catch (e) {
      error = '加载更多失败：$e';
    }
    loadingOlder = false;
    notifyListeners();
  }

  int? _oldestTsOf(List<ChatMessage> list) {
    for (final m in list.reversed) {
      if (m.createdAt != null) return m.createdAt;
    }
    return null;
  }

  /// 历史合并：按 messageId 去重（§6 不重复插入）。
  void _mergeHistory(List<ChatMessage> older) {
    final seenIds =
        messages.map((m) => m.messageId).whereType<String>().toSet();
    for (final m in older) {
      if (m.messageId != null && seenIds.contains(m.messageId)) continue;
      messages.add(m); // older 更旧 → 追加到尾部（尾部=最旧）
    }
  }

  /// 断线补齐 / 下拉刷新：重取最新页并按 messageId 对齐本地消息；
  /// 在飞 run 以补齐的 assistant 消息为准（§6）。
  Future<void> refresh() async {
    try {
      final page = await _api.conversationMessages(conversationId, limit: 50);
      final incoming = page.messages.reversed.toList();
      // 保留仍在进行中的本地消息（流式占位不应被历史覆盖）
      final inflight = messages
          .where((m) => m.sendState == MessageSendState.streaming)
          .toList();
      messages
        ..clear()
        ..addAll(inflight);
      _mergeHistory(incoming);
      _oldestTs = _oldestTsOf(messages);
      _hasMore = page.messages.length >= 50;
      error = null;
      await _resolveInflightRun(incoming);
    } on LinkError catch (e) {
      error = '刷新失败：$e';
    }
    notifyListeners();
  }

  /// 在飞 run 断线补齐：assistant 消息为准 → events.query → runs.get。
  Future<void> _resolveInflightRun(List<ChatMessage> latestPage) async {
    final runId = activeRunId;
    if (runId == null) return;
    // 1) 补齐的 assistant 消息（服务端已落库=最终结果，权威）
    for (final m in latestPage) {
      if (m.runId == runId && m.role == MessageRole.assistant) {
        _settleActiveRun(
          runId: runId,
          text: m.text,
          state: MessageSendState.settled,
          memoryIds: m.memoryIds,
          tools: m.tools,
          serverMessageId: m.messageId,
        );
        return;
      }
    }
    // 2) events.query 补事件（run.completed/failed/cancelled）
    try {
      final events = await _api.queryEvents(pattern: 'run.*', limit: 100);
      for (final e in events) {
        final run = (e.data['run'] as Map?)?.cast<String, dynamic>();
        if (run == null || run['id'] != runId) continue;
        final record = RunRecord.fromJson(run);
        if (e.topic == 'run.completed') {
          _settleActiveRun(
              runId: runId, text: record.output, state: MessageSendState.settled);
          return;
        }
        if (e.topic == 'run.failed') {
          _settleActiveRun(
              runId: runId,
              text: '',
              state: MessageSendState.failed,
              errMsg: record.error.isNotEmpty ? record.error : 'run failed');
          return;
        }
        if (e.topic == 'run.cancelled') {
          _settleActiveRun(
              runId: runId, text: '', state: MessageSendState.cancelled);
          return;
        }
      }
    } on LinkError {
      // 补齐失败不致命：回落 runs.get
    }
    // 3) runs.get 观测面（如 core 重启把在飞 run 落 interrupted）
    try {
      final detail = await _api.getRun(runId);
      if (detail.run.isTerminal) {
        switch (detail.run.status) {
          case 'completed':
            _settleActiveRun(
                runId: runId,
                text: detail.run.output,
                state: MessageSendState.settled);
            break;
          case 'cancelled':
            _settleActiveRun(
                runId: runId, text: '', state: MessageSendState.cancelled);
            break;
          default:
            _settleActiveRun(
                runId: runId,
                text: '',
                state: MessageSendState.failed,
                errMsg: detail.run.error.isNotEmpty
                    ? detail.run.error
                    : 'run ${detail.run.status}');
        }
      }
      // 仍在 running/waiting：保持流式占位，后续事件会落定
    } on LinkError {
      // 观测面也不可达：保持占位，等下次补齐/事件
    }
  }

  // ---------------------------------------------------------------- 发送

  /// 发送文字（可带图片 data: URL ≤4 张）。乐观插入用户消息 + 助手占位，
  /// 随后由事件流驱动。断线/error → 该轮显式标记失败（不重发）。
  Future<void> send(
    String text, {
    List<String> images = const [],
    List<LocalImage> localImages = const [],
  }) async {
    if (sending) return;
    final trimmed = text.trim();
    if (trimmed.isEmpty && images.isEmpty) return;

    sending = true;
    error = null;
    statusLine = '';

    // 乐观插入：用户消息（新→旧列表头）+ 助手占位（流式目标）
    final userMsg = ChatMessage(
      role: MessageRole.user,
      text: trimmed,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      imageCount: images.length,
      localImages: localImages,
      sendState: MessageSendState.settled,
    );
    final placeholder = ChatMessage(
      role: MessageRole.assistant,
      text: '',
      createdAt: DateTime.now().millisecondsSinceEpoch,
      sendState: MessageSendState.streaming,
    );
    messages.insert(0, placeholder);
    messages.insert(1, userMsg);
    notifyListeners();

    if (_api.isConnected) {
      await _sendViaLink(trimmed, images);
    } else {
      await _sendViaSse(trimmed, images);
    }
  }

  /// Link 路径：chat.send wait:false → 事件流驱动（[_onLinkEvent] 接管）。
  Future<void> _sendViaLink(String text, List<String> images) async {
    try {
      final result = await _api.sendChat(
        text: text,
        conversationId: conversationId,
        images: images,
      );
      // 同一连接严格有序：chat.send 处理期间产生的事件先于响应送达——
      // 响应到达时 run.started 可能已在事件队列里；认领逻辑见 _eventRunId。
      activeRunId = result.runId;
      _activeTransport = 'link';
      statusLine = '思考中…';
      notifyListeners();
    } on LinkError catch (e) {
      // 写路径：timeout/断线=结果未知，绝不自动重放（§6）
      _failActiveRun(e);
    }
  }

  /// SSE 退路（弱网/无长连）：同一份 Run 语义，事件形状统一到 RunFeedEvent。
  Future<void> _sendViaSse(String text, List<String> images) async {
    _activeTransport = 'sse';
    final buffer = StringBuffer();
    final trace = <TraceLine>[];
    var memoryIds = <String>[];
    var runId = '';

    ChatMessage? current() {
      final i = _activeIndex;
      return i < 0 ? null : messages[i];
    }

    void update({String? text, MessageSendState? state, String? errMsg}) {
      final c = current();
      if (c == null) return;
      _replace(
        c,
        text: text,
        state: state,
        runId: runId.isNotEmpty ? runId : null,
        trace: List<TraceLine>.unmodifiable(trace),
        memoryIds: List<String>.unmodifiable(memoryIds),
        errMsg: errMsg,
      );
    }

    await for (final ev in _sse.streamTurn(
      text: text,
      conversationId: conversationId,
      images: images,
    )) {
      switch (ev) {
        case RunFeedStarted():
          runId = ev.run.id;
          activeRunId = runId.isNotEmpty ? runId : null;
          statusLine = '思考中…';
        case RunFeedStep():
          _upsertTrace(trace, ev.step);
          memoryIds = _memoryIdsOfStep(ev.step) ?? memoryIds;
          statusLine = ev.step.title;
        case RunFeedDelta():
          buffer.write(ev.delta);
        case RunFeedTerminal():
          sending = false;
          activeRunId = null;
          _activeTransport = null;
          statusLine = '';
          final ok = ev.run.status == 'completed';
          update(
            text: ev.run.output.isNotEmpty ? ev.run.output : buffer.toString(),
            state: ok
                ? MessageSendState.settled
                : ev.run.status == 'cancelled'
                    ? MessageSendState.cancelled
                    : MessageSendState.failed,
            errMsg: ok ? null : ev.run.error,
          );
          if (!ok) {
            error = '本轮失败：${ev.run.error.isNotEmpty ? ev.run.error : ev.run.status}';
            notifyListeners();
          }
          return;
        case RunFeedAborted():
          sending = false;
          activeRunId = null;
          _activeTransport = null;
          statusLine = '';
          update(
            text: buffer.toString(),
            state: MessageSendState.failed,
            errMsg: '连接中断：本轮回复未完成（已标记失败，未重发）',
          );
          error = '连接中断：本轮回复未完成（已标记失败，未重发）';
          notifyListeners();
          return;
      }
      update(text: buffer.toString(), state: MessageSendState.streaming);
    }
  }

  /// 打断（仅 Link 路径；run 落 cancelled 终态后由事件落定）。
  Future<void> cancelActive() async {
    final runId = activeRunId;
    if (runId == null || !canCancel) return;
    try {
      await _api.cancelChat(runId);
      statusLine = '已请求打断…';
      notifyListeners();
    } on LinkError catch (e) {
      error = '打断失败：$e';
      notifyListeners();
    }
  }

  // ---------------------------------------------------------------- 事件流

  void _onWelcome(LinkWelcome welcome) {
    if (welcome.isReconnect) {
      // 断线补齐（§6）：补对话 + 在飞 run 落定
      refresh();
    }
  }

  /// 事件归属判定：优先 run_id 精确匹配；run_id 尚未认领（rpc.response
  /// 晚于事件到达的窗口）时按 conversation_id 认领本会话的最新 run。
  String? _eventRunId(Map<String, dynamic> runJson) {
    final id = runJson['id'] as String? ?? '';
    if (activeRunId != null && id == activeRunId) return activeRunId;
    final conv = runJson['conversation_id'] as String?;
    if (activeRunId == null &&
        sending &&
        conv == conversationId &&
        _activeIndex >= 0) {
      activeRunId = id.isNotEmpty ? id : null;
      return activeRunId;
    }
    return null;
  }

  void _onLinkEvent(LinkEvent ev) {
    switch (ev.topic) {
      case 'run.started':
        final run = (ev.data['run'] as Map?)?.cast<String, dynamic>();
        if (run == null || _eventRunId(run) == null) return;
        statusLine = '思考中…';
        notifyListeners();
        break;
      case 'run.step':
        final step = (ev.data['step'] as Map?)?.cast<String, dynamic>();
        if (step == null) return;
        final rid = step['run_id'] as String? ?? '';
        if (activeRunId == null || rid != activeRunId) return;
        _applyStepToActive(RunStep.fromJson(step));
        break;
      case 'run.delta':
        final rid = ev.data['run_id'] as String? ?? '';
        if (activeRunId == null || rid != activeRunId) return;
        _appendDelta(ev.data['delta'] as String? ?? '');
        break;
      case 'run.completed':
      case 'run.failed':
      case 'run.cancelled':
        final run = (ev.data['run'] as Map?)?.cast<String, dynamic>();
        if (run == null) return;
        final rid = _eventRunId(run);
        if (rid == null) return;
        _onRunTerminal(ev.topic, RunRecord.fromJson(run));
        break;
      default:
        break; // approval.*/notify/task.* 由 ApprovalController/页面层消费
    }
  }

  /// 在飞占位的索引（同一时刻至多一个 streaming 消息）。
  int get _activeIndex =>
      messages.indexWhere((m) => m.sendState == MessageSendState.streaming);

  void _appendDelta(String delta) {
    final i = _activeIndex;
    if (i < 0) return;
    messages[i] = messages[i].copyWith(text: messages[i].text + delta);
    notifyListeners();
  }

  void _applyStepToActive(RunStep step) {
    final i = _activeIndex;
    if (i < 0) return;
    final trace = List<TraceLine>.of(messages[i].trace);
    _upsertTrace(trace, step);
    final memoryIds = _memoryIdsOfStep(step) ?? messages[i].memoryIds;
    messages[i] = messages[i].copyWith(
      trace: List<TraceLine>.unmodifiable(trace),
      memoryIds: List<String>.unmodifiable(memoryIds),
    );
    if (step.status == 'running') {
      statusLine = step.kind == 'approval' ? '等待审批：${step.title}' : step.title;
    }
    notifyListeners();
  }

  /// recall 步的 output.memories 携带记忆 id 列表（M1 记忆提示数据源）。
  List<String>? _memoryIdsOfStep(RunStep step) {
    if (step.kind != 'recall' || step.status == 'running') return null;
    final mems = (step.output['memories'] as List?) ?? const [];
    return mems
        .whereType<Map>()
        .map((m) => m['id'] as String? ?? '')
        .where((s) => s.isNotEmpty)
        .toList();
  }

  /// step 开始/结束各推一次：按 step.id 原位更新（标题可在结束时变化）。
  void _upsertTrace(List<TraceLine> trace, RunStep step) {
    final line = TraceLine(
      stepId: step.id,
      kind: step.kind,
      title: step.title,
      status: step.status,
      detail: step.detailSummary.isEmpty ? null : step.detailSummary,
    );
    final idx = trace.indexWhere((t) => t.stepId == step.id);
    if (idx >= 0) {
      trace[idx] = line;
    } else {
      trace.add(line);
    }
  }

  void _onRunTerminal(String topic, RunRecord run) {
    switch (topic) {
      case 'run.completed':
        _settleActiveRun(
            runId: run.id, text: run.output, state: MessageSendState.settled);
        break;
      case 'run.failed':
        error = '本轮失败：${run.error.isNotEmpty ? run.error : 'failed'}';
        _settleActiveRun(
            runId: run.id,
            text: '',
            state: MessageSendState.failed,
            errMsg: run.error.isNotEmpty ? run.error : '模型或编排失败');
        break;
      case 'run.cancelled':
        _settleActiveRun(
            runId: run.id, text: '', state: MessageSendState.cancelled);
        break;
    }
  }

  /// 在飞 run 落定（终态定屏/断线补齐共用）。text 空=保留已流出文本。
  void _settleActiveRun({
    required String runId,
    required String text,
    required MessageSendState state,
    List<String>? memoryIds,
    List<Map<String, dynamic>>? tools,
    String? serverMessageId,
    String? errMsg,
  }) {
    final i = _activeIndex;
    sending = false;
    activeRunId = null;
    _activeTransport = null;
    statusLine = '';
    if (i < 0) {
      notifyListeners();
      return;
    }
    final current = messages[i];
    messages[i] = ChatMessage(
      messageId: serverMessageId,
      role: MessageRole.assistant,
      text: text.isNotEmpty ? text : current.text,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      runId: runId,
      memoryIds: memoryIds ?? current.memoryIds,
      tools: tools ?? current.tools,
      sendState: state,
      trace: current.trace,
      errorMessage: errMsg,
    );
    notifyListeners();
  }

  void _replace(
    ChatMessage target, {
    String? text,
    MessageSendState? state,
    String? runId,
    List<TraceLine>? trace,
    List<String>? memoryIds,
    String? errMsg,
  }) {
    final i = messages.indexOf(target);
    if (i < 0) return;
    messages[i] = target.copyWith(
      text: text,
      sendState: state,
      runId: runId,
      trace: trace,
      memoryIds: memoryIds,
      errorMessage: errMsg,
    );
    notifyListeners();
  }

  void _failActiveRun(LinkError e) {
    sending = false;
    activeRunId = null;
    _activeTransport = null;
    statusLine = '';
    error = e.toString();
    final i = _activeIndex;
    if (i >= 0) {
      messages[i] = messages[i].copyWith(
        sendState: MessageSendState.failed,
        errorMessage: e.toString(),
      );
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _welcomeSub?.cancel();
    _sse.close();
    super.dispose();
  }
}
