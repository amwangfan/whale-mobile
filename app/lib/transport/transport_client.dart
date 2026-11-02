import 'dart:async';

import 'package:flutter/services.dart';

/// 传输层连接状态（与 Kotlin WssClient.State 一一对应）。
enum TransportState {
  disconnected,
  connecting,
  awaitingAuth,
  online,
  degraded;

  static TransportState fromName(String? name) {
    switch (name) {
      case 'connecting':
        return TransportState.connecting;
      case 'awaiting_auth':
        return TransportState.awaitingAuth;
      case 'online':
        return TransportState.online;
      case 'degraded':
        return TransportState.degraded;
      case 'disconnected':
      default:
        return TransportState.disconnected;
    }
  }

  String get label {
    switch (this) {
      case TransportState.disconnected:
        return '已断开';
      case TransportState.connecting:
        return '连接中';
      case TransportState.awaitingAuth:
        return '认证中';
      case TransportState.online:
        return '在线';
      case TransportState.degraded:
        return '降级（重连中）';
    }
  }
}

/// EventChannel 上行给 Dart 的 action.request（contracts/v1 §4）。
class ActionRequest {
  ActionRequest.fromMap(Map<String, dynamic> map)
      : actionId = map['action_id'] as String? ?? '',
        capability = map['capability'] as String? ?? '',
        args = (map['args'] as Map?)?.cast<String, dynamic>() ?? const {},
        authorizedScope =
            (map['authorized_scope'] as List?)?.cast<String>() ?? const [],
        approvalRef = map['approval_ref'] as String?,
        expiresAt = (map['expires_at'] as num?)?.toInt() ?? 0,
        idempotencyKey = map['idempotency_key'] as String? ?? '';

  final String actionId;
  final String capability;
  final Map<String, dynamic> args;
  final List<String> authorizedScope;
  final String? approvalRef;
  final int expiresAt;
  final String idempotencyKey;
}

/// EventChannel 上行给 Dart 的 sensor.query（contracts/v1 §4）。
class SensorQuery {
  SensorQuery.fromMap(Map<String, dynamic> map)
      : requestId = map['request_id'] as String? ?? '',
        operation = map['operation'] as String? ?? '',
        filters = (map['filters'] as Map?)?.cast<String, dynamic>() ?? const {},
        maxItems = (map['max_items'] as num?)?.toInt() ?? 10,
        deadline = (map['deadline'] as num?)?.toInt() ?? 0;

  final String requestId;
  final String operation;
  final Map<String, dynamic> filters;
  final int maxItems;

  /// Unix 毫秒截止时间；0 表示未携带（按不过期处理，仍应尽快回复）。
  final int deadline;
}

/// 本地执行器处理的 action 终态回执（Kotlin 侧已上行，此事件仅供 UI 展示）。
class ActionReceipt {
  ActionReceipt.fromMap(Map<String, dynamic> map)
      : actionId = map['action_id'] as String? ?? '',
        capability = map['capability'] as String? ?? '',
        state = map['state'] as String? ?? 'unknown',
        errorCode = map['error_code'] as String?;

  final String actionId;
  final String capability;

  /// executed / failed（见 contracts/v1 action.receipt）。
  final String state;
  final String? errorCode;
}

/// 协议级错误（error 帧）。
class TransportError {
  TransportError.fromMap(Map<String, dynamic> map)
      : code = map['code'] as String? ?? 'internal',
        message = map['message'] as String? ?? '',
        refId = map['ref_id'] as String? ?? '';

  final String code;
  final String message;
  final String refId;
}

/// contracts/v1 控制通道客户端的 Dart 封装（Kotlin 侧见 TransportBridge）。
///
/// 通道：MethodChannel `whale/transport` + EventChannel `whale/transport/events`。
class TransportClient {
  TransportClient();

  static const MethodChannel _methods = MethodChannel('whale/transport');
  static const EventChannel _events = EventChannel('whale/transport/events');

  /// V1 能力全集（contracts/v1 §5），connect 默认声明。
  static const List<String> v1Capabilities = [
    'screen.context.read',
    'screen.history.search',
    'screen.evidence.read',
    'screen.live.subscribe',
    'phone.notification.post',
    'phone.notification.read',
    'phone.call.ring',
    'phone.call.audio',
    'phone.device.safe_action',
  ];

  Stream<Map<String, dynamic>>? _rawEvents;

  Stream<Map<String, dynamic>> get _eventStream =>
      _rawEvents ??= _events.receiveBroadcastStream().map(
            (dynamic e) => (e as Map).cast<String, dynamic>(),
          );

  /// 连接状态变化流（订阅即回放最近一次状态）。
  Stream<TransportState> get stateStream => _eventStream
      .where((e) => e['kind'] == 'state')
      .map((e) => TransportState.fromName(e['state'] as String?));

  /// 下行动作请求流（终态需经 sendActionReceipt 补发）。
  /// 注意：已有原生执行器接管的 capability（如 phone.notification.post）
  /// 不会出现在此流中，其终态见 [actionReceipts]。
  Stream<ActionRequest> get actionRequests => _eventStream
      .where((e) => e['kind'] == 'action_request')
      .map(ActionRequest.fromMap);

  /// 本地执行器终态回执流（通知模块等；仅 UI 展示，无需回复）。
  Stream<ActionReceipt> get actionReceipts => _eventStream
      .where((e) => e['kind'] == 'action_receipt')
      .map(ActionReceipt.fromMap);

  /// 下行感知查询流（必须经 sendSensorResult 回复，否则云端空等到 deadline）。
  Stream<SensorQuery> get sensorQueries => _eventStream
      .where((e) => e['kind'] == 'sensor_query')
      .map(SensorQuery.fromMap);

  /// 协议级错误流。
  Stream<TransportError> get errors => _eventStream
      .where((e) => e['kind'] == 'error')
      .map(TransportError.fromMap);

  /// 下行 call.invite / call.hangup 回显流（B4；VoIP 模块已在原生侧消费，
  /// 此流仅供 UI 日志展示）。事件为原始 Map（session_id/expires_at/reason）。
  Stream<Map<String, dynamic>> get callSignalEvents => _eventStream
      .where((e) => e['kind'] == 'call_invite' || e['kind'] == 'call_hangup');

  /// 建立 WSS 连接。返回 {device_id, public_key_hex}。
  Future<Map<String, dynamic>> connect(
    String url, {
    List<String> capabilities = v1Capabilities,
    String? appVersion,
  }) async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>(
      'connect',
      <String, dynamic>{
        'url': url,
        'capabilities': capabilities,
        'app_version': ?appVersion,
      },
    );
    return res?.cast<String, dynamic>() ?? const {};
  }

  Future<void> disconnect() => _methods.invokeMethod<void>('disconnect');

  /// 上行 sensor.event。返回 event_id（幂等键，§6）。
  Future<String> sendSensorEvent({
    required String kind,
    required String summary,
    int? occurredAt,
    String privacyClass = 'owner_private',
    String? evidenceRef,
    String schemaVersion = '1.0',
    bool dropOnPause = false,
    String? eventId,
  }) async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>(
      'sendSensorEvent',
      <String, dynamic>{
        'kind': kind,
        'summary': summary,
        'occurred_at': ?occurredAt,
        'privacy_class': privacyClass,
        'evidence_ref': ?evidenceRef,
        'schema_version': schemaVersion,
        'drop_on_pause': dropOnPause,
        'event_id': ?eventId,
      },
    );
    return res?['event_id'] as String? ?? '';
  }

  /// 回 action.receipt。state ∈ received/accepted/executed/failed/unknown。
  Future<void> sendActionReceipt(
    String actionId,
    String state, {
    int? executedAt,
    String? errorCode,
  }) =>
      _methods.invokeMethod<void>('sendActionReceipt', <String, dynamic>{
        'action_id': actionId,
        'state': state,
        'executed_at': ?executedAt,
        'error_code': ?errorCode,
      });

  /// 回 sensor.result（sensor.query 的唯一回包入口；data 为条目数组）。
  Future<void> sendSensorResult({
    required String requestId,
    required String status, // ok / denied / unavailable / error / paused(全局暂停)
    required List<Map<String, dynamic>> data,
    bool truncated = false,
    String source = 'screenmemo.local',
  }) =>
      _methods.invokeMethod<void>('sendSensorResult', <String, dynamic>{
        'request_id': requestId,
        'status': status,
        'data': data,
        'truncated': truncated,
        'source': source,
      });

  /// 声明 Dart 侧接管 sensor.query 分发（默认 false = 原生 fail-closed）。
  Future<void> setSensorQueryDelegate(bool enabled) =>
      _methods.invokeMethod<void>('setSensorQueryDelegate', <String, dynamic>{
        'enabled': enabled,
      });

  /// 保存 ScreenMemo MCP Bearer token（Keystore 加密落盘；空串清除）。
  Future<void> setMcpToken(String token) =>
      _methods.invokeMethod<void>('setMcpToken', <String, dynamic>{
        'token': token,
      });

  /// 读取已保存的 MCP token；未配置或解密失败返回 null。
  Future<String?> getMcpToken() =>
      _methods.invokeMethod<String?>('getMcpToken');

  /// 设置心跳上报的设备状态（online/connected_only/paused）。
  Future<void> setStatus(String status) =>
      _methods.invokeMethod<void>('setStatus', <String, dynamic>{
        'status': status,
      });

  /// 当前状态快照（含 device_id、session_id、队列统计）。
  Future<Map<String, dynamic>> getState() async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>('getState');
    return res?.cast<String, dynamic>() ?? const {};
  }

  /// 设备身份（device_id + Ed25519 公钥 hex，用于配对登记）。
  Future<Map<String, dynamic>> getIdentity() async {
    final res =
        await _methods.invokeMethod<Map<dynamic, dynamic>>('getIdentity');
    return res?.cast<String, dynamic>() ?? const {};
  }

  // ---- 模块开关（通用；原生侧存 whale_modules/<module>_enabled，默认 true）----

  /// 写模块开关。module 形如 `notification_post`（小写字母/数字/下划线）。
  Future<void> setModuleEnabled(String module, bool enabled) =>
      _methods.invokeMethod<void>('setModuleEnabled', <String, dynamic>{
        'module': module,
        'enabled': enabled,
      });

  /// 读全部已知模块开关（当前：notification_post）。
  Future<Map<String, bool>> getModuleStates() async {
    final res =
        await _methods.invokeMethod<Map<dynamic, dynamic>>('getModuleStates');
    return (res ?? const <dynamic, dynamic>{})
        .map((k, v) => MapEntry(k as String, v == true));
  }

  // ---- 全局暂停（A1；whale_modules/all_paused，原生执行器校验链前置检查）----

  /// 写全局暂停标志。true 时：action 回 unavailable、sensor.query 回 paused。
  Future<void> setAllPaused(bool paused) =>
      _methods.invokeMethod<void>('setAllPaused', <String, dynamic>{
        'paused': paused,
      });

  /// 读全局暂停标志（原生侧不可用时视为未暂停）。
  Future<bool> isAllPaused() async =>
      await _methods.invokeMethod<bool>('isAllPaused') ?? false;

  // ---- 通知权限（Android 13+ POST_NOTIFICATIONS）----

  /// 当前通知权限状态：granted / denied。
  Future<String> getNotificationPermissionStatus() async =>
      await _methods.invokeMethod<String>('getNotificationPermissionStatus') ??
      'denied';

  /// 发起系统权限申请（fire-and-forget）；返回发起时刻状态，调用方延迟后重查。
  Future<String> requestNotificationPermission() async =>
      await _methods.invokeMethod<String>('requestNotificationPermission') ??
      'denied';

  // ---- VoIP 模块（B4：phone.call.ring + call.* 信令）----

  /// VoIP 权限状态（RECORD_AUDIO + MANAGE_OWN_CALLS）：granted / denied。
  Future<String> getVoipPermissionStatus() async =>
      await _methods.invokeMethod<String>('getVoipPermissionStatus') ??
      'denied';

  /// 发起 VoIP 运行时权限申请（RECORD_AUDIO + READ_PHONE_STATE；
  /// fire-and-forget，返回发起时刻状态）。
  Future<String> requestVoipPermissions() async =>
      await _methods.invokeMethod<String>('requestVoipPermissions') ?? 'denied';

  /// 读 PCM 桥端点配置（nova_bridge；call.invite 不携带桥参数，走端侧配置）。
  Future<Map<String, dynamic>> getVoipBridgeConfig() async {
    final res = await _methods
        .invokeMethod<Map<dynamic, dynamic>>('getVoipBridgeConfig');
    return res?.cast<String, dynamic>() ?? const {};
  }

  /// 写 PCM 桥端点配置（host 非空 ≤255 字符，port 1..65535）。
  Future<void> setVoipBridgeConfig(String host, int port) =>
      _methods.invokeMethod<void>('setVoipBridgeConfig', <String, dynamic>{
        'host': host,
        'port': port,
      });

  // ---- 通知监听模块（B3 前半：phone.notification.read，独立可选默认关）----

  /// 通知监听特殊访问权限状态：granted / denied
  /// （NotificationManagerCompat.getEnabledListenerPackages）。
  Future<String> getNotificationReadPermissionStatus() async =>
      await _methods
              .invokeMethod<String>('getNotificationReadPermissionStatus') ??
          'denied';

  /// SEC-02：授权与系统实际绑定分开观测（permission/bound/buffered/overflow 等）。
  Future<Map<String, dynamic>> getNotificationReadStatus() async =>
      Map<String, dynamic>.from(await _methods
              .invokeMethod<Map<dynamic, dynamic>>('getNotificationReadStatus') ??
          <String, dynamic>{});

  /// 「去授权」：跳系统通知监听设置页（特殊访问权限无运行时弹窗，只能跳设置）。
  Future<void> openNotificationListenerSettings() =>
      _methods.invokeMethod<void>('openNotificationListenerSettings');

  /// 读通知监听 App 白名单（whale_notif_read/allowed_apps；默认空 = 一个都不读）。
  Future<Set<String>> getNotificationReadAllowedApps() async {
    final res = await _methods
        .invokeMethod<List<dynamic>>('getNotificationReadAllowedApps');
    return (res ?? const []).whereType<String>().toSet();
  }

  /// 写通知监听白名单（原生侧按包名正则过滤后落盘）。
  Future<void> setNotificationReadAllowedApps(Set<String> apps) =>
      _methods.invokeMethod<void>('setNotificationReadAllowedApps',
          <String, dynamic>{'apps': apps.toList()});

  /// 可监听 App 列表（白名单多选页数据源）：[{package, label}]，按 label 排序。
  Future<List<({String package, String label})>> getListenableApps() async {
    final res =
        await _methods.invokeMethod<List<dynamic>>('getListenableApps');
    return (res ?? const [])
        .whereType<Map>()
        .map((m) => (
              package: m['package'] as String? ?? '',
              label: m['label'] as String? ?? '',
            ))
        .where((a) => a.package.isNotEmpty)
        .toList();
  }
}
