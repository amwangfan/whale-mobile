import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../transport/link/link_client.dart';
import '../controllers/approval_controller.dart';
import 'device_config.dart';
import 'link_chat_api.dart';
import 'link_ticket_service.dart';

/// 一次连接尝试的解析结果（测试可捕获断言）。
class LinkSessionConfig {
  const LinkSessionConfig({required this.uri, this.ticket});

  /// Link WS 全地址（base_url 派生 ws(s)://{host}/api/link）。
  final Uri uri;

  /// C 路 Link ticket（900s，绑 device_id）；A 路 api_token 已弃用不上机。
  final String? ticket;
}

/// Link 会话生命周期管理（M2/O1：聊天面**前台按需连接**——聊天页活跃时建立、
/// 离开断开；通知永不丢走设备 WS，既有不动）。
///
/// 鉴权 **C 路 link_ticket**（主人定，Tob 令；免主 token 上机）：
/// 建连前先经 [LinkTicketService] 以设备 Ed25519 身份换 900s 票
/// （POST /api/devices/link_ticket，§11 签名四头），hello 带 ticket、
/// 固定 role=mobile。票可复用重连；过期/401 重签；4401/4403 终态
/// → 作废重签再连（有界，连续 3 次失败给明确提示）。
class LinkSession extends ChangeNotifier {
  LinkSession({
    Future<LinkClient> Function(LinkSessionConfig config)? clientFactory,
    Future<String> Function()? ticketProvider,
    LinkTicketService? ticketService,
  })  : _clientFactory = clientFactory,
        _ticketProvider = ticketProvider,
        _ticketService = ticketService ?? LinkTicketService();

  static LinkSession? _instance;

  /// 全局实例（聊天面共享一条 Link 连接）。
  static LinkSession get instance => _instance ??= LinkSession();

  /// 测试注入点。
  @visibleForTesting
  static set instance(LinkSession? value) => _instance = value;

  final Future<LinkClient> Function(LinkSessionConfig config)? _clientFactory;
  final Future<String> Function()? _ticketProvider;
  final LinkTicketService _ticketService;

  LinkClient? _client;
  ChatBackend? _api;
  ApprovalController? _approvals;
  StreamSubscription<LinkError>? _errorSub;
  int _holders = 0;
  bool _starting = false;

  /// 连续鉴权失败计数（welcome 清零）；超界给终态提示，不无限重签。
  int _authFailures = 0;

  /// 同一失败链去重（4401 会先出 error 帧再出关闭帧，只处理一次）。
  bool _handlingAuthFailure = false;

  /// 连续鉴权失败上限（超过即终态提示，等人工处置）。
  static const int maxAuthFailures = 3;

  /// 显式错误（鉴权失败/票据获取失败/被服务端断开等）。
  String? error;

  /// 聊天面后端（ChatBackend 抽象：widget 测试注入内存假实现）。
  ChatBackend? get api => _api;
  ApprovalController? get approvals => _approvals;
  LinkClient? get client => _client;
  LinkState get state => _client?.state ?? LinkState.disconnected;
  bool get isConnected => _client?.isConnected ?? false;
  int get holders => _holders;

  /// 票据服务（artifacts 头等复用当前有效票，免主 token 上机）。
  LinkTicketService get ticketService => _ticketService;

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
    // 新一轮连接尝试：重置失败链去重（同一轮的 error 帧+关闭帧只计一次，
    // 下一轮失败的 error 帧须照常处理）。
    _handlingAuthFailure = false;
    try {
      final config = await _resolveConfig();
      final client = await (_clientFactory?.call(config) ??
          Future.value(_defaultClient(config)));
      _client = client;
      _api = LinkChatApi(client);
      _approvals = ApprovalController(api: _api!);
      _errorSub = client.protocolErrors.listen(_onProtocolError);
      client.welcomes.listen((w) {
        _authFailures = 0; // 握手成功：清零连续失败计数
        _handlingAuthFailure = false;
        // 断线补齐：审批待决重拉（ApprovalController 内部也处理事件补齐）
        _approvals?.refreshPending();
        notifyListeners();
      });
      client.states.listen((_) => notifyListeners());
      client.connect();
      notifyListeners();
    } on LinkTicketException catch (e) {
      // 票据获取失败：明确提示（身份不可用/未配对/网络/401 撤销）
      error = 'Link 票据获取失败：$e';
      notifyListeners();
    } catch (e) {
      error = '$e';
      notifyListeners();
    } finally {
      _starting = false;
    }
  }

  /// 解析连接配置：base_url → WS 地址 + C 路票据（失败显式上抛）。
  Future<LinkSessionConfig> _resolveConfig() async {
    final base = await DeviceConfig.baseUrl();
    final ticket = await (_ticketProvider?.call() ?? _defaultTicket());
    return LinkSessionConfig(uri: LinkClient.linkUriFor(base), ticket: ticket);
  }

  Future<String> _defaultTicket() async =>
      (await _ticketService.getValid()).value;

  LinkClient _defaultClient(LinkSessionConfig config) {
    return LinkClient(
      uri: config.uri,
      ticket: config.ticket,
      role: 'mobile',
      name: 'whale-mobile',
    );
  }

  /// 协议级终态（4401 鉴权失败 / 4403 服务端 kick）：
  /// 作废票据→重签→重连（有界）；连续超限给终态提示。
  void _onProtocolError(LinkError e) {
    if (e.code == LinkErrorCode.unauthorized ||
        e.code == LinkErrorCode.forbidden) {
      if (_handlingAuthFailure) return; // 同一失败链只处理一次
      _handlingAuthFailure = true;
      _ticketService.invalidate();
      _authFailures++;
      if (_authFailures >= maxAuthFailures) {
        error = 'Link 鉴权连续失败（票据重签 $maxAuthFailures 次仍被拒）：'
            '请检查设备配对状态（设备可能已被撤销）';
        notifyListeners();
        return;
      }
      // 重签重连（仍持有页面的情况下）
      if (_holders > 0) {
        _stop();
        _start();
        return;
      }
    }
    error = e.toString();
    notifyListeners();
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
