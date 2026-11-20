import 'package:flutter/foundation.dart';

import '../models/schedule_entry.dart';
import '../services/schedule_repository.dart';

/// 日程页状态机（2026-11-19 过目通过版）。
///
/// - 列表：loading → ready | error（可重试）。
/// - 裁决/写操作：候选 pending → adding/rejecting（在飞锁，幂等防重复提交）
///   → added/rejected（出列）| failed（卡片保留 + 错误条，手动重试，不自动轰炸）。
/// - 合并去重：同事件不双卡——来源 messageId 优先，退化 归一化标题+日期
///   （ScheduleEntry.dedupeKey）。
class ScheduleController extends ChangeNotifier {
  ScheduleController(this._repo);

  final ScheduleRepository _repo;

  SchedulePhase phase = SchedulePhase.loading;
  String? error;

  /// 候选（已按 dedupeKey 去重：与正式日程同事件者不入列）。
  List<ScheduleEntry> candidates = const [];

  /// 正式日程。
  List<ScheduleEntry> items = const [];

  /// 在飞写操作 id 集（在飞即锁按钮；重复提交天然安全——mock/云端均幂等）。
  final Set<String> inFlight = <String>{};

  /// 写失败信息（卡片保留；手动重试）。
  final Map<String, String> failures = <String, String>{};

  bool busy(String id) => inFlight.contains(id);
  String? failureOf(String id) => failures[id];

  ScheduleEntry? candidateById(String id) {
    for (final e in candidates) {
      if (e.id == id) return e;
    }
    return null;
  }

  /// 来源上下文（GET /v1/schedule/source，±10 条，owner-only）。
  Future<List<SourceMessage>> sourceOf(String conversation, String messageId) =>
      _repo.fetchSource(conversation: conversation, messageId: messageId);

  Future<void> load() => _load(silent: false);

  /// 静默重拉（写操作成功后保真用，不闪 loading）。
  Future<void> refresh() => _load(silent: true);

  Future<void> _load({required bool silent}) async {
    if (!silent) {
      phase = SchedulePhase.loading;
      error = null;
      notifyListeners();
    }
    try {
      final freshItems = await _repo.fetchItems();
      final freshCandidates = await _repo.fetchCandidates();
      final seen = <String>{for (final e in freshItems) e.dedupeKey};
      final deduped = <ScheduleEntry>[];
      for (final c in freshCandidates) {
        if (seen.add(c.dedupeKey)) deduped.add(c); // 同事件不双卡
      }
      items = freshItems;
      candidates = deduped;
      phase = SchedulePhase.ready;
      error = null;
    } catch (e) {
      if (!silent) phase = SchedulePhase.error;
      error = '$e';
    }
    notifyListeners();
  }

  /// 候选 → 正式（POST …/add，幂等）。成功=重拉保真；失败=卡片保留。
  Future<bool> add(String id) => _write(id, () => _repo.addCandidate(id));

  /// 候选拒绝（POST …/reject，幂等）。
  Future<bool> reject(String id) => _write(id, () => _repo.rejectCandidate(id));

  /// 手动删正式日程（DELETE /items/{id}）。
  Future<bool> deleteItem(String id) => _write(id, () => _repo.deleteItem(id));

  /// 手动建正式日程（POST /items）。
  Future<bool> createItem({
    required String title,
    required String date,
    String? time,
  }) =>
      _write('new', () async {
        await _repo.createItem(title: title, date: date, time: time);
      });

  Future<bool> _write(String key, Future<void> Function() op) async {
    if (!inFlight.add(key)) return false; // 在飞锁：重复提交直接吞
    failures.remove(key);
    notifyListeners();
    try {
      await op();
      await refresh(); // 重拉保真，不本地乐观拼字段
      return true;
    } catch (e) {
      failures[key] = '$e';
      notifyListeners();
      return false;
    } finally {
      inFlight.remove(key);
      notifyListeners();
    }
  }
}

enum SchedulePhase { loading, ready, error }
