import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../transport/link/link_client.dart';
import '../controllers/approval_controller.dart';
import 'device_config.dart';
import 'link_chat_api.dart';

/// Link 会话生命周期管理（M2/O1：聊天面**前台按需连接**——聊天页活跃时建立、
/// 离开断开；通知永不丢走设备 WS，既有不动）。
///
/// - 引用计数：`acquire()`/`release()` 成对（会话列表页/聊天页各持一份）；
///   计数 0→1 建连，1→0 断开。
/// - 配置来源 [DeviceConfig]（base_url + 访问令牌）；base_url 派生
///   `ws(s)://{host}/api/link`，无硬编码。
/// - 鉴权 A（api_token）先通；C 的 ticket 头留接口（[DeviceConfig.linkTicket]）。
/// - 4401/4403 为终态：不自动重连，错误显式上屏（改配置后重进页面即重连）。
class LinkSession extends ChangeNotifier {
  LinkSession({
    Future<LinkClient> Function()? clientFactory,
  }) : _clientFactory = clientFactory;

  // ignore: prefer_initializing_formals —— 可空转存，意图更清晰

  static LinkSession? _instance;

  /// 全局实例（聊天面共享一条 Link 连接）。
  static LinkSession get instance => _instance ??= LinkSession();

  /// 测试注入点。
  @visibleForTesting
  static set instance(LinkSession? value) => _instance = value;

  final Future<LinkClient> Function()? _clientFactory;

  LinkClient? _client;
  LinkChatApi? _api;
  ApprovalController? _approvals;
  StreamSubscription<LinkError>? _errorSub;
  int _holders = 0;
  bool _starting = false;

  /// 显式错误（鉴权失败/被服务端断开等终态）。
  String? error;

  LinkChatApi? get api => _api;
  ApprovalController? get approvals => _approvals;
  LinkClient? get client => _client;
  LinkState get state => _client?.state ?? LinkState.disconnected;
  bool get isConnected => _client?.isConnected ?? false;
  int get holders => _holders;

  /// 持有一份连接（页面进入）。0→1 时建连。
  Future<void> acquire() async {
    _holders++;
    if (_holders != 1) {
      notifyListeners();
      return;
    }
    await _start();
  }

  /// 释放（页面离开）。1→0 时断开。
  void release() {
    if (_holders > 0) _holders--;
    if (_holders == 0) _stop();
    notifyListeners();
  }

  /// 配置变更后重建连接（设置页保存后调用）。
  Future<void> reconnect() async {
    if (_holders == 0) return;
    _stop();
    await _start();
  }

  Future<void> _start() async {
    if (_starting) return;
    _starting = true;
    error = null;
    try {
      final client = await (_clientFactory?.call() ?? _buildClient());
      _client = client;
      _api = LinkChatApi(client);
      _approvals = ApprovalController(api: _api!);
      _errorSub = client.protocolErrors.listen((e) {
        error = e.toString();
        notifyListeners();
      });
      client.welcomes.listen((w) {
        // 断线补齐：审批待决重拉（ApprovalController 内部也处理事件补齐）
        _approvals?.refreshPending();
        notifyListeners();
      });
      client.states.listen((_) => notifyListeners());
      client.connect();
      notifyListeners();
    } catch (e) {
      error = '$e';
      notifyListeners();
    } finally {
      _starting = false;
    }
  }

  Future<LinkClient> _buildClient() async {
    final base = await DeviceConfig.baseUrl();
    final token = await DeviceConfig.apiToken();
    final ticket = await DeviceConfig.linkTicket();
    return LinkClient(
      uri: LinkClient.linkUriFor(base),
      token: token,
      ticket: ticket,
      role: 'mobile',
      name: 'whale-mobile',
    );
  }

  void _stop() {
    _errorSub?.cancel();
    _errorSub = null;
    _approvals?.dispose();
    _approvals = null;
    _api = null;
    _client?.dispose();
    _client = null;
    error = null;
  }

  @override
  void dispose() {
    _stop();
    super.dispose();
  }
}
