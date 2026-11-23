import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// 假 Link server（dart:io WebSocket）：模拟 core `/api/link` 面。
///
/// 覆盖面：
/// - 握手：hello → welcome（token 错 → error{unauthorized} + 4401 关闭）；
///   首帧非 hello → error + 4400；
/// - RPC：rpc.request → rpc.response 按 corr 配对（支持延迟/不应答脚本）；
/// - 方法面：chat.send/cancel、conversations.list/messages/update/delete、
///   approvals.list/resolve（一次性+conflict）、runs.get/list、events.query、
///   personas.list、system.status；role=mobile 白名单外的（如 config.get）
///   回 forbidden（模拟服务端白名单）；
/// - 事件：chat.send 后按脚本推 run.started/run.step/run.delta/run.completed；
///   approval.requested 可手动注入；
/// - ping → pong；
/// - 断线控制：dropAll() 强断当前连接（客户端应退避重连）。
class FakeLinkServer {
  FakeLinkServer({this.expectedToken = 'test-token'});

  final String expectedToken;

  HttpServer? _http;
  final List<WebSocket> sockets = [];

  /// 收到的全部帧（客户端上行；断言用）。
  final List<Map<String, dynamic>> frames = [];

  /// 内存会话库：conversationId → messages（正序）。
  final Map<String, List<Map<String, dynamic>>> store = {};

  /// 会话元：conversationId → {title, channel, persona_id, ...}。
  final Map<String, Map<String, dynamic>> conversations = {};

  /// 待决审批：id → approval row。
  final Map<String, Map<String, dynamic>> approvals = {};

  /// 已落库 run：id → run 行。
  final Map<String, Map<String, dynamic>> runs = {};

  /// 事件日志（events.query 数据源；不含 run.delta）。
  final List<Map<String, dynamic>> eventLog = [];

  /// chat.send 脚本：收到后推的事件序列（null=默认一轮 think+delta+completed）。
  List<Map<String, dynamic>> Function(Map<String, dynamic> params,
      String conversationId, String runId)? chatScript;

  /// chat.send 响应延迟（毫秒）：非 null 时先推事件后回响应（测事件早到竞态）。
  int? chatResponseDelayMs;

  /// 不应答的 method 集合（测 RPC 超时）。
  final Set<String> neverAnswer = {};

  /// 方法级强制错误（如 {'chat.send': {'code':'unavailable','retryable':true}}）。
  final Map<String, Map<String, dynamic>> forcedError = {};

  /// 断线期间推不到的事件可经 logEvent 预置（断线补齐测试用）。
  void logEvent(String topic, Map<String, dynamic> data, {String? id, int? ts}) {
    eventLog.add(<String, dynamic>{
      'id': id ?? 'evt_${eventLog.length + 1}',
      'topic': topic,
      'ts': ts ?? DateTime.now().millisecondsSinceEpoch,
      'data': data,
    });
  }

  int _runCounter = 0;
  int _msgCounter = 0;
  int _stepCounter = 0;

  Uri get uri => Uri.parse('ws://127.0.0.1:${_http!.port}/api/link');

  Future<void> start() async {
    _http = await HttpServer.bind('127.0.0.1', 0);
    _http!.listen((req) async {
      if (req.uri.path != '/api/link' || !WebSocketTransformer.isUpgradeRequest(req)) {
        req.response.statusCode = 404;
        await req.response.close();
        return;
      }
      final ws = await WebSocketTransformer.upgrade(req);
      sockets.add(ws);
      ws.listen(
        (data) => _onFrame(ws, data as String),
        onDone: () => sockets.remove(ws),
        onError: (_) => sockets.remove(ws),
      );
    });
  }

  Future<void> stop() async {
    for (final ws in List.of(sockets)) {
      try {
        await ws.close();
      } catch (_) {}
    }
    sockets.clear();
    await _http?.close(force: true);
    _http = null;
  }

  /// 强断当前连接（不关 listener；客户端应退避重连）。
  Future<void> dropAll() async {
    for (final ws in List.of(sockets)) {
      sockets.remove(ws);
      try {
        // 1001 going away（1006 是保留码不能上帧）
        await ws.close(1001);
      } catch (_) {}
    }
  }

  /// 给所有在线连接推事件帧。
  void pushEvent(String topic, Map<String, dynamic> data, {int? ts}) {
    final id = 'evt_${eventLog.length + 1}';
    final t = ts ?? DateTime.now().millisecondsSinceEpoch;
    if (topic != 'run.delta') {
      logEvent(topic, data, id: id, ts: t);
    }
    if (topic == 'approval.requested') {
      approvals[data['id'] as String] = data;
    }
    for (final ws in List.of(sockets)) {
      ws.add(jsonEncode(<String, dynamic>{
        'v': '1.0',
        'id': 'srv_$id',
        'type': 'event',
        'ts': t,
        'corr': null,
        'payload': {'topic': topic, 'data': data, 'event_id': id, 'ts': t},
      }));
    }
  }

  // ---------------------------------------------------------------- 帧处理

  void _onFrame(WebSocket ws, String raw) {
    final env = (jsonDecode(raw) as Map).cast<String, dynamic>();
    frames.add(env);
    final type = env['type'] as String? ?? '';
    final payload = (env['payload'] as Map?)?.cast<String, dynamic>() ?? const {};
    switch (type) {
      case 'hello':
        _onHello(ws, env);
        break;
      case 'rpc.request':
        _onRpc(ws, env, payload);
        break;
      case 'ping':
        _send(ws, 'pong', const {}, corr: env['id'] as String?);
        break;
      default:
        break;
    }
  }

  void _onHello(WebSocket ws, Map<String, dynamic> env) {
    final payload = (env['payload'] as Map?)?.cast<String, dynamic>() ?? const {};
    final token = payload['token'] as String?;
    if (token != expectedToken) {
      _send(ws, 'error', {'code': 'unauthorized', 'message': 'invalid token'},
          corr: env['id'] as String?);
      ws.close(4401);
      return;
    }
    _send(ws, 'welcome', <String, dynamic>{
      'client_id': 'cli_fake_${DateTime.now().microsecondsSinceEpoch}',
      'protocol': '1.0',
      'server': {'name': '鲸鱼', 'version': '1.0.0-fake'},
      'methods': [
        'chat.send', 'chat.cancel', 'conversations.list', 'conversations.messages',
        'conversations.update', 'conversations.delete', 'runs.get', 'runs.list',
        'approvals.list', 'approvals.resolve', 'events.query', 'personas.list',
        'system.status', 'link.subscribe',
      ],
    }, corr: env['id'] as String?);
  }

  void _onRpc(WebSocket ws, Map<String, dynamic> env, Map<String, dynamic> payload) {
    final method = payload['method'] as String? ?? '';
    final params = (payload['params'] as Map?)?.cast<String, dynamic>() ?? const {};
    final corr = env['id'] as String?;

    // role=mobile 白名单（模拟服务端 ROLE_METHODS['mobile']）
    const whitelist = {
      'chat.send', 'chat.cancel', 'conversations.list', 'conversations.messages',
      'conversations.update', 'conversations.delete', 'runs.get', 'runs.list',
      'link.subscribe', 'link.clients', 'approvals.list', 'approvals.resolve',
      'system.status', 'system.schema', 'events.query', 'personas.list',
      'tasks.list', 'tasks.get', 'memory.search',
    };
    if (!whitelist.contains(method) &&
        !(method.startsWith('conversations.') || method.startsWith('glasses.'))) {
      _replyErr(ws, corr, 'forbidden', 'role mobile may not call $method');
      return;
    }
    if (neverAnswer.contains(method)) return; // 挂起（测客户端自超时）
    final forced = forcedError[method];
    if (forced != null) {
      _replyErr(ws, corr, forced['code'] as String, forced['message'] as String? ?? '',
          retryable: forced['retryable'] as bool? ?? false);
      return;
    }

    switch (method) {
      case 'chat.send':
        _onChatSend(ws, corr, params);
        break;
      case 'chat.cancel':
        final runId = params['run_id'] as String? ?? '';
        final run = runs[runId];
        if (run != null && run['status'] == 'running') {
          run['status'] = 'cancelled';
          run['ended_at'] = DateTime.now().millisecondsSinceEpoch;
          pushEvent('run.cancelled', {'run': run});
        }
        _replyOk(ws, corr, {'cancelled': run != null});
        break;
      case 'conversations.list':
        final limit = (params['limit'] as num?)?.toInt() ?? 50;
        final channel = params['channel'] as String?;
        var list = conversations.values.toList();
        if (channel != null) {
          list = list.where((c) => c['channel'] == channel).toList();
        }
        list.sort((a, b) =>
            (b['updated_at'] as int).compareTo(a['updated_at'] as int));
        _replyOk(ws, corr, {
          'conversations': list.take(limit).map(_convWithPreview).toList(),
        });
        break;
      case 'conversations.messages':
        _onConvMessages(ws, corr, params);
        break;
      case 'conversations.update':
        final id = params['id'] as String? ?? '';
        final conv = conversations[id];
        if (conv == null) {
          _replyErr(ws, corr, 'not_found', 'conversation $id');
          return;
        }
        if (params.containsKey('title')) conv['title'] = params['title'];
        if (params.containsKey('persona_id')) {
          conv['persona_id'] = params['persona_id'];
        }
        _replyOk(ws, corr, conv);
        break;
      case 'conversations.delete':
        final id = params['id'] as String? ?? '';
        conversations.remove(id);
        store.remove(id);
        _replyOk(ws, corr, {'deleted': id});
        break;
      case 'approvals.list':
        final status = params['status'] as String?;
        var list = approvals.values.toList();
        if (status != null) {
          list = list.where((a) => a['status'] == status).toList();
        }
        list.sort((a, b) =>
            (b['created_at'] as int).compareTo(a['created_at'] as int));
        _replyOk(ws, corr, {'approvals': list});
        break;
      case 'approvals.resolve':
        _onApprovalResolve(ws, corr, params);
        break;
      case 'runs.get':
        final id = params['id'] as String? ?? '';
        final run = runs[id];
        if (run == null) {
          _replyErr(ws, corr, 'not_found', 'run $id');
          return;
        }
        _replyOk(ws, corr, {'run': run, 'steps': const [], 'children': const []});
        break;
      case 'runs.list':
        final list = runs.values.toList()
          ..sort((a, b) =>
              (b['created_at'] as int).compareTo(a['created_at'] as int));
        _replyOk(ws, corr, {'runs': list});
        break;
      case 'events.query':
        final pattern = (params['pattern'] as String? ?? '*').replaceAll('*', '');
        final limit = (params['limit'] as num?)?.toInt() ?? 100;
        final before = params['before'] as String?;
        var list = eventLog
            .where((e) => (e['topic'] as String).contains(pattern))
            .toList();
        if (before != null) {
          list = list.where((e) => (e['id'] as String).compareTo(before) < 0).toList();
        }
        list.sort((a, b) => (b['id'] as String).compareTo(a['id'] as String));
        _replyOk(ws, corr, {'events': list.take(limit).toList()});
        break;
      case 'personas.list':
        _replyOk(ws, corr, {
          'personas': [
            {'id': 'default', 'name': '鲸鱼', 'builtin': true},
            {'id': 'p_custom', 'name': '助手', 'tagline': '自定义', 'builtin': false},
          ],
          'active': 'default',
        });
        break;
      case 'system.status':
        _replyOk(ws, corr, {'version': '1.0.0-fake', 'uptime_s': 1});
        break;
      case 'link.subscribe':
        _replyOk(ws, corr, {'subscriptions': params['patterns'] ?? const []});
        break;
      default:
        _replyErr(ws, corr, 'not_found', 'unknown method $method');
    }
  }

  Map<String, dynamic> _convWithPreview(Map<String, dynamic> conv) {
    final msgs = store[conv['id']] ?? const [];
    final last = msgs.isEmpty ? null : msgs.last;
    return <String, dynamic>{
      ...conv,
      'preview': last == null
          ? null
          : {'role': last['role'], 'text': (last['content'] as String? ?? '').substring(0, ((last['content'] as String).length).clamp(0, 80))},
    };
  }

  void _onConvMessages(
      WebSocket ws, String? corr, Map<String, dynamic> params) {
    final id = params['id'] as String? ?? '';
    final limit = (params['limit'] as num?)?.toInt() ?? 100;
    final before = (params['before'] as num?)?.toInt();
    var msgs = store[id] ?? const [];
    if (before != null) {
      msgs = msgs.where((m) => (m['ts'] as int) < before).toList();
    }
    // core 语义：ts DESC LIMIT → 再反转为正序
    final sorted = List.of(msgs)
      ..sort((a, b) => (b['ts'] as int).compareTo(a['ts'] as int));
    final page = sorted.take(limit).toList().reversed.toList();
    _replyOk(ws, corr, {
      'conversation': conversations[id],
      'messages': page,
    });
  }

  void _onApprovalResolve(
      WebSocket ws, String? corr, Map<String, dynamic> params) {
    final id = params['id'] as String? ?? '';
    final row = approvals[id];
    if (row == null) {
      _replyErr(ws, corr, 'not_found', 'approval $id');
      return;
    }
    if (row['status'] != 'pending') {
      // 一次性核销：重复裁决 → conflict
      _replyErr(ws, corr, 'conflict', 'approval already ${row['status']}');
      return;
    }
    final approve = params['approve'] as bool? ?? false;
    row['status'] = approve ? 'approved' : 'denied';
    row['resolved_at'] = DateTime.now().millisecondsSinceEpoch;
    row['resolved_by'] = 'mobile';
    row['note'] = params['note'] as String? ?? '';
    pushEvent('approval.resolved', row);
    _replyOk(ws, corr, row);
  }

  void _onChatSend(WebSocket ws, String? corr, Map<String, dynamic> params) {
    final text = params['text'] as String? ?? '';
    if (text.isEmpty) {
      _replyErr(ws, corr, 'invalid_params', 'text is required');
      return;
    }
    final convId =
        params['conversation_id'] as String? ?? 'mobile:fake_${_msgCounter}';
    conversations.putIfAbsent(
        convId,
        () => <String, dynamic>{
              'id': convId,
              'title': '',
              'channel': 'mobile',
              'peer': 'owner',
              'persona_id': null,
              'created_at': DateTime.now().millisecondsSinceEpoch,
              'updated_at': DateTime.now().millisecondsSinceEpoch,
              'turns': 0,
              'meta': <String, dynamic>{},
            });
    conversations[convId]!['updated_at'] = DateTime.now().millisecondsSinceEpoch;
    _runCounter++;
    final runId = 'run_fake_$_runCounter';
    final now = DateTime.now().millisecondsSinceEpoch;
    runs[runId] = <String, dynamic>{
      'id': runId,
      'conversation_id': convId,
      'parent_id': null,
      'task_id': null,
      'mode': 'chat',
      'status': 'running',
      'input': text,
      'output': '',
      'error': '',
      'channel': 'mobile',
      'model': 'fake-model',
      'created_at': now,
      'ended_at': null,
      'stats': <String, dynamic>{},
    };
    // 用户消息落库
    store.putIfAbsent(convId, () => []).add(<String, dynamic>{
      'id': 'msg_${++_msgCounter}',
      'conversation_id': convId,
      'role': 'user',
      'content': text,
      'run_id': runId,
      'ts': now,
      'meta': params['images'] != null
          ? {'images': (params['images'] as List).length}
          : <String, dynamic>{},
    });
    if (params['wait'] == false) {
      // 事件序列异步推（同一连接有序；脚本可自定义）
      final script = chatScript ?? _defaultChatScript;
      final frames = script(params, convId, runId);
      void pushAll() {
        for (final f in frames) {
          pushEvent(f['topic'] as String, (f['data'] as Map).cast<String, dynamic>());
        }
      }

      final delay = chatResponseDelayMs;
      if (delay != null) {
        // 事件先于响应到达（竞态窗口）
        pushAll();
        Timer(Duration(milliseconds: delay), () {
          _replyOk(ws, corr,
              {'conversation_id': convId, 'run_id': runId, 'accepted': true});
        });
      } else {
        _replyOk(ws, corr,
            {'conversation_id': convId, 'run_id': runId, 'accepted': true});
        Timer(const Duration(milliseconds: 5), pushAll);
      }
    } else {
      _replyOk(ws, corr, {
        'conversation_id': convId,
        'run_id': runId,
        'status': 'completed',
        'text': '同步回复',
        'memories': const [],
        'tools': const [],
      });
    }
  }

  /// 默认脚本：think 步 → delta×2 → completed（reply 落库）。
  List<Map<String, dynamic>> _defaultChatScript(
      Map<String, dynamic> params, String convId, String runId) {
    final reply = '回复：${params['text']}';
    final step = <String, dynamic>{
      'id': 'step_${++_stepCounter}',
      'run_id': runId,
      'seq': 1,
      'kind': 'think',
      'title': '决定直接回答',
      'status': 'done',
      'input': <String, dynamic>{},
      'output': {'decision': 'reply'},
      'started_at': DateTime.now().millisecondsSinceEpoch,
      'ended_at': DateTime.now().millisecondsSinceEpoch,
    };
    return [
      {'topic': 'run.started', 'data': {'run': runs[runId]}},
      {'topic': 'run.step', 'data': {'step': step}},
      {'topic': 'run.delta', 'data': {'run_id': runId, 'conversation_id': convId, 'delta': reply.substring(0, 3)}},
      {'topic': 'run.delta', 'data': {'run_id': runId, 'conversation_id': convId, 'delta': reply.substring(3)}},
      {'topic': 'run.completed', 'data': {'run': _completeRun(runId, convId, reply)}},
    ];
  }

  Map<String, dynamic> _completeRun(String runId, String convId, String output) =>
      _completeRunImpl(runId, convId, output);

  Map<String, dynamic> _completeRunImpl(String runId, String convId, String output) {
    final run = runs[runId]!;
    run['status'] = 'completed';
    run['output'] = output;
    run['ended_at'] = DateTime.now().millisecondsSinceEpoch;
    run['stats'] = {'latency_ms': 5, 'memories': 0, 'tools': 0};
    store.putIfAbsent(convId, () => []).add(<String, dynamic>{
      'id': 'msg_${++_msgCounter}',
      'conversation_id': convId,
      'role': 'assistant',
      'content': output,
      'run_id': runId,
      'ts': DateTime.now().millisecondsSinceEpoch,
      'meta': {'memories': const [], 'tools': const []},
    });
    return run;
  }

  /// 断线期间把 run 落定（不推事件，只落库 + 记事件日志）——断线补齐测试用。
  void completeRunOffline(String runId, String output, {String? convId}) {
    final run = runs[runId];
    if (run == null) return;
    final cid = convId ?? run['conversation_id'] as String;
    _completeRunImpl(runId, cid, output);
    logEvent('run.completed', {'run': Map<String, dynamic>.of(run)});
  }

  /// 断线期间推 approval.requested（只记事件日志 + 落审批表）。
  void requestApprovalOffline(Map<String, dynamic> approval) {
    approvals[approval['id'] as String] = approval;
    logEvent('approval.requested', approval);
  }

  // ---------------------------------------------------------------- 帧写

  void _send(WebSocket ws, String type, Map<String, dynamic> payload,
      {String? corr}) {
    try {
      ws.add(jsonEncode(<String, dynamic>{
        'v': '1.0',
        'id': 'srv_${DateTime.now().microsecondsSinceEpoch}',
        'type': type,
        'ts': DateTime.now().millisecondsSinceEpoch,
        'corr': corr,
        'payload': payload,
      }));
    } catch (_) {/* 已断 */}
  }

  void _replyOk(WebSocket ws, String? corr, Map<String, dynamic> result) {
    _send(ws, 'rpc.response', {'ok': true, 'result': result}, corr: corr);
  }

  void _replyErr(WebSocket ws, String? corr, String code, String message,
      {bool retryable = false}) {
    _send(ws, 'rpc.response', {
      'ok': false,
      'error': {'code': code, 'message': message, 'retryable': retryable},
    }, corr: corr);
  }
}
