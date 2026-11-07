import 'dart:async';

import 'package:flutter/services.dart';

/// 传输层连接状态（与 Kotlin 侧 WssClientV2.State 经 stateName 映射的名称一一对应；
/// v1/v2 状态词相同）。
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

/// `whale/transport` 通道客户端（Kotlin 侧见 TransportBridge 瘦身版）。
///
/// v1 协议控制面（connect/sendSensorEvent/sensor.query 委托/事件流等）已随
/// v1 协议栈整体下线，控制面唯一通道为 `whale/transport_v2`
/// （见 transport_client_v2.dart）。本客户端只保留**与协议版本无关**的
/// 模块配置面（模块开关/全局暂停/权限/桥配置/采集/自主体/MCP token）。
///
/// 本地数据查询面（notificationInbox.*/capture.*/favorites.*/backup.*/
/// getLaunchRoute）由 features/ 下各仓库直接用 MethodChannel('whale/transport')
/// 调用，不经本类。
class TransportClient {
  TransportClient();

  static const MethodChannel _methods = MethodChannel('whale/transport');

  /// 保存 ScreenMemo MCP Bearer token（Keystore 加密落盘；空串清除）。
  Future<void> setMcpToken(String token) =>
      _methods.invokeMethod<void>('setMcpToken', <String, dynamic>{
        'token': token,
      });

  /// 读取已保存的 MCP token；未配置或解密失败返回 null。
  Future<String?> getMcpToken() =>
      _methods.invokeMethod<String?>('getMcpToken');

  // ---- 模块开关（通用；原生侧存 whale_modules/<module>_enabled，默认 true）----

  /// 写模块开关。module 形如 `notification_post`（小写字母/数字/下划线）。
  Future<void> setModuleEnabled(String module, bool enabled) =>
      _methods.invokeMethod<void>('setModuleEnabled', <String, dynamic>{
        'module': module,
        'enabled': enabled,
      });

  /// 读全部已知模块开关。
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
  Future<void> setVoipBridgeConfig(String host, int port, {String? token}) =>
      _methods.invokeMethod<void>('setVoipBridgeConfig', <String, dynamic>{
        'host': host,
        'port': port,
        'token': ?token,
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

  // ---- 设备自主体（core#58；默认全关） ----

  /// 状态：enabled/root_authorized/estop。
  Future<Map<String, dynamic>> getAgentStatus() async =>
      Map<String, dynamic>.from(await _methods
              .invokeMethod<Map<dynamic, dynamic>>('getAgentStatus') ??
          <String, dynamic>{});

  Future<void> setAgentEnabled(bool enabled) => _methods
      .invokeMethod<void>('setAgentEnabled', <String, dynamic>{'enabled': enabled});

  /// 「持续 Root Shell 授权」——control/observe/shell 全部走 root 路径，
  /// 未授权一律 scope_denied。
  Future<void> setAgentRootAuthorized(bool enabled) =>
      _methods.invokeMethod<void>(
          'setAgentRootAuthorized', <String, dynamic>{'enabled': enabled});

  /// 紧急停止：销毁全部会话/在途命令，之后 agent 请求一律拒绝。
  Future<void> agentEmergencyStop() =>
      _methods.invokeMethod<void>('agentEmergencyStop');

  /// 解除紧急停止（用户明确操作）。
  Future<void> agentClearEstop() =>
      _methods.invokeMethod<void>('agentClearEstop');

  /// SM-01 影子采集模块状态（enabled/granted/conflict/running/stats）。
  Future<Map<String, dynamic>> getCaptureStatus() async =>
      Map<String, dynamic>.from(await _methods
              .invokeMethod<Map<dynamic, dynamic>>('getCaptureStatus') ??
          <String, dynamic>{});

  /// SM-01 影子采集开关（默认关；与独立 ScreenMemo 采集互斥）。
  Future<void> setCaptureEnabled(bool enabled) => _methods
      .invokeMethod<void>('setCaptureEnabled', <String, dynamic>{'enabled': enabled});

  /// 跳系统无障碍设置页（采集服务授权入口）。
  Future<void> openAccessibilitySettings() =>
      _methods.invokeMethod<void>('openAccessibilitySettings');

  /// SM-02/03：持久化开关（默认 false=纯影子）。
  Future<void> setCapturePersistEnabled(bool enabled) =>
      _methods.invokeMethod<void>(
          'setCapturePersistEnabled', <String, dynamic>{'enabled': enabled});

  /// SM-03：数据源切换（mcp|builtin）；返回 false=前置条件不满足。
  Future<bool> setCaptureDataSource(String source) async =>
      await _methods.invokeMethod<bool>(
          'setCaptureDataSource', <String, dynamic>{'source': source}) ??
      false;

  /// SM-02/03：从导出目录增量导入 ScreenMemo 数据，返回导入报告。
  Future<Map<String, dynamic>> importScreenMemoData(String basePath) async =>
      Map<String, dynamic>.from(await _methods.invokeMethod<Map<dynamic, dynamic>>(
              'importScreenMemoData',
              <String, dynamic>{'base_path': basePath}) ??
          <String, dynamic>{});

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
