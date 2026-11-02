import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'modules/notification_read_apps_page.dart';
import 'modules/registry.dart';
import 'modules/screenmemo/event_poller.dart';
import 'modules/screenmemo/mcp_client.dart';
import 'modules/screenmemo/screenmemo_module.dart';
import 'transport/transport_client.dart';

void main() {
  runApp(const WhaleApp());
}

class WhaleApp extends StatelessWidget {
  const WhaleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Whale',
      theme: ThemeData(colorScheme: .fromSeed(seedColor: Colors.teal)),
      home: const ModuleOverviewPage(),
    );
  }
}

/// A1 模块总览主页：顶部传输状态条 + 每模块一张卡片 + 可折叠连接测试/日志区。
class ModuleOverviewPage extends StatefulWidget {
  const ModuleOverviewPage({super.key});

  @override
  State<ModuleOverviewPage> createState() => _ModuleOverviewPageState();
}

class _ModuleOverviewPageState extends State<ModuleOverviewPage> {
  final TransportClient _transport = TransportClient();
  late final ScreenMemoModule _screenMemo =
      ScreenMemoModule(transport: _transport);
  final ModuleRegistry _registry = ModuleRegistry();
  final TextEditingController _urlController =
      TextEditingController(text: _defaultGatewayUrl);
  final TextEditingController _mcpTokenController = TextEditingController();

  /// 默认网关地址可用 `--dart-define=WM_GATEWAY_URL=ws://host:port` 覆盖，
  /// 便于真机联调时注入 tailnet/内网地址而不入库。
  static const String _defaultGatewayUrl = String.fromEnvironment(
    'WM_GATEWAY_URL',
    defaultValue: 'ws://10.0.2.2:8765',
  );

  TransportState _state = TransportState.disconnected;
  String _deviceId = '…';
  String _publicKeyHex = '';
  String? _sessionId;
  Map<String, dynamic> _queue = const {};
  final List<String> _log = <String>[];
  StreamSubscription<TransportState>? _stateSub;
  StreamSubscription<ActionRequest>? _actionSub;
  StreamSubscription<TransportError>? _errorSub;
  StreamSubscription<ActionReceipt>? _receiptSub;
  StreamSubscription<Map<String, dynamic>>? _callSignalSub;

  // ---- 一键暂停（A1；whale_modules/all_paused 落原生侧）----
  bool _allPaused = false;

  /// 暂停前的开关快照（恢复时逆向还原）。
  Map<String, bool>? _prePauseToggles;

  // ---- 通知模块（WM-06 阶段一：post/update/cancel）----
  bool _notifEnabled = true; // 与原生 whale_modules/notification_post_enabled 同步
  bool? _notifPermissionGranted; // null = 未知（原生侧不可用）

  // ---- VoIP 模块（B4 前半：phone.call.ring + call.* 信令，媒体走 PCM 桥）----
  bool _voipEnabled = false; // 宿主默认关（whale_modules/voip_enabled）
  bool? _voipPermissionGranted; // null = 未知（原生侧不可用）
  final TextEditingController _bridgeHostController = TextEditingController();
  final TextEditingController _bridgePortController = TextEditingController();

  // ---- 通知监听模块（B3 前半：phone.notification.read，独立可选默认关）----
  bool _notifReadEnabled = false; // whale_modules/notification_read_enabled
  bool? _notifReadPermissionGranted; // null = 未知（原生侧不可用）

  // ---- 受控设备动作模块（B3 前半：phone.device.safe_action，默认关）----
  bool _safeActionEnabled = false; // whale_modules/safe_action_enabled

  // ---- ScreenMemo（WM-04）----
  bool _screenMemoEnabled = true; // 模块开关默认开
  String _mcpStatus = '未测试';

  // ---- ScreenMemo 事件导出（B1：poll_events → sensor.event）----
  /// 本端上传开关（SharedPreferences 键 screenmemo_event_export，默认关）。
  /// 注意与 ScreenMemo App 内「事件导出（供 Whale）」是两个层面：
  /// 那边控制事件产生，这边控制上传。
  static const String _eventExportKey = 'screenmemo_event_export';
  bool _eventExportEnabled = false;
  ScreenMemoEventPoller? _eventPoller;

  // ---- transport 模块开关（开 = 连接意图）----
  bool _transportEnabled = true;

  @override
  void initState() {
    super.initState();
    _stateSub = _transport.stateStream.listen((s) {
      setState(() => _state = s);
      _registry.updateTransportState(s);
      _refreshState();
    }, onError: (Object _) {/* 通道异常不致命 */});
    _actionSub = _transport.actionRequests.listen((req) {
      _appendLog('action.request: ${req.capability} id=${req.actionId}');
      // 无原生执行器的 capability：阶段一演示仅回执 accepted，不真正执行。
      _transport.sendActionReceipt(req.actionId, 'accepted');
    }, onError: (Object _) {});
    _receiptSub = _transport.actionReceipts.listen((r) {
      final label =
          r.state == 'executed' ? 'executed' : 'failed(${r.errorCode ?? '-'})';
      if (r.capability == 'phone.notification.post') {
        _registry.setLastReceipt(
            'notification_post', '$label  id=${r.actionId.substring(0, 8)}…');
        _appendLog('notification receipt: $label');
      } else if (r.capability == 'phone.call.ring') {
        _registry.setLastReceipt(
            'voip', '$label  id=${r.actionId.substring(0, 8)}…');
        _appendLog('voip ring receipt: $label');
      } else if (r.capability == 'phone.device.safe_action') {
        _registry.setLastReceipt(
            'safe_action', '$label  id=${r.actionId.substring(0, 8)}…');
        _appendLog('safe_action receipt: $label');
      }
    }, onError: (Object _) {});
    _callSignalSub = _transport.callSignalEvents.listen((e) {
      _appendLog('${e['kind']}: session=${e['session_id'] ?? '-'}');
    }, onError: (Object _) {});
    _errorSub = _transport.errors.listen((e) {
      _registry.setLastError('transport', '${e.code} ${e.message}');
      _appendLog('error: ${e.code} ${e.message}');
    }, onError: (Object _) {});
    _loadIdentity();
    // 先读全局暂停标志，再初始化模块与自动回连（暂停时不自动联网）。
    unawaited(_initPauseFlag().then((_) {
      _initNotifications();
      _initVoip();
      _initNotificationRead();
      _initSafeAction();
      _initScreenMemo();
      _initEventExport();
      _maybeAutoConnect();
    }));
  }

  /// 冷启动读取原生 whale_modules/all_paused：暂停中则保持暂停态，不自动联网。
  Future<void> _initPauseFlag() async {
    try {
      final paused = await _transport.isAllPaused();
      if (!mounted || !paused) return;
      setState(() {
        _allPaused = true;
        _transportEnabled = false;
        _notifEnabled = false;
        _screenMemoEnabled = false;
        _voipEnabled = false;
        _notifReadEnabled = false;
        _safeActionEnabled = false;
      });
      _registry.setAllPaused(true);
      _appendLog('检测到全局暂停标志：保持暂停（点右上角恢复）');
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
  }

  /// 应用重启后自动回连：仅当上次是用户主动连接过（未手动断开）且未全局暂停。
  /// 一键暂停/手动断开会把标志置 false，重启后不自动联网（A1 可暂停原则）。
  Future<void> _maybeAutoConnect() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final wasConnected = prefs.getBool('transport_was_connected') ?? false;
      _registry.setTransportEverConnected(wasConnected);
      if (wasConnected && !_allPaused && mounted) {
        _appendLog('自动回连（上次在线）');
        await _connect();
      }
    } catch (_) {
      // 测试环境无插件
    }
  }

  Future<void> _markConnected(bool value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('transport_was_connected', value);
    } catch (_) {}
  }

  // ---------------------------------------------------------------- 一键暂停

  /// 一键暂停：断开传输 + 关闭所有模块开关 + all_paused 落原生侧。
  /// 原生执行器校验链前置检查该标志：action 回 unavailable、sensor.query 回 paused。
  Future<void> _pauseAll() async {
    if (_allPaused) return;
    _prePauseToggles = <String, bool>{
      'transport':
          _transportEnabled && _state != TransportState.disconnected,
      'notification_post': _notifEnabled,
      'screenmemo': _screenMemoEnabled,
      'voip': _voipEnabled,
      'notification_read': _notifReadEnabled,
      'safe_action': _safeActionEnabled,
    };
    setState(() {
      _allPaused = true;
      _transportEnabled = false;
      _notifEnabled = false;
      _screenMemoEnabled = false;
      _voipEnabled = false;
      _notifReadEnabled = false;
      _safeActionEnabled = false;
    });
    _registry.updateNotification(enabled: false);
    _registry.updateScreenMemo(enabled: false);
    _registry.updateVoip(enabled: false);
    _registry.updateNotificationRead(enabled: false);
    _registry.updateSafeAction(enabled: false);
    _registry.setTransportEnabled(false);
    _registry.setAllPaused(true);
    _appendLog('一键暂停：断开传输，关闭全部模块');
    try {
      await _transport.setAllPaused(true);
      await _transport.setModuleEnabled('notification_post', false);
      await _transport.setModuleEnabled('screenmemo', false);
      await _transport.setModuleEnabled('voip', false);
      await _transport.setModuleEnabled('notification_read', false);
      await _transport.setModuleEnabled('safe_action', false);
      await _screenMemo.setEnabled(false);
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
    await _disconnect();
  }

  /// 恢复：逆向一键暂停——清 all_paused、按暂停前快照还原开关、必要时回连。
  Future<void> _resumeAll() async {
    if (!_allPaused) return;
    final restore = _prePauseToggles ??
        const {
          'transport': true,
          'notification_post': true,
          'screenmemo': true,
          'voip': false, // voip 默认关：无快照时不自动恢复
          'notification_read': false, // 默认关（B3）：无快照时不自动恢复
          'safe_action': false, // 默认关（B3）：无快照时不自动恢复
        };
    _prePauseToggles = null;
    final restoreTransport = restore['transport'] ?? true;
    final restoreNotif = restore['notification_post'] ?? true;
    final restoreScreenMemo = restore['screenmemo'] ?? true;
    final restoreVoip = restore['voip'] ?? false;
    final restoreNotifRead = restore['notification_read'] ?? false;
    final restoreSafeAction = restore['safe_action'] ?? false;
    setState(() {
      _allPaused = false;
      _transportEnabled = restoreTransport;
      _notifEnabled = restoreNotif;
      _screenMemoEnabled = restoreScreenMemo;
      _voipEnabled = restoreVoip;
      _notifReadEnabled = restoreNotifRead;
      _safeActionEnabled = restoreSafeAction;
    });
    _registry.setAllPaused(false);
    _registry.setTransportEnabled(restoreTransport);
    _registry.updateNotification(enabled: restoreNotif);
    _registry.updateScreenMemo(enabled: restoreScreenMemo);
    _registry.updateVoip(enabled: restoreVoip);
    _registry.updateNotificationRead(enabled: restoreNotifRead);
    _registry.updateSafeAction(enabled: restoreSafeAction);
    _appendLog('恢复全部：模块开关还原${restoreTransport ? '，回连传输' : ''}');
    try {
      await _transport.setAllPaused(false);
      await _transport.setModuleEnabled('notification_post', restoreNotif);
      await _transport.setModuleEnabled('screenmemo', restoreScreenMemo);
      await _transport.setModuleEnabled('voip', restoreVoip);
      await _transport.setModuleEnabled('notification_read', restoreNotifRead);
      await _transport.setModuleEnabled('safe_action', restoreSafeAction);
      await _screenMemo.setEnabled(restoreScreenMemo);
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
    if (restoreTransport) await _connect();
  }

  // ---------------------------------------------------------------- 通知模块

  Future<void> _initNotifications() async {
    try {
      final states = await _transport.getModuleStates();
      final perm = await _transport.getNotificationPermissionStatus();
      if (!mounted) return;
      setState(() {
        _notifEnabled = states['notification_post'] ?? true;
        _notifPermissionGranted = perm == 'granted';
      });
      _registry.updateNotification(
        permissionGranted: _notifPermissionGranted,
        enabled: _notifEnabled,
      );
    } catch (_) {
      // 原生侧不可用（单元测试环境）：权限未知 → installed
    }
  }

  Future<void> _toggleNotifications(bool value) async {
    setState(() => _notifEnabled = value);
    _registry.updateNotification(enabled: value);
    try {
      await _transport.setModuleEnabled('notification_post', value);
      _appendLog('通知模块 ${value ? '开启' : '关闭'}');
    } catch (e) {
      _appendLog('通知模块开关失败: $e');
    }
  }

  Future<void> _requestNotifPermission() async {
    try {
      await _transport.requestNotificationPermission();
      // 系统权限弹窗为异步；延迟重查一次状态（真机验证项）。
      await Future<void>.delayed(const Duration(milliseconds: 1200));
      final perm = await _transport.getNotificationPermissionStatus();
      if (!mounted) return;
      setState(() => _notifPermissionGranted = perm == 'granted');
      _registry.updateNotification(permissionGranted: perm == 'granted');
      _appendLog('通知权限申请后状态: $perm');
    } catch (e) {
      _appendLog('通知权限申请失败: $e');
    }
  }

  // ---------------------------------------------------------------- VoIP（B4）

  Future<void> _initVoip() async {
    try {
      final states = await _transport.getModuleStates();
      final perm = await _transport.getVoipPermissionStatus();
      final bridge = await _transport.getVoipBridgeConfig();
      if (!mounted) return;
      setState(() {
        _voipEnabled = states['voip'] ?? false; // voip 默认关
        _voipPermissionGranted = perm == 'granted';
        _bridgeHostController.text = bridge['host'] as String? ?? '127.0.0.1';
        _bridgePortController.text = '${bridge['port'] ?? 18799}';
      });
      _registry.updateVoip(
        permissionGranted: _voipPermissionGranted,
        enabled: _voipEnabled,
      );
    } catch (_) {
      // 原生侧不可用（单元测试环境）：权限未知 → installed
    }
  }

  Future<void> _toggleVoip(bool value) async {
    setState(() => _voipEnabled = value);
    _registry.updateVoip(enabled: value);
    try {
      await _transport.setModuleEnabled('voip', value);
      _appendLog('VoIP 模块 ${value ? '开启' : '关闭'}');
    } catch (e) {
      _appendLog('VoIP 模块开关失败: $e');
    }
  }

  Future<void> _requestVoipPermission() async {
    try {
      await _transport.requestVoipPermissions();
      // 系统权限弹窗为异步；延迟重查一次状态（真机验证项）。
      await Future<void>.delayed(const Duration(milliseconds: 1200));
      final perm = await _transport.getVoipPermissionStatus();
      if (!mounted) return;
      setState(() => _voipPermissionGranted = perm == 'granted');
      _registry.updateVoip(permissionGranted: perm == 'granted');
      _appendLog('VoIP 权限申请后状态: $perm');
    } catch (e) {
      _appendLog('VoIP 权限申请失败: $e');
    }
  }

  Future<void> _saveVoipBridgeConfig() async {
    final host = _bridgeHostController.text.trim();
    final port = int.tryParse(_bridgePortController.text.trim()) ?? 0;
    try {
      await _transport.setVoipBridgeConfig(host, port);
      _appendLog('PCM 桥配置已保存: $host:$port');
    } catch (e) {
      _appendLog('PCM 桥配置保存失败: $e');
    }
  }

  // ------------------------------------------- 通知监听（B3，默认关）

  Future<void> _initNotificationRead() async {
    try {
      final states = await _transport.getModuleStates();
      final perm = await _transport.getNotificationReadPermissionStatus();
      if (!mounted) return;
      setState(() {
        _notifReadEnabled = states['notification_read'] ?? false; // 默认关
        _notifReadPermissionGranted = perm == 'granted';
      });
      _registry.updateNotificationRead(
        permissionGranted: _notifReadPermissionGranted,
        enabled: _notifReadEnabled,
      );
    } catch (_) {
      // 原生侧不可用（单元测试环境）：权限未知 → installed
    }
  }

  Future<void> _toggleNotificationRead(bool value) async {
    setState(() => _notifReadEnabled = value);
    _registry.updateNotificationRead(enabled: value);
    try {
      await _transport.setModuleEnabled('notification_read', value);
      _appendLog('通知监听模块 ${value ? '开启' : '关闭'}');
    } catch (e) {
      _appendLog('通知监听模块开关失败: $e');
    }
  }

  /// 「去授权」：跳系统通知监听设置页；返回后延迟重查权限状态。
  Future<void> _openNotificationListenerSettings() async {
    try {
      await _transport.openNotificationListenerSettings();
      _appendLog('已打开系统通知监听设置页');
      // 用户从设置页返回后重查（fire-and-forget；真机验证项）。
      await Future<void>.delayed(const Duration(seconds: 3));
      final perm = await _transport.getNotificationReadPermissionStatus();
      if (!mounted) return;
      setState(() => _notifReadPermissionGranted = perm == 'granted');
      _registry.updateNotificationRead(permissionGranted: perm == 'granted');
    } catch (e) {
      _appendLog('打开通知监听设置失败: $e');
    }
  }

  Future<void> _openNotificationReadWhitelist() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => NotificationReadAppsPage(transport: _transport),
      ),
    );
  }

  // ------------------------------------------- 受控设备动作（B3，默认关）

  Future<void> _initSafeAction() async {
    try {
      final states = await _transport.getModuleStates();
      if (!mounted) return;
      setState(() {
        _safeActionEnabled = states['safe_action'] ?? false; // 默认关
      });
      _registry.updateSafeAction(enabled: _safeActionEnabled);
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
  }

  Future<void> _toggleSafeAction(bool value) async {
    setState(() => _safeActionEnabled = value);
    _registry.updateSafeAction(enabled: value);
    try {
      await _transport.setModuleEnabled('safe_action', value);
      _appendLog('设备动作模块 ${value ? '开启' : '关闭'}');
    } catch (e) {
      _appendLog('设备动作模块开关失败: $e');
    }
  }

  // ---------------------------------------------------------------- ScreenMemo

  Future<void> _initScreenMemo() async {
    await _screenMemo.init();
    await _screenMemo.setEnabled(_screenMemoEnabled);
    try {
      var has = await _screenMemo.hasToken();
      // 联调便利：无已存 token 时允许用 --dart-define=WM_MCP_TOKEN=... 注入（不入库、不上日志）
      if (!has && _devMcpToken.isNotEmpty) {
        await _screenMemo.setToken(_devMcpToken);
        has = true;
      }
      if (mounted) {
        _registry.updateScreenMemo(hasToken: has, enabled: _screenMemoEnabled);
      }
    } catch (_) {
      // 原生侧不可用（单元测试环境）：token 未知 → installed
    }
  }

  /// 联调注入用 MCP token（默认空；生产 UI 走手动输入）。
  static const String _devMcpToken = String.fromEnvironment(
    'WM_MCP_TOKEN',
    defaultValue: '',
  );

  Future<void> _saveMcpToken() async {
    final token = _mcpTokenController.text.trim();
    if (token.isEmpty) return;
    try {
      await _screenMemo.setToken(token);
      _mcpTokenController.clear();
      if (!mounted) return;
      setState(() => _mcpStatus = 'token 已保存（未测试）');
      _registry.updateScreenMemo(hasToken: true);
      _registry.setLastError('screenmemo', null);
      _appendLog('ScreenMemo token 已加密保存');
    } catch (e) {
      _appendLog('保存 token 失败: $e');
    }
  }

  Future<void> _pingScreenMemo() async {
    setState(() => _mcpStatus = '测试中…');
    try {
      final status = await _screenMemo.ping();
      final db = status['database'];
      final dbOk = db is Map && db['ok'] == true;
      final segments = db is Map ? db['segment_count'] : null;
      if (!mounted) return;
      setState(() {
        _mcpStatus = dbOk ? '在线（segments=$segments）' : '在线（数据库不可用）';
      });
      _registry.updateScreenMemo(
        health: dbOk ? ScreenMemoHealth.ok : ScreenMemoHealth.dbDown,
      );
      _registry.setLastError('screenmemo', null);
      _appendLog('screenmemo_status → $_mcpStatus');
    } on McpException catch (e) {
      if (!mounted) return;
      setState(() => _mcpStatus = e.kind == McpErrorKind.denied
          ? '鉴权失败（检查 token）'
          : '不可达（ScreenMemo 未运行？）');
      _registry.updateScreenMemo(
        health: e.kind == McpErrorKind.denied
            ? ScreenMemoHealth.authFailed
            : ScreenMemoHealth.unreachable,
      );
      _registry.setLastError('screenmemo', e.message);
      _appendLog('screenmemo_status 失败: ${e.message}');
    } catch (e) {
      if (!mounted) return;
      setState(() => _mcpStatus = '失败: $e');
      _registry.updateScreenMemo(health: ScreenMemoHealth.failure);
      _registry.setLastError('screenmemo', '$e');
    }
  }

  Future<void> _toggleScreenMemo(bool value) async {
    setState(() => _screenMemoEnabled = value);
    _registry.updateScreenMemo(enabled: value);
    await _screenMemo.setEnabled(value);
    try {
      await _transport.setModuleEnabled('screenmemo', value);
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
    _appendLog('ScreenMemo 模块 ${value ? '开启' : '关闭'}');
  }

  // ------------------------------------------------------ ScreenMemo 事件导出

  ScreenMemoEventPoller _createEventPoller() {
    final p = ScreenMemoEventPoller(
      client: _screenMemo.mcpClient,
      sendEvent: _transport.sendSensorEvent,
      transportStates: _transport.stateStream,
      isAllPaused: () async => _allPaused,
      hasToken: _screenMemo.hasToken,
      moduleEnabled: () => _screenMemoEnabled,
      initialTransportState: _state,
      logger: _appendLog,
    );
    p.addListener(_onEventPollerUpdate);
    return p;
  }

  void _onEventPollerUpdate() {
    if (mounted) setState(() {});
  }

  /// 冷启动恢复：上次开关为开则自动启动轮询器（从持久化游标续传）。
  Future<void> _initEventExport() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final enabled = prefs.getBool(_eventExportKey) ?? false;
      if (!mounted || !enabled) return;
      setState(() => _eventExportEnabled = true);
      _eventPoller = _createEventPoller();
      unawaited(_eventPoller!.start());
      _appendLog('事件导出已随启动恢复');
    } catch (_) {
      // 测试环境无插件
    }
  }

  Future<void> _toggleEventExport(bool value) async {
    setState(() => _eventExportEnabled = value);
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_eventExportKey, value);
    } catch (_) {
      // 测试环境无插件
    }
    if (value) {
      _eventPoller ??= _createEventPoller();
      unawaited(_eventPoller!.start());
      _appendLog('事件导出已开启（15s 轮询 poll_events）');
    } else {
      await _eventPoller?.stop();
      _appendLog('事件导出已关闭（游标保留，重开续传）');
    }
  }

  /// 导出状态行：游标位置 / 已上传计数 / 最近事件时间。
  String get _eventExportStatus {
    final p = _eventPoller;
    if (!_eventExportEnabled) return '关（不产生上行流量）';
    if (p == null) return '启动中…';
    final last = p.lastEventAtMs;
    final lastText = last == null
        ? '-'
        : DateTime.fromMillisecondsSinceEpoch(last)
            .toLocal()
            .toString()
            .substring(0, 19);
    var text = '游标 #${p.cursor} · 已上传 ${p.uploadedCount} · 最近事件 $lastText';
    if (p.consecutiveFailures > 0) {
      text += ' · 连续失败 ${p.consecutiveFailures}（退避中）';
    }
    return text;
  }

  // ---------------------------------------------------------------- 传输

  Future<void> _loadIdentity() async {
    try {
      final id = await _transport.getIdentity();
      if (!mounted) return;
      setState(() {
        _deviceId = id['device_id'] as String? ?? 'unknown';
        _publicKeyHex = id['public_key_hex'] as String? ?? '';
      });
    } catch (_) {
      if (mounted) setState(() => _deviceId = '（原生侧不可用）');
    }
  }

  Future<void> _refreshState() async {
    try {
      final s = await _transport.getState();
      if (!mounted) return;
      setState(() {
        _sessionId = s['session_id'] as String?;
        _queue = <String, dynamic>{
          'pending': s['queue_pending'],
          'sent': s['queue_sent'],
          'acked': s['queue_acked'],
          'dropped': s['queue_dropped'],
        };
      });
    } catch (_) {
      // 原生侧尚未就绪（如单元测试环境），忽略
    }
  }

  /// transport 模块开关：开 = 连接，关 = 断开（任务书：开关即连接/断开）。
  Future<void> _toggleTransport(bool value) async {
    setState(() => _transportEnabled = value);
    _registry.setTransportEnabled(value);
    if (value) {
      await _connect();
    } else {
      await _disconnect();
    }
  }

  Future<void> _connect() async {
    try {
      final id = await _transport.connect(_urlController.text.trim());
      setState(() {
        _deviceId = id['device_id'] as String? ?? _deviceId;
        _publicKeyHex = id['public_key_hex'] as String? ?? _publicKeyHex;
      });
      _appendLog('connect → ${_urlController.text.trim()}');
      unawaited(_markConnected(true));
    } catch (e) {
      _appendLog('connect 失败: $e');
    }
  }

  Future<void> _disconnect() async {
    try {
      await _transport.disconnect();
    } catch (_) {
      // 原生侧不可用（单元测试环境）
    }
    unawaited(_markConnected(false));
    _appendLog('disconnect');
  }

  Future<void> _sendTestEvent() async {
    try {
      final eventId = await _transport.sendSensorEvent(
        kind: 'debug.ping',
        summary: 'manual test event ${DateTime.now().toIso8601String()}',
        privacyClass: 'public',
      );
      _appendLog('sensor.event 已入队 event_id=$eventId');
      _refreshState();
    } catch (e) {
      _appendLog('sendSensorEvent 失败: $e');
    }
  }

  void _appendLog(String line) {
    if (!mounted) return;
    setState(() {
      _log.insert(
          0, '[${DateTime.now().toLocal().toString().substring(11, 19)}] $line');
      if (_log.length > 50) _log.removeLast();
    });
  }

  @override
  void dispose() {
    _stateSub?.cancel();
    _actionSub?.cancel();
    _errorSub?.cancel();
    _receiptSub?.cancel();
    _callSignalSub?.cancel();
    _urlController.dispose();
    _mcpTokenController.dispose();
    _bridgeHostController.dispose();
    _bridgePortController.dispose();
    _registry.dispose();
    _eventPoller?.dispose();
    _screenMemo.dispose();
    super.dispose();
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    final online = _state == TransportState.online;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Whale 模块总览'),
        actions: [
          IconButton(
            tooltip: _allPaused ? '恢复全部' : '一键暂停',
            icon: Icon(
              _allPaused ? Icons.play_arrow : Icons.pause,
              color: _allPaused ? Colors.red : null,
            ),
            onPressed: _allPaused ? _resumeAll : _pauseAll,
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: _registry,
        builder: (context, _) {
          return ListView(
            padding: const EdgeInsets.all(12),
            children: [
              _transportBanner(),
              if (_allPaused) _pausedBanner(),
              const SizedBox(height: 8),
              _buildTransportCard(online),
              _buildNotificationCard(),
              _buildNotificationReadCard(),
              _buildVoipCard(),
              _buildSafeActionCard(),
              _buildScreenMemoCard(),
              const SizedBox(height: 8),
              _connectTestSection(online),
              _logSection(),
            ],
          );
        },
      ),
    );
  }

  /// 顶部传输状态条。
  Widget _transportBanner() {
    final color = _transportColor(_state);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(Icons.circle, size: 12, color: color),
          const SizedBox(width: 8),
          Text(
            '传输：${_state.label}',
            style: TextStyle(fontWeight: FontWeight.bold, color: color),
          ),
          const Spacer(),
          Text(
            '队列 p${_queue['pending'] ?? 0} s${_queue['sent'] ?? 0} '
            'a${_queue['acked'] ?? 0} d${_queue['dropped'] ?? 0}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }

  Widget _pausedBanner() {
    return Container(
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.red.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.red.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          const Icon(Icons.pause_circle, color: Colors.red),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '已全局暂停：传输断开，action 回 unavailable、query 回 paused',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
          TextButton(onPressed: _resumeAll, child: const Text('恢复全部')),
        ],
      ),
    );
  }

  Widget _buildTransportCard(bool online) {
    final spec = _registry['transport'];
    return _moduleCard(
      spec: spec,
      icon: Icons.swap_vertical_circle,
      subtitle: 'WSS 控制通道 · 心跳上报 · 离线队列',
      switchValue: _transportEnabled,
      onToggle: _toggleTransport,
      children: [
        _row('状态', _state.label),
        if (spec.lastError != null)
          _errorLine('最近错误: ${spec.lastError}'),
      ],
    );
  }

  Widget _buildNotificationCard() {
    final spec = _registry['notification_post'];
    return _moduleCard(
      spec: spec,
      icon: Icons.notifications,
      subtitle: 'phone.notification.post（云端下发通知）',
      switchValue: _notifEnabled,
      onToggle: _toggleNotifications,
      children: [
        _row('通知权限', _notifPermissionGranted == null
            ? '查询中…'
            : _notifPermissionGranted! ? '已授予' : '未授予'),
        if (_notifPermissionGranted == false)
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton.icon(
              onPressed: _requestNotifPermission,
              icon: const Icon(Icons.notifications_active, size: 18),
              label: const Text('去授权'),
            ),
          ),
        if (spec.lastReceipt != null) _row('最近回执', spec.lastReceipt!),
        if (spec.lastError != null) _errorLine('最近错误: ${spec.lastError}'),
      ],
    );
  }

  /// VoIP 模块卡（B4 前半）：开关 + 权限 + PCM 桥端点配置（来电入口为
  /// WSS call.invite / action.request phone.call.ring，无本地触发 UI）。
  Widget _buildVoipCard() {
    final spec = _registry['voip'];
    return _moduleCard(
      spec: spec,
      icon: Icons.call,
      subtitle: 'phone.call.ring + call.invite 信令（自管 Telecom 来电，PCM 桥媒体）',
      switchValue: _voipEnabled,
      onToggle: _toggleVoip,
      children: [
        _row('通话权限', _voipPermissionGranted == null
            ? '查询中…'
            : _voipPermissionGranted! ? '已授予' : '未授予'),
        if (_voipPermissionGranted == false)
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton.icon(
              onPressed: _requestVoipPermission,
              icon: const Icon(Icons.mic, size: 18),
              label: const Text('去授权'),
            ),
          ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              flex: 2,
              child: TextField(
                controller: _bridgeHostController,
                decoration: const InputDecoration(
                  labelText: 'PCM 桥地址',
                  hintText: '127.0.0.1',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _bridgePortController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '端口',
                  hintText: '18799',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
              ),
            ),
            const SizedBox(width: 8),
            OutlinedButton(
              onPressed: _saveVoipBridgeConfig,
              child: const Text('保存'),
            ),
          ],
        ),
        if (spec.lastReceipt != null) _row('最近回执', spec.lastReceipt!),
        if (spec.lastError != null) _errorLine('最近错误: ${spec.lastError}'),
      ],
    );
  }

  /// 通知监听模块卡（B3 前半）：开关（默认关）+ 特殊访问权限状态 +
  /// 「去授权」跳系统设置 + 白名单多选页入口。
  Widget _buildNotificationReadCard() {
    final spec = _registry['notification_read'];
    return _moduleCard(
      spec: spec,
      icon: Icons.notification_important,
      subtitle: 'phone.notification.read（仅白名单 App 的标题摘要，不含正文）',
      switchValue: _notifReadEnabled,
      onToggle: _toggleNotificationRead,
      children: [
        _row('监听权限', _notifReadPermissionGranted == null
            ? '查询中…'
            : _notifReadPermissionGranted! ? '已授予' : '未授予'),
        if (_notifReadPermissionGranted == false)
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton.icon(
              onPressed: _openNotificationListenerSettings,
              icon: const Icon(Icons.settings, size: 18),
              label: const Text('去授权'),
            ),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: _openNotificationReadWhitelist,
            icon: const Icon(Icons.checklist, size: 18),
            label: const Text('监听白名单'),
          ),
        ),
        if (spec.lastError != null) _errorLine('最近错误: ${spec.lastError}'),
      ],
    );
  }

  /// 受控设备动作模块卡（B3 前半）：开关（默认关）+ 最近回执。
  Widget _buildSafeActionCard() {
    final spec = _registry['safe_action'];
    return _moduleCard(
      spec: spec,
      icon: Icons.rocket_launch,
      subtitle: 'phone.device.safe_action（启动 App / 打开 http(s) 深链，6 次/分钟）',
      switchValue: _safeActionEnabled,
      onToggle: _toggleSafeAction,
      children: [
        if (spec.lastReceipt != null) _row('最近回执', spec.lastReceipt!),
        if (spec.lastError != null) _errorLine('最近错误: ${spec.lastError}'),
      ],
    );
  }

  Widget _buildScreenMemoCard() {
    final spec = _registry['screenmemo'];
    return _moduleCard(
      spec: spec,
      icon: Icons.burst_mode,
      subtitle: 'screen.context/history/evidence 三能力（经 ScreenMemo MCP）',
      switchValue: _screenMemoEnabled,
      onToggle: _toggleScreenMemo,
      children: [
        _row('MCP 状态', _mcpStatus),
        if (spec.lastError != null) _errorLine('最近错误: ${spec.lastError}'),
        const SizedBox(height: 8),
        TextField(
          controller: _mcpTokenController,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: 'MCP Bearer token',
            hintText: '从 ScreenMemo 设置页复制',
            border: OutlineInputBorder(),
            isDense: true,
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: _saveMcpToken,
                child: const Text('保存 token'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: OutlinedButton(
                onPressed: _pingScreenMemo,
                child: const Text('测试连接'),
              ),
            ),
          ],
        ),
        const Divider(height: 24),
        Row(
          children: [
            const Expanded(child: Text('事件导出（上传到云端）')),
            Switch(
              value: _eventExportEnabled,
              onChanged: _allPaused ? null : _toggleEventExport,
            ),
          ],
        ),
        _row('导出状态', _eventExportStatus),
      ],
    );
  }

  /// 模块卡片：名称 + 状态徽章 + 开关 + 元信息（隐私级/常驻/权限）+ 模块特定内容。
  Widget _moduleCard({
    required ModuleSpec spec,
    required IconData icon,
    required String subtitle,
    required bool switchValue,
    required ValueChanged<bool>? onToggle,
    List<Widget> children = const [],
  }) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 8, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    spec.displayName,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                _statusBadge(spec.status),
                Switch(
                  value: switchValue,
                  onChanged: _allPaused ? null : onToggle,
                ),
              ],
            ),
            Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                _metaChip('隐私: ${spec.privacyLevel.label}'),
                _metaChip(spec.resident ? '常驻' : '非常驻'),
                _metaChip(spec.androidPermissions.isEmpty
                    ? '无系统权限'
                    : '权限: ${spec.androidPermissions.join("/")}'),
                if (spec.rootOptional) _metaChip('可选 Root'),
              ],
            ),
            const SizedBox(height: 4),
            ...children,
          ],
        ),
      ),
    );
  }

  Widget _statusBadge(ModuleStatus status) {
    final color = _statusColor(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Text(
        status.label,
        style: TextStyle(fontSize: 12, color: color),
      ),
    );
  }

  Widget _metaChip(String text) => Text(
        text,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.outline,
            ),
      );

  Widget _errorLine(String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Text(
          text,
          style: Theme.of(context)
              .textTheme
              .bodySmall
              ?.copyWith(color: Theme.of(context).colorScheme.error),
        ),
      );

  /// 可折叠连接测试区（阶段一调试面板）。
  Widget _connectTestSection(bool online) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        title: const Text('连接测试'),
        subtitle: Text('device_id: $_deviceId'),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          _row('device_id', _deviceId),
          _row('session_id', _sessionId ?? '-'),
          _row(
            '队列',
            'pending=${_queue['pending'] ?? 0} sent=${_queue['sent'] ?? 0} '
                'acked=${_queue['acked'] ?? 0} dropped=${_queue['dropped'] ?? 0}',
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _urlController,
            decoration: const InputDecoration(
              labelText: '网关地址',
              hintText: 'ws://<core-host>:8765',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: FilledButton(
                  onPressed: online || _allPaused ? null : _connect,
                  child: const Text('连接'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton(
                  onPressed: _state == TransportState.disconnected
                      ? null
                      : _disconnect,
                  child: const Text('断开'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: _sendTestEvent,
            child: const Text('发送测试 sensor.event'),
          ),
          if (_publicKeyHex.isNotEmpty) ...[
            const SizedBox(height: 12),
            SelectableText(
              'pubkey: $_publicKeyHex',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ],
      ),
    );
  }

  /// 可折叠日志区。
  Widget _logSection() {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        title: const Text('日志'),
        subtitle: Text('${_log.length} 条'),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          if (_log.isEmpty)
            Text('暂无日志', style: Theme.of(context).textTheme.bodySmall)
          else
            ..._log.map(
              (l) => Align(
                alignment: Alignment.centerLeft,
                child: Text(l, style: Theme.of(context).textTheme.bodySmall),
              ),
            ),
        ],
      ),
    );
  }

  static Color _statusColor(ModuleStatus status) {
    switch (status) {
      case ModuleStatus.installed:
        return Colors.blueGrey;
      case ModuleStatus.permissionRequired:
        return Colors.deepOrange;
      case ModuleStatus.disabled:
        return Colors.grey;
      case ModuleStatus.enabled:
        return Colors.teal;
      case ModuleStatus.starting:
        return Colors.amber;
      case ModuleStatus.running:
        return Colors.green;
      case ModuleStatus.degraded:
        return Colors.orange;
      case ModuleStatus.error:
        return Colors.red;
      case ModuleStatus.stopped:
        return Colors.blueGrey;
    }
  }

  static Color _transportColor(TransportState state) {
    switch (state) {
      case TransportState.disconnected:
        return Colors.blueGrey;
      case TransportState.connecting:
      case TransportState.awaitingAuth:
        return Colors.amber;
      case TransportState.online:
        return Colors.green;
      case TransportState.degraded:
        return Colors.orange;
    }
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 96,
            child: Text(label, style: const TextStyle(color: Colors.grey)),
          ),
          Expanded(child: SelectableText(value)),
        ],
      ),
    );
  }
}
