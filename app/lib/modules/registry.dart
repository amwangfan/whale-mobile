import 'package:flutter/foundation.dart';

import '../transport/transport_client.dart';

/// 模块生命周期状态（A1；docs/MODULE-LIFECYCLE.md）。
///
/// 状态机主链：
///   installed → permissionRequired → disabled/enabled → starting → running
///   running → degraded / error → stopped
///
/// 全局暂停（allPaused）为最高优先级覆盖：任何模块一律收束到 stopped。
enum ModuleStatus {
  /// 已安装但未就绪：transport 从未连接过；screenmemo 未配置 token；权限状态未知。
  installed('已安装'),

  /// 有所需 Android 运行时权限未授予（notification_post: POST_NOTIFICATIONS）。
  permissionRequired('待授权'),

  /// 用户关闭了模块开关。
  disabled('已停用'),

  /// 开关已开、前置条件满足，但无持续运行态（事件驱动模块的就绪态）或尚未验证。
  enabled('已启用'),

  /// 正在启动（transport 连接/认证中）。
  starting('启动中'),

  /// 正常运行。
  running('运行中'),

  /// 运行但降级（transport 掉线重连中；screenmemo 数据库不可用）。
  degraded('降级运行'),

  /// 错误（ping 失败/鉴权失败/不可达等）。
  error('错误'),

  /// 已停止：transport 连接过又断开；或全局暂停收束。
  stopped('已停止');

  const ModuleStatus(this.label);

  final String label;
}

/// 模块隐私级（与 contracts/v1 privacy_class 对齐的三档）。
enum PrivacyLevel {
  public('公开'),
  ownerPrivate('机主隐私'),
  ownerSensitive('机主敏感');

  const PrivacyLevel(this.label);

  final String label;
}

/// ScreenMemo ping 结果归类（状态推导输入；UI 展示用更细的文本另存）。
enum ScreenMemoHealth {
  /// 已配置 token 但尚未 ping 过。
  unknown,

  /// 在线且数据库可用。
  ok,

  /// 在线但数据库不可用 → degraded。
  dbDown,

  /// 鉴权失败（token 错）→ error。
  authFailed,

  /// 不可达（ScreenMemo App 未运行）→ error。
  unreachable,

  /// 其他失败 → error。
  failure,
}

/// 模块注册表条目（A1 收口元数据 + 运行时状态）。
class ModuleSpec {
  ModuleSpec({
    required this.id,
    required this.displayName,
    required this.androidPermissions,
    required this.privacyLevel,
    required this.resident,
    required this.rootOptional,
    this.status = ModuleStatus.installed,
  });

  /// 模块 id（与原生 `whale_modules/<id>_enabled` 开关键一致）。
  final String id;

  /// 显示名称。
  final String displayName;

  /// 所需 Android 运行时权限（空 = 无系统权限需求）。
  final List<String> androidPermissions;

  /// 隐私级（该模块处理数据的最高隐私档）。
  final PrivacyLevel privacyLevel;

  /// 是否常驻（App 运行期间持续值守）。
  final bool resident;

  /// 是否可选 Root 增强（当前三个模块均不需要；为 voip 等预留）。
  final bool rootOptional;

  /// 当前状态（由 [ModuleRegistry] 推导写入，勿直接改）。
  ModuleStatus status;

  /// 最近错误（展示用；null = 无）。
  String? lastError;

  /// 最近回执摘要（展示用；null = 无）。
  String? lastReceipt;

  bool get needsRuntimePermission => androidPermissions.isNotEmpty;
}

// ---------------------------------------------------------------------------
// 状态推导（纯函数，单测全覆盖）
// ---------------------------------------------------------------------------

/// transport：从 TransportClient.stateStream 映射。
/// online→running、connecting/awaitingAuth→starting、degraded→degraded、
/// disconnected→stopped（连接过）/installed（从未连接）；开关关 → disabled。
ModuleStatus deriveTransportStatus({
  required TransportState? state,
  required bool everConnected,
  required bool enabled,
}) {
  if (!enabled) return ModuleStatus.disabled;
  switch (state) {
    case null:
    case TransportState.disconnected:
      return everConnected ? ModuleStatus.stopped : ModuleStatus.installed;
    case TransportState.connecting:
    case TransportState.awaitingAuth:
      return ModuleStatus.starting;
    case TransportState.online:
      return ModuleStatus.running;
    case TransportState.degraded:
      return ModuleStatus.degraded;
  }
}

/// notification_post：权限状态 + 模块开关（事件驱动，就绪态 = enabled）。
/// 校验顺序与状态机主链一致：权限未知→installed，未授予→permissionRequired，
/// 开关关→disabled，授予+开→enabled。
ModuleStatus deriveNotificationStatus({
  required bool? permissionGranted,
  required bool enabled,
}) {
  if (permissionGranted == null) return ModuleStatus.installed;
  if (!permissionGranted) return ModuleStatus.permissionRequired;
  return enabled ? ModuleStatus.enabled : ModuleStatus.disabled;
}

/// voip：权限状态 + 模块开关（事件驱动，就绪态 = enabled）。
/// 与 notification 推导同构；开关宿主默认关（B4：RECORD_AUDIO 等运行时权限）。
ModuleStatus deriveVoipStatus({
  required bool? permissionGranted,
  required bool enabled,
}) {
  if (permissionGranted == null) return ModuleStatus.installed;
  if (!permissionGranted) return ModuleStatus.permissionRequired;
  return enabled ? ModuleStatus.enabled : ModuleStatus.disabled;
}

/// notification_read：通知监听特殊访问权限 + 模块开关（B3；默认关）。
/// 与 voip 推导同构；权限为特殊访问（系统设置页授予），非运行时权限。
ModuleStatus deriveNotificationReadStatus({
  required bool? permissionGranted,
  required bool enabled,
}) {
  if (permissionGranted == null) return ModuleStatus.installed;
  if (!permissionGranted) return ModuleStatus.permissionRequired;
  return enabled ? ModuleStatus.enabled : ModuleStatus.disabled;
}

/// safe_action：无特殊系统权限项，纯开关模块（B3；默认关）。
/// 开关关 → disabled；开 → enabled（事件驱动，就绪态）。
ModuleStatus deriveSafeActionStatus({required bool enabled}) {
  return enabled ? ModuleStatus.enabled : ModuleStatus.disabled;
}

/// screenmemo：token 配置 + 模块开关 + ping 结果。
/// 无 token→installed（未配置）；开关关→disabled；已配置待验证→enabled；
/// ping 在线→running；数据库不可用→degraded；鉴权失败/不可达/其他→error。
ModuleStatus deriveScreenMemoStatus({
  required bool? hasToken,
  required bool enabled,
  required ScreenMemoHealth health,
}) {
  if (hasToken == null || !hasToken) return ModuleStatus.installed;
  if (!enabled) return ModuleStatus.disabled;
  switch (health) {
    case ScreenMemoHealth.unknown:
      return ModuleStatus.enabled;
    case ScreenMemoHealth.ok:
      return ModuleStatus.running;
    case ScreenMemoHealth.dbDown:
      return ModuleStatus.degraded;
    case ScreenMemoHealth.authFailed:
    case ScreenMemoHealth.unreachable:
    case ScreenMemoHealth.failure:
      return ModuleStatus.error;
  }
}

// ---------------------------------------------------------------------------
// 注册表（UI 单一事实来源；输入变化 → 推导 → notifyListeners）
// ---------------------------------------------------------------------------

/// 模块注册表：内置登记 transport / notification_post / screenmemo / voip /
/// notification_read / safe_action，
/// 汇聚各输入源（传输状态流、权限、开关、ping）推导 [ModuleSpec.status]。
///
/// 全局暂停 [setAllPaused] 为最高优先级：暂停期间所有模块 status = stopped。
class ModuleRegistry extends ChangeNotifier {
  ModuleRegistry({Map<String, ModuleSpec>? modules})
      : modules = modules ?? buildDefaultModules();

  /// 内置三模块登记（A1）。
  static Map<String, ModuleSpec> buildDefaultModules() =>
      <String, ModuleSpec>{
        'transport': ModuleSpec(
          id: 'transport',
          displayName: '传输通道',
          androidPermissions: const <String>[], // 无运行时权限需求
          privacyLevel: PrivacyLevel.public,
          resident: true, // 常驻：WSS 长连接 + 心跳
          rootOptional: false,
        ),
        'notification_post': ModuleSpec(
          id: 'notification_post',
          displayName: '通知推送',
          androidPermissions: const <String>['POST_NOTIFICATIONS'],
          privacyLevel: PrivacyLevel.ownerPrivate,
          resident: true, // 常驻：action 执行器随桥注册
          rootOptional: false,
        ),
        'screenmemo': ModuleSpec(
          id: 'screenmemo',
          displayName: 'ScreenMemo 屏幕记忆',
          androidPermissions: const <String>[], // 无系统权限，需 token + ScreenMemo App 存活
          privacyLevel: PrivacyLevel.ownerSensitive,
          resident: false, // 依赖外部 ScreenMemo App，非常驻
          rootOptional: false,
        ),
        'voip': ModuleSpec(
          id: 'voip',
          displayName: 'VoIP 通话',
          // RECORD_AUDIO（PCM 桥采音）+ READ_PHONE_STATE（部分 Telecom API）；
          // MANAGE_OWN_CALLS 为 normal 级声明即授予，不列入运行时权限。
          androidPermissions: const <String>['RECORD_AUDIO', 'READ_PHONE_STATE'],
          privacyLevel: PrivacyLevel.ownerSensitive, // 通话号码/状态
          resident: false, // 事件驱动：WSS call.invite / phone.call.ring 触发
          rootOptional: false,
        ),
        'notification_read': ModuleSpec(
          id: 'notification_read',
          displayName: '通知监听',
          // 特殊访问权限（系统「通知使用权」设置页授予），非运行时权限。
          androidPermissions: const <String>['通知监听'],
          privacyLevel: PrivacyLevel.ownerSensitive, // 通知标题属机主敏感面
          resident: false, // 事件驱动：NotificationListenerService 回调触发
          rootOptional: false,
        ),
        'safe_action': ModuleSpec(
          id: 'safe_action',
          displayName: '设备动作',
          androidPermissions: const <String>[], // 无特殊系统权限项
          privacyLevel: PrivacyLevel.ownerPrivate, // 启动 App/打开 http(s) 深链
          resident: false, // 事件驱动：action.request phone.device.safe_action
          rootOptional: false,
        ),
      };

  /// 已登记模块（key = module id）。
  final Map<String, ModuleSpec> modules;

  /// 全局暂停标志（与原生 whale_modules/all_paused 同步）。
  bool get allPaused => _allPaused;
  bool _allPaused = false;

  // ---- 推导输入（更新任一输入即重算） ----
  TransportState? _transportState;
  bool _transportEverConnected = false;
  bool _transportEnabled = true;
  bool? _notifPermissionGranted;
  bool _notifEnabled = true;
  bool? _screenMemoHasToken;
  bool _screenMemoEnabled = true;
  ScreenMemoHealth _screenMemoHealth = ScreenMemoHealth.unknown;
  bool? _voipPermissionGranted;
  bool _voipEnabled = false; // voip 宿主默认关（B4）
  bool? _notifReadPermissionGranted;
  bool _notifReadEnabled = false; // notification_read 默认关（B3，独立可选）
  bool _safeActionEnabled = false; // safe_action 默认关（B3）

  ModuleSpec operator [](String id) {
    final spec = modules[id];
    if (spec == null) {
      throw ArgumentError.value(id, 'id', '模块未登记');
    }
    return spec;
  }

  /// 传输状态流输入。观察到非 disconnected 即锁定「连接过」。
  void updateTransportState(TransportState state) {
    _transportState = state;
    if (state != TransportState.disconnected) _transportEverConnected = true;
    _recompute();
  }

  /// transport 模块开关（开 = 连接意图；关 = 断开并停用）。
  void setTransportEnabled(bool enabled) {
    _transportEnabled = enabled;
    _recompute();
  }

  /// 冷启动时注入「上次连接过」（SharedPreferences transport_was_connected）。
  void setTransportEverConnected(bool value) {
    _transportEverConnected = value;
    _recompute();
  }

  void updateNotification({bool? permissionGranted, bool? enabled}) {
    if (permissionGranted != null) _notifPermissionGranted = permissionGranted;
    if (enabled != null) _notifEnabled = enabled;
    _recompute();
  }

  void updateScreenMemo({
    bool? hasToken,
    bool? enabled,
    ScreenMemoHealth? health,
  }) {
    if (hasToken != null) _screenMemoHasToken = hasToken;
    if (enabled != null) _screenMemoEnabled = enabled;
    if (health != null) _screenMemoHealth = health;
    _recompute();
  }

  void updateVoip({bool? permissionGranted, bool? enabled}) {
    if (permissionGranted != null) _voipPermissionGranted = permissionGranted;
    if (enabled != null) _voipEnabled = enabled;
    _recompute();
  }

  void updateNotificationRead({bool? permissionGranted, bool? enabled}) {
    if (permissionGranted != null) {
      _notifReadPermissionGranted = permissionGranted;
    }
    if (enabled != null) _notifReadEnabled = enabled;
    _recompute();
  }

  void updateSafeAction({bool? enabled}) {
    if (enabled != null) _safeActionEnabled = enabled;
    _recompute();
  }

  /// 全局暂停覆盖：true 时所有模块收束 stopped；false 恢复各自推导态。
  void setAllPaused(bool paused) {
    _allPaused = paused;
    _recompute();
  }

  /// 记录模块最近错误（如协议错误、ping 失败详情）。
  void setLastError(String moduleId, String? message) {
    this[moduleId].lastError = message;
    notifyListeners();
  }

  /// 记录模块最近回执摘要（如通知 executed/failed）。
  void setLastReceipt(String moduleId, String receipt) {
    this[moduleId].lastReceipt = receipt;
    notifyListeners();
  }

  void _recompute() {
    if (_allPaused) {
      for (final spec in modules.values) {
        spec.status = ModuleStatus.stopped;
      }
    } else {
      this['transport'].status = deriveTransportStatus(
        state: _transportState,
        everConnected: _transportEverConnected,
        enabled: _transportEnabled,
      );
      this['notification_post'].status = deriveNotificationStatus(
        permissionGranted: _notifPermissionGranted,
        enabled: _notifEnabled,
      );
      this['screenmemo'].status = deriveScreenMemoStatus(
        hasToken: _screenMemoHasToken,
        enabled: _screenMemoEnabled,
        health: _screenMemoHealth,
      );
      this['voip'].status = deriveVoipStatus(
        permissionGranted: _voipPermissionGranted,
        enabled: _voipEnabled,
      );
      this['notification_read'].status = deriveNotificationReadStatus(
        permissionGranted: _notifReadPermissionGranted,
        enabled: _notifReadEnabled,
      );
      this['safe_action'].status = deriveSafeActionStatus(
        enabled: _safeActionEnabled,
      );
    }
    notifyListeners();
  }
}
