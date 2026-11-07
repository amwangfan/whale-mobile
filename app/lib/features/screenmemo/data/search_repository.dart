import 'package:flutter/services.dart';

import 'capture_repository.dart';

/// 屏幕历史全文搜索仓库（Phase 3）。
///
/// 经 `whale/transport` 通道调原生 `CaptureStore.search`（FTS5 + LIKE 回退）。
///
/// **通道约定（新约定）**：`capture.search` {query, limit?, since_ms?} →
/// List<Map>（字段同 CaptureRecord）。
class SearchRepository {
  SearchRepository({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;

  /// 全文搜索 OCR 文本与标题（中文等无空格语言由原生侧 LIKE 回退）。
  Future<List<CaptureRecord>> search(
    String query, {
    int limit = 20,
    int? sinceMs,
  }) async {
    if (query.trim().isEmpty) return const [];
    final res = await _channel.invokeMethod<List<dynamic>>(
      'capture.search',
      <String, dynamic>{
        'query': query.trim(),
        'limit': limit,
        'since_ms': ?sinceMs,
      },
    );
    return (res ?? const [])
        .whereType<Map>()
        .map((m) => CaptureRecord.fromMap(m.cast<String, dynamic>()))
        .toList();
  }
}
