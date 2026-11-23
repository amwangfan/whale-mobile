import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../transport/link/link_client.dart';
import '../models/approval.dart';
import '../services/link_chat_api.dart';

/// 审批中心（M1：手机必须可见可裁决——全量审批卡：args 展开、note、历史列表）。
///
/// - `approval.requested` 事件 → 进待决列表（按 id 去重）；
/// - `approval.resolved` 事件 → 待决移除（含服务端 600s 超时拒绝 by=system）；
/// - 裁决 `approvals.resolve {id, approve, note?}`：一次性；重复核销服务端回
///   `conflict`——本地刷新待决并显式提示，不伪装成功；
/// - 断线补齐：重连 welcome 后重拉 `approvals.list(status=pending)`，并用
///   `events.query(pattern=approval.*)` 补断线期间的 requested/resolved。
class ApprovalController extends ChangeNotifier {
  ApprovalController({required ChatBackend api}) : _api = api {
    _eventSub = _api.events.listen(_onEvent);
    _welcomeSub = _api.welcomes.listen(_onWelcome);
  }

  final ChatBackend _api;
  late final StreamSubscription<LinkEvent> _eventSub;
  late final StreamSubscription<LinkWelcome> _welcomeSub;

  /// 待决审批（新→旧）。
  final List<Approval> pending = <Approval>[];

  /// 历史（已裁决；`loadHistory` 加载，含裁决结果与 note）。
  final List<Approval> history = <Approval>[];

  bool loading = false;
  String? error;
  bool _disposed = false;

  /// 最近一次裁决的显式结果（SnackBar 提示用；消费后清）。
  String? lastResolveMessage;

  /// dispose 后不再通知（页面销毁时在飞的 refreshPending/catchUp 允许悬空完成）。
  void _safeNotify() {
    if (!_disposed) notifyListeners();
  }

  void _onWelcome(LinkWelcome welcome) {
    if (welcome.isReconnect) catchUp();
  }

  void _onEvent(LinkEvent ev) {
    switch (ev.topic) {
      case 'approval.requested':
        final approval = Approval.fromJson(ev.data);
        final i = pending.indexWhere((a) => a.id == approval.id);
        if (i >= 0) {
          pending[i] = approval;
        } else {
          pending.insert(0, approval);
        }
        _safeNotify();
        break;
      case 'approval.resolved':
        final approval = Approval.fromJson(ev.data);
        pending.removeWhere((a) => a.id == approval.id);
        _upsertHistory(approval);
        _safeNotify();
        break;
      default:
        break;
    }
  }

  /// 重拉待决（页面进入/断线补齐）。
  Future<void> refreshPending() async {
    loading = true;
    error = null;
    _safeNotify();
    try {
      final list = await _api.listApprovals(status: 'pending');
      pending
        ..clear()
        ..addAll(list);
    } on LinkError catch (e) {
      error = '加载审批失败：$e';
    }
    loading = false;
    _safeNotify();
  }

  /// 加载历史（status=null 全部；排除当前 pending 后按时间倒序）。
  Future<void> loadHistory() async {
    loading = true;
    error = null;
    _safeNotify();
    try {
      final all = await _api.listApprovals(status: null);
      final pendingIds = pending.map((a) => a.id).toSet();
      history
        ..clear()
        ..addAll(all.where((a) => !pendingIds.contains(a.id)));
    } on LinkError catch (e) {
      error = '加载审批历史失败：$e';
    }
    loading = false;
    _safeNotify();
  }

  /// 断线补齐：events.query 补断线期间的 requested/resolved（再叠 refreshPending
  /// 的权威状态）。
  Future<void> catchUp() async {
    try {
      final events = await _api.queryEvents(pattern: 'approval.*', limit: 100);
      for (final ev in events.reversed) {
        _onEvent(ev);
      }
    } on LinkError {
      // 补事件失败不致命：refreshPending 拉权威状态
    }
    await refreshPending();
  }

  /// 裁决。返回 null=成功；否则为显式错误文案（conflict=已被处理）。
  Future<String?> resolve(Approval approval, bool approve,
      {String note = ''}) async {
    try {
      final row = await _api.resolveApproval(approval.id, approve, note: note);
      pending.removeWhere((a) => a.id == approval.id);
      _upsertHistory(row);
      lastResolveMessage = approve ? '已批准' : '已拒绝';
      _safeNotify();
      return null;
    } on LinkError catch (e) {
      if (e.code == LinkErrorCode.conflict) {
        // 一次性核销：重复裁决 → 刷新权威状态 + 显式提示
        lastResolveMessage = '该审批已被处理（可能超时或他端裁决）';
        await refreshPending();
      } else {
        lastResolveMessage = '裁决失败：$e';
      }
      _safeNotify();
      return lastResolveMessage;
    }
  }

  void _upsertHistory(Approval approval) {
    final i = history.indexWhere((a) => a.id == approval.id);
    if (i >= 0) {
      history[i] = approval;
    } else {
      history.insert(0, approval);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _eventSub.cancel();
    _welcomeSub.cancel();
    super.dispose();
  }
}
