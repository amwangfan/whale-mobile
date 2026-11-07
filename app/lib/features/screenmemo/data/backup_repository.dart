import 'package:flutter/services.dart';

/// 备份恢复仓库（Phase 3）。
///
/// 经 `whale/transport` 通道调原生 `modules/capture/backup/BackupRepository.kt`
/// （ZIP + manifest + JSONL；导入按 backup_hash 幂等去重）。
///
/// **通道约定（新约定）**：
/// - `backup.exportZip` {dest_dir, since_ms?} → ExportReport
/// - `backup.importZip` {path} → ImportReport
class BackupRepository {
  BackupRepository({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;

  /// 导出 ZIP 备份。返回导出报告（含文件路径、记录数、哈希）。
  Future<ExportReport> exportZip(String destDir, {int? sinceMs}) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'backup.exportZip',
      <String, dynamic>{
        'dest_dir': destDir,
        'since_ms': ?sinceMs,
      },
    );
    return ExportReport.fromMap(
        (res ?? const <dynamic, dynamic>{}).cast<String, dynamic>());
  }

  /// 导入 ZIP 备份（幂等：同 backup_hash 重复导入不产生重复数据）。
  Future<ImportReport> importZip(String path) async {
    final res = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'backup.importZip',
      <String, dynamic>{'path': path},
    );
    return ImportReport.fromMap(
        (res ?? const <dynamic, dynamic>{}).cast<String, dynamic>());
  }
}

/// 导出报告（BackupRepository.ExportReport）。
class ExportReport {
  const ExportReport({
    this.zipPath = '',
    this.captures = 0,
    this.segments = 0,
    this.favorites = 0,
    this.notes = 0,
    this.imageFiles = 0,
    this.backupHash = '',
  });

  final String zipPath;
  final int captures;
  final int segments;
  final int favorites;
  final int notes;
  final int imageFiles;
  final String backupHash;

  factory ExportReport.fromMap(Map<String, dynamic> m) => ExportReport(
        zipPath: m['zip_path'] as String? ?? '',
        captures: (m['captures'] as num?)?.toInt() ?? 0,
        segments: (m['segments'] as num?)?.toInt() ?? 0,
        favorites: (m['favorites'] as num?)?.toInt() ?? 0,
        notes: (m['notes'] as num?)?.toInt() ?? 0,
        imageFiles: (m['image_files'] as num?)?.toInt() ?? 0,
        backupHash: m['backup_hash'] as String? ?? '',
      );
}

/// 导入报告（BackupRepository.ImportReport；含重复/失败计数与校验）。
class ImportReport {
  const ImportReport({
    this.backupHash = '',
    this.capturesImported = 0,
    this.capturesDuplicate = 0,
    this.segmentsImported = 0,
    this.favoritesImported = 0,
    this.notesImported = 0,
    this.imagesRestored = 0,
    this.alreadyImported = false,
    this.errors = const [],
  });

  final String backupHash;
  final int capturesImported;
  final int capturesDuplicate;
  final int segmentsImported;
  final int favoritesImported;
  final int notesImported;
  final int imagesRestored;

  /// 命中导入幂等台账（capture_imports）：本次为重复导入。
  final bool alreadyImported;
  final List<String> errors;

  factory ImportReport.fromMap(Map<String, dynamic> m) => ImportReport(
        backupHash: m['backup_hash'] as String? ?? '',
        capturesImported: (m['captures_imported'] as num?)?.toInt() ?? 0,
        capturesDuplicate: (m['captures_duplicate'] as num?)?.toInt() ?? 0,
        segmentsImported: (m['segments_imported'] as num?)?.toInt() ?? 0,
        favoritesImported: (m['favorites_imported'] as num?)?.toInt() ?? 0,
        notesImported: (m['notes_imported'] as num?)?.toInt() ?? 0,
        imagesRestored: (m['images_restored'] as num?)?.toInt() ?? 0,
        alreadyImported: m['already_imported'] as bool? ?? false,
        errors: (m['errors'] as List?)?.cast<String>() ?? const [],
      );
}
