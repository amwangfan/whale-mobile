import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'ulid.dart';

/// Link 协议 v1 客户端（whale-mobile 聊天面；`ws(s)://<host>/api/link`）。
///
/// 对应 `docs/PROTOCOL.md` + `docs/mobile-chat-convergence.md`（M1-M5 已批）：
/// - 信封四要素 `id`（ULID）/`corr`/`type`/`payload`，`v=1.0`，时间戳 Unix ms；
/// - 首帧 hello（10s 内）：`{token|ticket, role:"mobile", name, subscribe[]}`，
///   鉴权 A=api_token（staging 联调），C=设备线签发的短期 ticket（900s，接口已留）；
/// - rpc.request{method,params} → rpc.response 按 `corr` 配对，默认 130s 自超时
///   （覆盖审批等待上限 600s？不——chat.send wait:false 立返；130s 是 RPC 层
///   口径，审批经事件流异步到达）；
/// - 事件推送 event{topic,data,event_id,ts} 按订阅分发；
/// - 30s ping 保活 + 3 倍间隔无下行帧判僵死强制重连（沿用 WssClientV2 看门狗）；
/// - 断线指数退避重连 1s/2s/5s/15s/30s 封顶（与眼镜 handoff 同口径）；
/// - 4401（鉴权失败）/4403（服务端 kick）为终态：不自动重连，显式上抛。
///
/// 纪律（§6/眼镜 handoff §2/§11）：
/// - `retryable:true` 才可由调用方自动重试；`timeout`/本地断线失败=结果未知，
///   绝不自动重放（本类 request 从不自动重试；读路径重试策略见 LinkChatApi）；
/// - 单帧 ≤1 MiB：本地超限拒发（invalid_params），不真上线路；
/// - token/ticket 只进 hello 帧，零日志。
///
/// ULID 生成器见同目录 ulid.dart（Crockford Base32；48bit 毫秒 + 80bit 随机，
/// 与 core `kernel/ids.py` 同形 26 字符）。

/// Link 错误码枚举（core `WhaleError` 口径 + 本地合成码）。
enum LinkErrorCode {
  invalidParams,
  unauthorized,
  forbidden,
  notFound,
  conflict,
  rateLimited,
  internal,
  unavailable,
  timeout,

  /// 本地合成：网络/连接失败、帧超限、坏响应等（不进服务端枚举）。
  local,
}

/// Link 层异常。凭证/message 不含 token；[retryable] 是「调用方可否自动重试」
/// 的唯一依据。
class LinkError implements Exception {
  const LinkError(
    this.code,
    this.message, {
    this.retryable = false,
    this.details,
    this.origin = 'server',
  });

  final LinkErrorCode code;
  final String message;
  final bool retryable;
  final Map<String, dynamic>? details;

  /// server=服务端 error 帧 / local=本地合成（网络断、帧超限、超时）。
  final String origin;

  static LinkErrorCode parseCode(String? wire) {
    switch (wire) {
      case 'invalid_params':
        return LinkErrorCode.invalidParams;
      case 'unauthorized':
        return LinkErrorCode.unauthorized;
      case 'forbidden':
        return LinkErrorCode.forbidden;
      case 'not_found':
        return LinkErrorCode.notFound;
      case 'conflict':
        return LinkErrorCode.conflict;
      case 'rate_limited':
        return LinkErrorCode.rateLimited;
      case 'unavailable':
        return LinkErrorCode.unavailable;
      case 'timeout':
        return LinkErrorCode.timeout;
      case 'internal':
        return LinkErrorCode.internal;
      default:
        return LinkErrorCode.local;
    }
  }

  String get wire => switch (code) {
        LinkErrorCode.invalidParams => 'invalid_params',
        LinkErrorCode.unauthorized => 'unauthorized',
        LinkErrorCode.forbidden => 'forbidden',
        LinkErrorCode.notFound => 'not_found',
        LinkErrorCode.conflict => 'conflict',
        LinkErrorCode.rateLimited => 'rate_limited',
        LinkErrorCode.internal => 'internal',
        LinkErrorCode.unavailable => 'unavailable',
        LinkErrorCode.timeout => 'timeout',
        LinkErrorCode.local => 'local',
      };

  @override
  String toString() => '[$wire${retryable ? '/retryable' : ''}] $message';
}

/// Link 事件帧（event{topic,data,event_id,ts}）。
class LinkEvent {
  const LinkEvent({
    required this.topic,
    required this.data,
    this.eventId,
    this.ts,
  });

  final String topic;
  final Map<String, dynamic> data;
  final String? eventId;
  final int? ts;
}

/// welcome 帧内容（握手成功证据）。
class LinkWelcome {
  const LinkWelcome({
    required this.clientId,
    required this.protocol,
    required this.serverName,
    required this.serverVersion,
    required this.methods,
    required this.isReconnect,
  });

  final String clientId;
  final String protocol;
  final String serverName;
  final String serverVersion;
  final List<String> methods;

  /// 本次 welcome 是否为「掉线后的重连」（上层据此做断线补齐）。
  final bool isReconnect;
}

enum LinkState { disconnected, connecting, connected, degraded }

/// WebSocket 连接工厂（测试注入点：假 Link server / 内存双端）。
typedef LinkConnector = Future<WebSocket> Function(Uri uri);

/// 极简 glob 匹配（fnmatch 子集：`*` 任意串、`?` 单字符），供事件订阅过滤。
bool linkTopicMatches(String pattern, String topic) {
  // 迭代式通配匹配，避免回溯指数爆炸。
  var p = 0, t = 0, star = -1, mark = 0;
  while (t < topic.length) {
    if (p < pattern.length && (pattern[p] == '?' || pattern[p] == topic[t])) {
      p++;
      t++;
    } else if (p < pattern.length && pattern[p] == '*') {
      star = p++;
      mark = t;
    } else if (star != -1) {
      p = star + 1;
      t = ++mark;
    } else {
      return false;
    }
  }
  while (p < pattern.length && pattern[p] == '*') {
    p++;
  }
  return p == pattern.length;
}

class LinkClient {
  LinkClient({
    required this.uri,
    String? token,
    String? ticket,
    this.role = 'mobile',
    this.name = 'whale-mobile',
    List<String>? subscribe,
    LinkConnector? connector,
    this.rpcTimeout = const Duration(seconds: 130),
    this.pingInterval = const Duration(seconds: 30),
    List<Duration>? backoffSteps,
    this.helloTimeout = const Duration(seconds: 10),
  })  : _token = token,
        _ticket = ticket,
        subscribe = List.unmodifiable(subscribe ??
            const ['run.*', 'approval.*', 'notify', 'task.*']),
        _connector = connector ?? ((u) => WebSocket.connect(u.toString())),
        _backoff = List.unmodifiable(backoffSteps ??
            const [
              Duration(seconds: 1),
              Duration(seconds: 2),
              Duration(seconds: 5),
              Duration(seconds: 15),
              Duration(seconds: 30),
            ]);

  /// Link WS 全地址（由 base_url 派生，见 [linkUriFor]）。
  final Uri uri;
  final String role;
  final String name;

  /// 订阅主题（hello.subscribe；可经 link.subscribe RPC 改，此处是初始值）。
  final List<String> subscribe;

  final Duration rpcTimeout;
  final Duration pingInterval;

  final List<Duration> _backoff;

  final LinkConnector _connector;
  final String? _token;

  /// C 鉴权接口（目标态）：hello 携带 ticket 而非 token；core 侧实现中。
  final String? _ticket;

  /// 握手超时（首帧 hello 后等 welcome 的窗口，默认 10s）。
  final Duration helloTimeout;

  static const int maxFrameBytes = 1024 * 1024; // 1 MiB（PROTOCOL §1）

  // ---- 状态（单线程事件循环内变更；Stream 广播）----
  LinkState _state = LinkState.disconnected;
  WebSocket? _ws;
  int _generation = 0;
  bool _voluntaryClose = false;
  bool _disposed = false;
  bool _hadConnected = false;
  int _backoffAttempt = 0;
  int _lastInboundMs = 0;
  Timer? _pingTimer;
  Timer? _reconnectTimer;
  StreamSubscription<dynamic>? _sub;
  LinkWelcome? _welcome;

  final Map<String, Completer<Map<String, dynamic>>> _pending = {};
  final Map<String, Timer> _pendingTimers = {};

  final StreamController<LinkState> _states =
      StreamController<LinkState>.broadcast();
  final StreamController<LinkEvent> _events =
      StreamController<LinkEvent>.broadcast();
  final StreamController<LinkError> _protocolErrors =
      StreamController<LinkError>.broadcast();
  final StreamController<LinkWelcome> _welcomes =
      StreamController<LinkWelcome>.broadcast();

  Stream<LinkState> get states => _states.stream;
  Stream<LinkEvent> get events => _events.stream;
  Stream<LinkError> get protocolErrors => _protocolErrors.stream;

  /// welcome 流（含 isReconnect 标记）；上层据此触发断线补齐。
  Stream<LinkWelcome> get welcomes => _welcomes.stream;

  LinkState get state => _state;
  LinkWelcome? get welcome => _welcome;
  bool get isConnected => _state == LinkState.connected;
  int get reconnectAttempts => _backoffAttempt;

  /// 事件主题过滤（订阅语义同 core fnmatch；`run.*` 匹配 `run.delta`）。
  Stream<LinkEvent> eventsMatching(String pattern) =>
      events.where((e) => linkTopicMatches(pattern, e.topic));

  /// 由 REST base_url 派生 Link WS 地址（http→ws、https→wss；无硬编码路径以外部分）。
  static Uri linkUriFor(String baseUrl) {
    final base = Uri.parse(baseUrl.trim());
    final scheme = switch (base.scheme.toLowerCase()) {
      'http' => 'ws',
      'https' => 'wss',
      'ws' => 'ws',
      'wss' => 'wss',
      _ => throw const LinkError(LinkErrorCode.invalidParams,
          'Core 地址 scheme 无法映射到 ws/wss', origin: 'local'),
    };
    if (base.host.isEmpty) {
      throw const LinkError(
          LinkErrorCode.invalidParams, 'Core 地址缺少主机名', origin: 'local');
    }
    return Uri(
      scheme: scheme,
      host: base.host,
      port: base.hasPort ? base.port : null,
      path: '/api/link',
    );
  }

  // ---------------------------------------------------------------- 生命周期

  /// 启动连接循环（幂等：已连接/建连中忽略）。之后断线自动退避重连，
  /// 直到 [disconnect]/[dispose]。
  void connect() {
    if (_disposed) return;
    if (_state == LinkState.connected ||
        _state == LinkState.connecting ||
        _state == LinkState.degraded) {
      return;
    }
    _voluntaryClose = false;
    _openSocket();
  }

  /// 主动断开并保持（不自动重连）。再次 [connect] 恢复。
  void disconnect() {
    _voluntaryClose = true;
    _teardownSocket();
    _transition(LinkState.disconnected);
  }

  Future<void> dispose() async {
    _disposed = true;
    _voluntaryClose = true;
    _teardownSocket();
    _state = LinkState.disconnected;
    await _states.close();
    await _events.close();
    await _protocolErrors.close();
    await _welcomes.close();
  }

  void _teardownSocket() {
    _generation++;
    _pingTimer?.cancel();
    _pingTimer = null;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    final ws = _ws;
    _ws = null;
    _sub?.cancel();
    _sub = null;
    if (ws != null) {
      try {
        ws.close(WebSocketStatus.normalClosure);
      } catch (_) {/* 已断 */}
    }
    _failAllPending(const LinkError(
        LinkErrorCode.unavailable, '连接中断，请求结果未知（未自动重放）',
        origin: 'local'));
  }

  void _openSocket() {
    if (_disposed || _voluntaryClose) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _transition(_hadConnected ? LinkState.degraded : LinkState.connecting);
    final gen = ++_generation;
    _connector(uri).then((ws) {
      if (_disposed || _voluntaryClose || gen != _generation) {
        try {
          ws.close();
        } catch (_) {}
        return;
      }
      _ws = ws;
      _lastInboundMs = DateTime.now().millisecondsSinceEpoch;
      _sub = ws.listen(
        (data) => _onFrame(gen, data),
        onError: (Object e) => _onTransportLost(gen, 'error: $e', null),
        onDone: () =>
            _onTransportLost(gen, 'closed code=${ws.closeCode}', ws.closeCode),
        cancelOnError: false,
      );
      _sendHello(gen);
    }, onError: (Object e) {
      if (gen != _generation) return;
      _onTransportLost(gen, 'connect: $e', null);
    });
  }

  void _onTransportLost(int gen, String why, int? closeCode) {
    if (_disposed || gen != _generation) return;
    _pingTimer?.cancel();
    _pingTimer = null;
    _sub?.cancel();
    _sub = null;
    _ws = null;
    _failAllPending(const LinkError(
        LinkErrorCode.unavailable, '连接中断，请求结果未知（未自动重放）',
        origin: 'local'));
    if (_voluntaryClose) {
      _transition(LinkState.disconnected);
      return;
    }
    // 4401 鉴权失败 / 4403 服务端 kick：终态，不自动重连（token 轮换/设备撤销后
    // 重连只会再被拒）；UI 显式可见，主人改配置后重进页面即重连。
    if (closeCode == 4401 || closeCode == 4403) {
      _voluntaryClose = true;
      _transition(LinkState.disconnected);
      _protocolErrors.add(LinkError(
        closeCode == 4401
            ? LinkErrorCode.unauthorized
            : LinkErrorCode.forbidden,
        closeCode == 4401
            ? 'Link 鉴权失败（4401）：请核对访问令牌'
            : '服务端主动断开（4403）',
        origin: 'server',
      ));
      return;
    }
    _transition(_hadConnected ? LinkState.degraded : LinkState.connecting);
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_disposed || _voluntaryClose || _reconnectTimer != null) return;
    final step = _backoff[_backoffAttempt.clamp(0, _backoff.length - 1)];
    _backoffAttempt++;
    _reconnectTimer = Timer(step, _openSocket);
  }

  // ---------------------------------------------------------------- 握手

  void _sendHello(int gen) {
    final helloId = newUlid();
    final payload = <String, dynamic>{
      // A：api_token（staging 联调）；C：ticket（900s，绑 device_id，接口已留）。
      if (_ticket != null && _ticket.isNotEmpty) 'ticket': _ticket,
      if ((_ticket == null || _ticket.isEmpty) &&
          _token != null &&
          _token.isNotEmpty)
        'token': _token,
      'role': role,
      'name': name,
      'subscribe': subscribe,
    };
    _send(gen, 'hello', payload, id: helloId);
    // 握手超时（10s 无 welcome/error）：断开走重连。
    Timer(helloTimeout, () {
      if (_disposed || gen != _generation) return;
      if (_state != LinkState.connected) {
        _onTransportLost(gen, 'hello timeout', null);
      }
    });
  }

  // ---------------------------------------------------------------- 帧收发

  void _send(int gen, String type, Map<String, dynamic> payload,
      {String? id, String? corr}) {
    final ws = _ws;
    if (ws == null || gen != _generation) {
      throw const LinkError(LinkErrorCode.unavailable, 'Link 未连接',
          retryable: true, origin: 'local');
    }
    final env = <String, dynamic>{
      'v': '1.0',
      'id': id ?? newUlid(),
      'type': type,
      'ts': DateTime.now().millisecondsSinceEpoch,
      'corr': corr,
      'payload': payload,
    };
    final raw = jsonEncode(env);
    if (utf8.encode(raw).length > maxFrameBytes) {
      // 帧上限（1 MiB）：本地拒发；大负载一律 artifact 引用（契约 §3）。
      throw const LinkError(LinkErrorCode.invalidParams, 'frame too large',
          origin: 'local');
    }
    ws.add(raw);
  }

  void _onFrame(int gen, dynamic data) {
    if (_disposed || gen != _generation) return;
    _lastInboundMs = DateTime.now().millisecondsSinceEpoch;
    if (data is! String) return; // 二进制帧不在协议内，忽略
    final Map<String, dynamic> env;
    try {
      env = (jsonDecode(data) as Map).cast<String, dynamic>();
    } catch (_) {
      return; // 坏帧忽略（服务端对坏 JSON 回 error，不断连）
    }
    final type = env['type'] as String? ?? '';
    final payload =
        (env['payload'] as Map?)?.cast<String, dynamic>() ?? const {};
    switch (type) {
      case 'welcome':
        _hadConnected = true;
        _backoffAttempt = 0;
        final server =
            (payload['server'] as Map?)?.cast<String, dynamic>() ?? const {};
        _welcome = LinkWelcome(
          clientId: payload['client_id'] as String? ?? '',
          protocol: payload['protocol'] as String? ?? '',
          serverName: server['name'] as String? ?? '',
          serverVersion: server['version'] as String? ?? '',
          methods:
              (payload['methods'] as List?)?.whereType<String>().toList() ??
                  const [],
          isReconnect: _state == LinkState.degraded,
        );
        _transition(LinkState.connected);
        _startPing();
        _welcomes.add(_welcome!);
        break;
      case 'rpc.response':
        _resolveResponse(env);
        break;
      case 'event':
        _events.add(LinkEvent(
          topic: payload['topic'] as String? ?? '',
          data: (payload['data'] as Map?)?.cast<String, dynamic>() ?? const {},
          eventId: payload['event_id'] as String?,
          ts: (payload['ts'] as num?)?.toInt(),
        ));
        break;
      case 'pong':
        break; // 看门狗只认「有下行帧」
      case 'rpc.request':
        // 服务端→客户端调用（capability.invoke）：手机聊天面第一版不声明能力，
        // 按纪律回 not_found 而非悬挂。
        _tryReply(gen, env['id'] as String?, const {
          'ok': false,
          'error': {'code': 'not_found', 'message': 'no capabilities exposed'}
        });
        break;
      case 'error':
        final err = LinkError(
          LinkError.parseCode(payload['code'] as String?),
          payload['message'] as String? ?? '',
          retryable: payload['retryable'] as bool? ?? false,
          details: (payload['details'] as Map?)?.cast<String, dynamic>(),
        );
        _protocolErrors.add(err);
        // 握手期 error（unauthorized/invalid ticket）后服务端会 4401/4400 关闭；
        // 由 _onTransportLost 按关闭码落定终态。
        break;
      default:
        break; // 未知类型静默忽略（前向兼容）
    }
  }

  void _tryReply(int gen, String? corr, Map<String, dynamic> payload) {
    if (corr == null) return;
    try {
      _send(gen, 'rpc.response', payload, corr: corr);
    } catch (_) {/* 连接已断则丢弃 */}
  }

  // ---------------------------------------------------------------- RPC

  /// 单次 RPC：corr 配对 + 自超时（默认 [rpcTimeout]=130s）。
  ///
  /// **从不自动重试**：`timeout`/断线=结果未知，绝不自动重放（纪律 §6）。
  /// 读路径的 retryable 重试策略在 LinkChatApi._read 实现。
  Future<Map<String, dynamic>> request(String method,
          [Map<String, dynamic>? params, Duration? timeout]) =>
      _request(method, params ?? const {}, timeout ?? rpcTimeout);

  Future<Map<String, dynamic>> _request(
      String method, Map<String, dynamic> params, Duration timeout) async {
    if (_state != LinkState.connected) {
      // 未连接=未发送，安全可重试语义（与「在飞断线=结果未知」区分）。
      return Future.error(const LinkError(
          LinkErrorCode.unavailable, 'Link 未连接',
          retryable: true, origin: 'local'));
    }
    final id = newUlid();
    final completer = Completer<Map<String, dynamic>>();
    _pending[id] = completer;
    final timer = Timer(timeout, () {
      _pending.remove(id);
      _pendingTimers.remove(id);
      if (!completer.isCompleted) {
        // timeout：结果未知，绝不自动重放（纪律 §6）。
        completer.completeError(LinkError(
            LinkErrorCode.timeout, '$method 请求超时（结果未知，未重放）',
            origin: 'local'));
      }
    });
    _pendingTimers[id] = timer;
    try {
      _send(_generation, 'rpc.request', {'method': method, 'params': params},
          id: id);
    } catch (e) {
      _pending.remove(id);
      timer.cancel();
      _pendingTimers.remove(id);
      rethrow;
    }
    return completer.future;
  }

  void _resolveResponse(Map<String, dynamic> env) {
    final corr = env['corr'] as String? ?? '';
    final completer = _pending.remove(corr);
    final timer = _pendingTimers.remove(corr);
    timer?.cancel();
    if (completer == null || completer.isCompleted) return;
    final payload =
        (env['payload'] as Map?)?.cast<String, dynamic>() ?? const {};
    if (payload['ok'] == true) {
      final result = payload['result'];
      completer.complete(result is Map
          ? result.cast<String, dynamic>()
          : <String, dynamic>{'value': result});
    } else {
      final err = (payload['error'] as Map?)?.cast<String, dynamic>() ??
          const <String, dynamic>{};
      completer.completeError(LinkError(
        LinkError.parseCode(err['code'] as String?),
        err['message'] as String? ?? '',
        retryable: err['retryable'] as bool? ?? false,
        details: (err['details'] as Map?)?.cast<String, dynamic>(),
      ));
    }
  }

  void _failAllPending(LinkError error) {
    final pending = _pending.values.toList();
    _pending.clear();
    for (final t in _pendingTimers.values) {
      t.cancel();
    }
    _pendingTimers.clear();
    for (final c in pending) {
      if (!c.isCompleted) c.completeError(error);
    }
  }

  // ---------------------------------------------------------------- 保活

  void _startPing() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(pingInterval, (_) {
      final gen = _generation;
      if (_state != LinkState.connected) return;
      // 看门狗：3 倍间隔无下行帧判僵死，强制重连（沿用 WssClientV2 口径）。
      final silentMs = DateTime.now().millisecondsSinceEpoch - _lastInboundMs;
      if (silentMs > pingInterval.inMilliseconds * 3) {
        _onTransportLost(gen, 'watchdog silent', null);
        return;
      }
      try {
        _send(gen, 'ping', const {});
      } catch (_) {/* 断线由 listen 回调接管 */}
    });
  }

  void _transition(LinkState next) {
    if (_state == next) return;
    _state = next;
    _states.add(next);
  }
}
