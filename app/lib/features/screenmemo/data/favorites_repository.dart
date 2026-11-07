import 'package:flutter/services.dart';

/// 收藏仓库（Phase 3）。
///
/// 经 `whale/transport` 通道调原生 CaptureStore 收藏表。
///
/// **通道约定（新约定）**：
/// - `favorites.list` → List<Map>
/// - `favorites.add` {capture_id?|segment_id?, note?} → {ok: bool}
///   （原生侧部分唯一索引保证同一目标只收藏一次，重复添加幂等）
/// - `favorites.remove` {capture_id?|segment_id?} → {removed: int}
class FavoritesRepository {
  FavoritesRepository({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;

  Future<List<FavoriteEntry>> list() async {
    final res = await _channel.invokeMethod<List<dynamic>>('favorites.list');
    return (res ?? const [])
        .whereType<Map>()
        .map((m) => FavoriteEntry.fromMap(m.cast<String, dynamic>()))
        .toList();
  }

  /// 收藏截图或段（二选一必填；重复添加幂等 no-op）。
  Future<bool> add({int? captureId, String? segmentId, String? note}) async {
    assert(captureId != null || segmentId != null, 'captureId 或 segmentId 必填');
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'favorites.add',
      <String, dynamic>{
        'capture_id': ?captureId,
        'segment_id': ?segmentId,
        'note': ?note,
      },
    );
    return res?['ok'] as bool? ?? false;
  }

  Future<int> remove({int? captureId, String? segmentId}) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'favorites.remove',
      <String, dynamic>{
        'capture_id': ?captureId,
        'segment_id': ?segmentId,
      },
    );
    return (res?['removed'] as num?)?.toInt() ?? 0;
  }
}

/// 收藏条目（截图或段，二选一）。
class FavoriteEntry {
  const FavoriteEntry({
    required this.id,
    this.captureId,
    this.segmentId,
    this.note,
    required this.createdAt,
  });

  final int id;
  final int? captureId;
  final String? segmentId;
  final String? note;
  final int createdAt;

  bool get isSegment => segmentId != null;

  factory FavoriteEntry.fromMap(Map<String, dynamic> m) => FavoriteEntry(
        id: (m['id'] as num?)?.toInt() ?? 0,
        captureId: (m['capture_id'] as num?)?.toInt(),
        segmentId: m['segment_id'] as String?,
        note: m['note'] as String?,
        createdAt: (m['created_at'] as num?)?.toInt() ?? 0,
      );
}
