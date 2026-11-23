/// Artifact 模型（v3：`/api/artifacts/{id}`，契约 §3 统一命名空间）。
///
/// Core 形状：`{id, name, content_type, size, created_at, meta, url}`，
/// 上行应答另带 `sha256`。id = `art_` + ULID。
library;

class ArtifactRef {
  const ArtifactRef({
    required this.artifactId,
    required this.name,
    required this.contentType,
    required this.size,
    this.createdAt,
    this.sha256,
  });

  /// `art_` + 26 字符 ULID。
  final String artifactId;
  final String name;
  final String contentType;
  final int size;
  final int? createdAt;
  final String? sha256;

  static final RegExp idPattern = RegExp(r'^art_[0-9A-HJKMNP-TV-Z]{26}$');

  bool get hasValidId => idPattern.hasMatch(artifactId);
  bool get isImage => contentType.startsWith('image/');

  factory ArtifactRef.fromJson(Map<String, dynamic> json) {
    return ArtifactRef(
      artifactId: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      contentType: json['content_type'] as String? ?? 'application/octet-stream',
      size: (json['size'] as num?)?.toInt() ?? 0,
      createdAt: (json['created_at'] as num?)?.toInt(),
      sha256: json['sha256'] as String?,
    );
  }

  /// 人类可读大小。
  String get sizeLabel {
    if (size < 1024) return '$size B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)} KB';
    return '${(size / 1024 / 1024).toStringAsFixed(1)} MB';
  }
}
