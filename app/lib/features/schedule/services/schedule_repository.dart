import '../models/schedule_entry.dart';

/// 日程溯源数据口（云端 /v1/schedule/* 契约形状；2026-11-19 最终版）。
///
/// 端点对应：
/// - fetchCandidates  ↔ GET /v1/schedule/candidates
/// - fetchItems       ↔ GET /v1/schedule/list
/// - addCandidate     ↔ POST /v1/schedule/candidates/{id}/add（候选转正式；幂等）
/// - rejectCandidate  ↔ POST /v1/schedule/candidates/{id}/reject（拒绝且不再提；幂等）
/// - createItem       ↔ POST /v1/schedule/items {title,date,time?}
/// - deleteItem       ↔ DELETE /v1/schedule/items/{id}
/// - fetchSource      ↔ GET /v1/schedule/source?conversation&messageId（±10 条，owner-only）
abstract class ScheduleRepository {
  Future<List<ScheduleEntry>> fetchCandidates();
  Future<List<ScheduleEntry>> fetchItems();
  Future<void> addCandidate(String id);
  Future<void> rejectCandidate(String id);
  Future<ScheduleEntry> createItem({
    required String title,
    required String date,
    String? time,
  });
  Future<void> deleteItem(String id);
  Future<List<SourceMessage>> fetchSource({
    required String conversation,
    required String messageId,
  });
}
