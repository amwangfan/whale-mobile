import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../transport/transport_client.dart';
import 'adapter.dart';
import 'mcp_client.dart';

/// sensor.event 上行入口（与 TransportClient.sendSensorEvent 命名参数子集对齐，
/// 便于单测注入 fake；直接传 `_transport.sendSensorEvent` 即可）。
typedef SensorEventSender = Future<String> Function({
  required String kind,
  required String summary,
  int? occurredAt,
  String privacyClass,
  String? evidenceRef,
  String? eventId,
});

/// ScreenMemo 事件导出轮询器（B1 下半：poll_events → sensor.event 上行）。
///
/// 工作方式：
/// - 条件同时满足才发起 MCP 轮询：模块开（[moduleEnabled]）+ token 已配
///   （[hasToken]）+ 传输在线（[transportStates] 最近一帧 == online）+
///   未全局暂停（[isAllPaused]）。
/// - 每 [interval]（默认 15s）调 `poll_events{since_id=游标, limit=pageSize}`；
///   游标（SharedPreferences 键 [cursorKey]）仅在事件成功入队后推进并落盘，
///   失败/暂停不推进，恢复后从游标续传。
/// - 事件 → sensor.event：event_id 固定 `sm-<id>`（重启/重装幂等），
///   occurred_at 用事件原值；summary 经 adapter 既有裁剪截到 200 字符；
///   整条事件先过 [ScreenMemoAdapter.sanitizeForCloud] 剥除 file_path 类字段。
/// - 背压：单轮最多 [pageSize] 条；MCP 失败/不可用 → 静默跳过该轮（记日志），
///   不推进游标；连续失败 ≥5 次后指数退避，封顶 [maxBackoff]（默认 5min）。
class ScreenMemoEventPoller extends ChangeNotifier {
  ScreenMemoEventPoller({
    required ScreenMemoMcpClient client,
    required SensorEventSender sendEvent,
    required Stream<TransportState> transportStates,
    Future<bool> Function()? isAllPaused,
    Future<bool> Function()? hasToken,
    bool Function()? moduleEnabled,
    Future<SharedPreferences> Function()? prefsProvider,
    this.interval = const Duration(seconds: 15),
    this.maxBackoff = const Duration(minutes: 5),
    this.pageSize = 100,
    TransportState initialTransportState = TransportState.disconnected,
    void Function(String message)? logger,
  })  : _client = client, // ignore: prefer_initializing_formals
        _sendEvent = sendEvent, // ignore: prefer_initializing_formals
        _transportStates = transportStates, // ignore: prefer_initializing_formals
        _isAllPaused = isAllPaused, // ignore: prefer_initializing_formals
        _hasToken = hasToken, // ignore: prefer_initializing_formals
        _moduleEnabled = moduleEnabled, // ignore: prefer_initializing_formals
        _prefsProvider = prefsProvider ?? SharedPreferences.getInstance,
        _transportState = initialTransportState,
        _logger = logger; // ignore: prefer_initializing_formals

  /// 游标持久化键（SharedPreferences）。
  static const String cursorKey = 'screenmemo_event_cursor';

  /// 上传 summary 上限（与 adapter snippet 裁剪一致）。
  static const int maxSummaryChars = ScreenMemoAdapter.maxSnippetChars;

  /// 连续失败达到该次数后进入指数退避。
  static const int backoffThreshold = 5;

  final ScreenMemoMcpClient _client;
  final SensorEventSender _sendEvent;
  final Stream<TransportState> _transportStates;
  final Future<bool> Function()? _isAllPaused;
  final Future<bool> Function()? _hasToken;
  final bool Function()? _moduleEnabled;
  final Future<SharedPreferences> Function() _prefsProvider;
  final void Function(String message)? _logger;

  /// 正常轮询间隔（默认 15s）。
  final Duration interval;

  /// 退避封顶（默认 5min）。
  final Duration maxBackoff;

  /// 单轮拉取上限（默认 100，服务端 ≤200）。
  final int pageSize;

  SharedPreferences? _prefs;
  StreamSubscription<TransportState>? _stateSub;
  Timer? _timer;
  bool _running = false;
  bool _polling = false;
  bool _loaded = false;

  TransportState _transportState = TransportState.disconnected;

  int _cursor = 0;
  int _uploadedCount = 0;
  int? _lastEventAtMs;
  int _consecutiveFailures = 0;
  String? _lastError;

  /// 当前游标（已确认入队的最大事件 id）。
  int get cursor => _cursor;

  /// 本次运行已成功入队的事件数。
  int get uploadedCount => _uploadedCount;

  /// 最近一条已上传事件的 occurred_at（Unix 毫秒；null = 尚无）。
  int? get lastEventAtMs => _lastEventAtMs;

  /// 连续失败次数（MCP 失败或入队失败）。
  int get consecutiveFailures => _consecutiveFailures;

  /// 最近一次失败摘要（展示用）。
  String? get lastError => _lastError;

  /// 是否已启动（定时器在跑）。
  bool get isRunning => _running;

  /// 下一次轮询前的等待时长（连续失败 ≥5 次后指数退避，封顶 [maxBackoff]）。
  @visibleForTesting
  Duration get currentDelay {
    if (_consecutiveFailures < backoffThreshold) return interval;
    final shift = _consecutiveFailures - backoffThreshold + 1;
    final ms = interval.inMilliseconds * (1 << shift);
    return ms >= maxBackoff.inMilliseconds
        ? maxBackoff
        : Duration(milliseconds: ms);
  }

  /// 启动：加载游标 → 订阅传输状态（转 online 时立即补一轮）→ 开表。
  Future<void> start() async {
    if (_running) return;
    _running = true;
    await _ensureLoaded();
    _stateSub = _transportStates.listen((s) {
      final wasOnline = _transportState == TransportState.online;
      _transportState = s;
      if (s == TransportState.online && !wasOnline) _kick();
    }, onError: (Object _) {/* 通道异常不致命 */});
    _arm();
    // 启动即试一轮（条件不满足会自动跳过）；await 保证 start 返回时首轮已落定。
    await _onTimer();
    if (_running) notifyListeners();
  }

  /// 停止轮询（游标保留；再次 start 从游标续传）。
  Future<void> stop() async {
    _running = false;
    _timer?.cancel();
    _timer = null;
    await _stateSub?.cancel();
    _stateSub = null;
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    await stop();
    super.dispose();
  }

  /// 单轮轮询：条件不满足静默跳过；失败不推进游标。公开供测试/立即触发。
  Future<void> pollOnce() async {
    if (!_running || _polling) return;
    _polling = true;
    try {
      await _ensureLoaded();
      if (!await _conditionsMet()) return;
      final Map<String, dynamic> sc;
      try {
        sc = await _client.callToolStructured('poll_events', <String, dynamic>{
          'since_id': _cursor,
          'limit': pageSize,
        });
        _consecutiveFailures = 0;
        _lastError = null;
      } on McpException catch (e) {
        // MCP 失败/不可用：静默跳过该轮，不推进游标。
        _onFailure('poll_events: ${e.message}');
        return;
      }
      for (final raw in ScreenMemoAdapter.asList(sc['events'])) {
        if (raw is! Map) continue;
        // 防御性剥除 file_path 类字段（服务端已保证，双保险）。
        final event =
            (ScreenMemoAdapter.sanitizeForCloud(raw) as Map).cast<String, dynamic>();
        final id = ScreenMemoAdapter.asInt(event['id']);
        if (id == null) continue; // 无 id 无法推进游标，跳过
        final kind = event['kind']?.toString() ?? '';
        if (kind.isEmpty) {
          // 脏数据：推进游标跳过，避免毒消息卡死管线。
          await _advanceCursor(id);
          continue;
        }
        // 批次中途暂停/掉线/关模块：停在当前游标，下轮续传。
        if (!await _conditionsMet()) return;
        final summary = ScreenMemoAdapter.truncateText(
          event['summary']?.toString() ?? '',
          maxSummaryChars,
        );
        final occurredAt = ScreenMemoAdapter.asInt(event['occurred_at']);
        try {
          await _sendEvent(
            kind: kind,
            summary: summary,
            occurredAt: occurredAt,
            privacyClass:
                event['privacy_class']?.toString() ?? 'owner_private',
            evidenceRef: event['evidence_ref'] as String?,
            eventId: 'sm-${id.toString().padLeft(13, '0')}', // 幂等键：重启/重装重拉不重复；schema 要求 ≥16 字符（minLength 16）
          );
        } catch (e) {
          // 入队失败：不推进游标，下轮以同一幂等键重试。
          _onFailure('sendSensorEvent: $e');
          return;
        }
        await _advanceCursor(id);
        _uploadedCount++;
        _lastEventAtMs = occurredAt ?? _lastEventAtMs;
      }
    } catch (e) {
      // 兜底：任何意外异常都不许逃出 pollOnce（kick 路径为浮空调用）。
      _onFailure('pollOnce 异常: $e');
    } finally {
      _polling = false;
      // stop/dispose 后不再通知（浮空轮询可能在 dispose 后才落定）。
      if (_running) notifyListeners();
    }
  }

  // ---- 内部 ----

  Future<bool> _conditionsMet() async {
    if (!_running) return false;
    if (!(_moduleEnabled?.call() ?? true)) return false;
    if (_transportState != TransportState.online) return false;
    try {
      if (await _isAllPaused?.call() ?? false) return false;
      if (!(await _hasToken?.call() ?? true)) return false;
    } catch (_) {
      return false; // 状态查询失败按不满足处理（静默跳过）
    }
    return true;
  }

  Future<void> _ensureLoaded() async {
    if (_loaded) return;
    _prefs = await _prefsProvider();
    _cursor = _prefs?.getInt(cursorKey) ?? 0;
    _loaded = true;
  }

  Future<void> _advanceCursor(int id) async {
    if (id <= _cursor) return;
    _cursor = id;
    try {
      await _prefs?.setInt(cursorKey, id);
    } catch (_) {
      // 落盘失败：内存游标已推进；重启后按幂等键去重，最多重发。
    }
  }

  void _onFailure(String message) {
    _consecutiveFailures++;
    _lastError = message;
    _logger?.call('事件导出跳过本轮（连续失败 $_consecutiveFailures）: $message');
  }

  void _arm() {
    if (!_running) return;
    _timer?.cancel();
    _timer = Timer(currentDelay, _onTimer);
  }

  Future<void> _onTimer() async {
    await pollOnce();
    _arm();
  }

  /// 立即触发一轮（若当前不在轮询中），随后重排定时器。
  void _kick() {
    if (!_running || _polling) return;
    _timer?.cancel();
    unawaited(_onTimer());
  }
}
