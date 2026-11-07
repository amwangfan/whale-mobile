import 'package:flutter/services.dart';

/// 屏幕采集数据仓库（Phase 3 Flutter 侧）。
///
/// 经 MethodChannel `whale/transport` 调原生 CaptureStore（Kotlin
/// `modules/capture/CaptureStore.kt`）。
///
/// **通道约定（新约定，Flutter↔原生对齐点）**——原生侧需在
/// TransportBridge 注册以下方法（本阶段 Flutter 先行，见开发报告遗留项）：
/// - `capture.listCaptures` {since_ms?, until_ms?, limit?} → List<Map>
/// - `capture.listSegments` {limit?, since_ms?} → List<Map>
/// - `capture.getEvidencePath` {ref: "capture:segment_…" 或行 id} → {path}
/// - `capture.storageStats` → {captures, segments, favorites, notes,
///   image_files, image_bytes}
/// - `capture.purgeBefore` {cutoff_ms, keep_favorites} → PurgeReport
/// - `capture.compressImagesBefore` {cutoff_ms, max_side?, quality?} →
///   CompressReport
///
/// 隐私：本库为 owner_sensitive，所有数据只进本地页面，不上行。
class CaptureRepository {
  CaptureRepository({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;

  /// 时间线条目（动态段）。
  Future<List<ScreenSegment>> listSegments({int limit = 50, int? sinceMs}) async {
    final res = await _channel.invokeMethod<List<dynamic>>(
      'capture.listSegments',
      <String, dynamic>{'limit': limit, 'since_ms': ?sinceMs,},
    );
    return (res ?? const [])
        .whereType<Map>()
        .map((m) => ScreenSegment.fromMap(m.cast<String, dynamic>()))
        .toList();
  }

  /// 时间范围内的截图记录（段详情/回放的成员）。
  Future<List<CaptureRecord>> listCaptures({
    int? sinceMs,
    int? untilMs,
    int limit = 200,
  }) async {
    final res = await _channel.invokeMethod<List<dynamic>>(
      'capture.listCaptures',
      <String, dynamic>{
        'limit': limit,
        'since_ms': ?sinceMs,
        'until_ms': ?untilMs,
      },
    );
    return (res ?? const [])
        .whereType<Map>()
        .map((m) => CaptureRecord.fromMap(m.cast<String, dynamic>()))
        .toList();
  }

  /// 证据文件本地路径（根目录逃逸校验在原生侧做；不透明的本地路径不出设备）。
  Future<String?> evidencePath(String evidenceRef) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'capture.getEvidencePath',
      <String, dynamic>{'ref': evidenceRef},
    );
    return res?['path'] as String?;
  }

  /// 存储占用统计（storageStats）。
  Future<StorageStats> storageStats() async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'capture.storageStats');
    return StorageStats.fromMap(
        (res ?? const <dynamic, dynamic>{}).cast<String, dynamic>());
  }

  /// 清理 cutoff 之前的记录（收藏受保护）。
  Future<PurgeReport> purgeBefore(int cutoffMs,
      {bool keepFavorites = true}) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'capture.purgeBefore',
      <String, dynamic>{
        'cutoff_ms': cutoffMs,
        'keep_favorites': keepFavorites,
      },
    );
    return PurgeReport.fromMap(
        (res ?? const <dynamic, dynamic>{}).cast<String, dynamic>());
  }

  /// 压缩 cutoff 之前的图片（缩放 + 重编码）。
  Future<CompressReport> compressImagesBefore(int cutoffMs,
      {int maxSide = 1280, int quality = 80}) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'capture.compressImagesBefore',
      <String, dynamic>{
        'cutoff_ms': cutoffMs,
        'max_side': maxSide,
        'quality': quality,
      },
    );
    return CompressReport.fromMap(
        (res ?? const <dynamic, dynamic>{}).cast<String, dynamic>());
  }
}

/// 截图记录（CaptureStore.Row 的 Flutter 视图）。
class CaptureRecord {
  const CaptureRecord({
    required this.id,
    required this.captureTime,
    required this.packageName,
    this.appLabel,
    required this.width,
    required this.height,
    required this.ocrStatus,
    this.ocrText,
  });

  final int id;
  final int captureTime;
  final String packageName;
  final String? appLabel;
  final int width;
  final int height;
  final String ocrStatus;
  final String? ocrText;

  factory CaptureRecord.fromMap(Map<String, dynamic> m) => CaptureRecord(
        id: (m['id'] as num?)?.toInt() ?? 0,
        captureTime: (m['capture_time'] as num?)?.toInt() ?? 0,
        packageName: m['package_name'] as String? ?? '',
        appLabel: m['app_label'] as String?,
        width: (m['width'] as num?)?.toInt() ?? 0,
        height: (m['height'] as num?)?.toInt() ?? 0,
        ocrStatus: m['ocr_status'] as String? ?? '',
        ocrText: m['ocr_text'] as String?,
      );

  /// 证据引用（`capture:row_<id>`，与原生 evidenceRefToRowId 对齐）。
  String get evidenceRef => 'capture:row_$id';
}

/// 动态段（连续同应用截图聚合；CaptureStore.Segment）。
class ScreenSegment {
  const ScreenSegment({
    required this.segmentId,
    required this.packageName,
    required this.startedAt,
    required this.endedAt,
    this.summary,
    this.evidence = const [],
    this.memberCount = 0,
    this.closed = true,
  });

  final String segmentId;
  final String packageName;
  final int startedAt;
  final int endedAt;
  final String? summary;
  final List<String> evidence;
  final int memberCount;
  final bool closed;

  factory ScreenSegment.fromMap(Map<String, dynamic> m) => ScreenSegment(
        segmentId: m['segment_id'] as String? ?? '',
        packageName: m['package_name'] as String? ?? '',
        startedAt: (m['started_at'] as num?)?.toInt() ?? 0,
        endedAt: (m['ended_at'] as num?)?.toInt() ?? 0,
        summary: m['summary'] as String?,
        evidence: (m['evidence'] as List?)?.cast<String>() ?? const [],
        memberCount: (m['member_count'] as num?)?.toInt() ?? 0,
        closed: m['closed'] as bool? ?? true,
      );

  /// 段级证据引用（`capture:segment_<ULID>`，contracts/v2 §10）。
  String get evidenceRef => 'capture:$segmentId';
}

/// 存储占用统计（CaptureStore.StorageStats）。
class StorageStats {
  const StorageStats({
    this.captures = 0,
    this.segments = 0,
    this.favorites = 0,
    this.notes = 0,
    this.imageFiles = 0,
    this.imageBytes = 0,
  });

  final int captures;
  final int segments;
  final int favorites;
  final int notes;
  final int imageFiles;
  final int imageBytes;

  factory StorageStats.fromMap(Map<String, dynamic> m) => StorageStats(
        captures: (m['captures'] as num?)?.toInt() ?? 0,
        segments: (m['segments'] as num?)?.toInt() ?? 0,
        favorites: (m['favorites'] as num?)?.toInt() ?? 0,
        notes: (m['notes'] as num?)?.toInt() ?? 0,
        imageFiles: (m['image_files'] as num?)?.toInt() ?? 0,
        imageBytes: (m['image_bytes'] as num?)?.toInt() ?? 0,
      );

  String get imageBytesLabel {
    if (imageBytes < 1024 * 1024) {
      return '${(imageBytes / 1024).toStringAsFixed(1)} KB';
    }
    if (imageBytes < 1024 * 1024 * 1024) {
      return '${(imageBytes / 1024 / 1024).toStringAsFixed(1)} MB';
    }
    return '${(imageBytes / 1024 / 1024 / 1024).toStringAsFixed(2)} GB';
  }
}

/// 清理报告（CaptureStore.PurgeReport）。
class PurgeReport {
  const PurgeReport({
    this.rowsDeleted = 0,
    this.filesDeleted = 0,
    this.bytesFreed = 0,
    this.favoritesKept = 0,
  });

  final int rowsDeleted;
  final int filesDeleted;
  final int bytesFreed;
  final int favoritesKept;

  factory PurgeReport.fromMap(Map<String, dynamic> m) => PurgeReport(
        rowsDeleted: (m['rows_deleted'] as num?)?.toInt() ?? 0,
        filesDeleted: (m['files_deleted'] as num?)?.toInt() ?? 0,
        bytesFreed: (m['bytes_freed'] as num?)?.toInt() ?? 0,
        favoritesKept: (m['favorites_kept'] as num?)?.toInt() ?? 0,
      );
}

/// 压缩报告（CaptureStore.CompressReport）。
class CompressReport {
  const CompressReport({
    this.filesCompressed = 0,
    this.bytesBefore = 0,
    this.bytesAfter = 0,
  });

  final int filesCompressed;
  final int bytesBefore;
  final int bytesAfter;

  int get bytesSaved => bytesBefore - bytesAfter;

  factory CompressReport.fromMap(Map<String, dynamic> m) => CompressReport(
        filesCompressed: (m['files_compressed'] as num?)?.toInt() ?? 0,
        bytesBefore: (m['bytes_before'] as num?)?.toInt() ?? 0,
        bytesAfter: (m['bytes_after'] as num?)?.toInt() ?? 0,
      );
}
