import 'dart:async';

import 'package:flutter/services.dart';

import 'transport_client.dart';

/// EventChannel 上行给 Dart 的 action.request（contracts/v2 §7）。
class ActionRequestV2 {
  ActionRequestV2.fromMap(Map<String, dynamic> map)
      : actionId = map['action_id'] as String? ?? '',
        capability = map['capability'] as String? ?? '',
        // v2 payload 字段为 arguments；兼容读 args（v1 命名）。
        args = ((map['arguments'] ?? map['args']) as Map?)?.cast<String, dynamic>() ??
            const {},
        authorizedScope =
            (map['authorized_scope'] as List?)?.cast<String>() ?? const [],
        expiresAt = (map['expires_at'] as num?)?.toInt() ?? 0,
        idempotencyKey = map['idempotency_key'] as String? ?? '';

  final String actionId;
  final String capability;
  final Map<String, dynamic> args;
  final List<String> authorizedScope;
  final int expiresAt;
  final String idempotencyKey;
}

/// EventChannel 上行给 Dart 的 sensor.query（v2 面；字段与 v1 同名）。
class SensorQueryV2 {
  SensorQueryV2.fromMap(Map<String, dynamic> map)
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

/// 执行器处理的 action 终态回执（Kotlin 侧已上行，此事件仅供 UI 展示）。
class ActionReceiptV2 {
  ActionReceiptV2.fromMap(Map<String, dynamic> map)
      : actionId = map['action_id'] as String? ?? '',
        capability = map['capability'] as String? ?? '',
        status = map['status'] as String? ?? 'unknown',
        errorCode = map['error_code'] as String?;

  final String actionId;
  final String capability;

  /// v2 五终态之一（succeeded/failed/timeout/cancelled/unknown）。
  final String status;
  final String? errorCode;
}

/// 协议级错误（error 帧）。
class TransportErrorV2 {
  TransportErrorV2.fromMap(Map<String, dynamic> map)
      : code = map['code'] as String? ?? 'internal',
        message = map['message'] as String? ?? '',
        refId = map['ref_id'] as String? ?? '';

  final String code;
  final String message;
  final String refId;
}

/// contracts/v2 控制通道客户端的 Dart 封装（Kotlin 侧见 TransportBridgeV2）。
///
/// 通道：MethodChannel `whale/transport_v2` + EventChannel `whale/transport_v2/events`。
/// 本地数据查询面（notificationInbox.*/capture.*/favorites.*/backup.*/
/// getLaunchRoute）与模块配置面在 `whale/transport` 通道（见 transport_client.dart）。
class TransportClientV2 {
  TransportClientV2();

  static const MethodChannel _methods = MethodChannel('whale/transport_v2');
  static const EventChannel _events = EventChannel('whale/transport_v2/events');

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
  Stream<ActionRequestV2> get actionRequests => _eventStream
      .where((e) => e['kind'] == 'action_request')
      .map(ActionRequestV2.fromMap);

  /// 本地执行器终态回执流（通知模块等；仅 UI 展示，无需回复）。
  Stream<ActionReceiptV2> get actionReceipts => _eventStream
      .where((e) => e['kind'] == 'action_receipt')
      .map(ActionReceiptV2.fromMap);

  /// 下行感知查询流（必须经 sendSensorResult 回复，否则云端空等到 deadline）。
  Stream<SensorQueryV2> get sensorQueries => _eventStream
      .where((e) => e['kind'] == 'sensor_query')
      .map(SensorQueryV2.fromMap);

  /// 协议级错误流。
  Stream<TransportErrorV2> get errors => _eventStream
      .where((e) => e['kind'] == 'error')
      .map(TransportErrorV2.fromMap);

  /// 下行 call.invite / call.hangup 回显流（VoIP 模块已在原生侧消费，
  /// 此流仅供 UI 日志展示）。事件为原始 Map（session_id/expires_at/reason）。
  Stream<Map<String, dynamic>> get callSignalEvents => _eventStream
      .where((e) => e['kind'] == 'call_invite' || e['kind'] == 'call_hangup');

  /// 建立 v2 WSS 连接。返回 {device_id, public_key_hex}。
  /// capabilities/app_flavor/features 由原生侧 NodeRuntimeV2.buildHelloConfig 组装。
  Future<Map<String, dynamic>> connect(String url) async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>(
      'connectV2',
      <String, dynamic>{'url': url},
    );
    return res?.cast<String, dynamic>() ?? const {};
  }

  Future<void> disconnect() => _methods.invokeMethod<void>('disconnectV2');

  /// 上行 experience.event（§9）。返回 event_id（幂等键，evt_+ULID）。
  Future<String> sendExperienceEvent({
    required String kind,
    required String summary,
    int? occurredAt,
    String source = 'flutter',
    String privacyClass = 'owner_private',
    String? evidenceRef,
    String? taskId,
    String? runId,
    Map<String, dynamic>? extras,
    bool dropOnPause = false,
    String? eventId,
  }) async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>(
      'sendExperienceEvent',
      <String, dynamic>{
        'kind': kind,
        'summary': summary,
        'occurred_at': ?occurredAt,
        'source': source,
        'privacy_class': privacyClass,
        'evidence_ref': ?evidenceRef,
        'task_id': ?taskId,
        'run_id': ?runId,
        'extras': ?extras,
        'drop_on_pause': dropOnPause,
        'event_id': ?eventId,
      },
    );
    return res?['event_id'] as String? ?? '';
  }

  /// 回 action.receipt（v2 五终态；first-terminal-wins 由 ReceiptDispatcherV2 保证）。
  Future<void> sendActionReceipt(
    String actionId,
    String status, {
    String? errorCode,
    String? resultSummary,
    String? outputRef,
    String? providerRef,
  }) =>
      _methods.invokeMethod<void>('sendActionReceiptV2', <String, dynamic>{
        'action_id': actionId,
        'status': status,
        'error_code': ?errorCode,
        'result_summary': ?resultSummary,
        'output_ref': ?outputRef,
        'provider_ref': ?providerRef,
      });

  /// 回 sensor.result（sensor.query 的唯一回包入口；data 为条目数组）。
  Future<void> sendSensorResult({
    required String requestId,
    required String status, // ok / denied / unavailable / error / paused(全局暂停)
    required List<Map<String, dynamic>> data,
    bool truncated = false,
    String source = 'screenmemo.local',
  }) =>
      _methods.invokeMethod<void>('sendSensorResultV2', <String, dynamic>{
        'request_id': requestId,
        'status': status,
        'data': data,
        'truncated': truncated,
        'source': source,
      });

  /// 声明 Dart 侧接管 sensor.query 分发（默认 false = 原生 fail-closed）。
  Future<void> setSensorQueryDelegate(bool enabled) =>
      _methods.invokeMethod<void>('setSensorQueryDelegateV2', <String, dynamic>{
        'enabled': enabled,
      });

  /// 设置心跳上报的设备状态（online/connected_only/paused）。
  Future<void> setStatus(String status) =>
      _methods.invokeMethod<void>('setStatusV2', <String, dynamic>{
        'status': status,
      });

  /// 当前状态快照（含 device_id、session_id、outbox 队列统计）。
  Future<Map<String, dynamic>> getState() async {
    final res = await _methods.invokeMethod<Map<dynamic, dynamic>>('getStateV2');
    return res?.cast<String, dynamic>() ?? const {};
  }

  /// 设备身份（device_id + Ed25519 公钥 hex，用于配对登记）。
  Future<Map<String, dynamic>> getIdentity() async {
    final res =
        await _methods.invokeMethod<Map<dynamic, dynamic>>('getIdentityV2');
    return res?.cast<String, dynamic>() ?? const {};
  }
}
