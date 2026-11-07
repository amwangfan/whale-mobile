/// 附件引用模型（contracts/v2 §11 AttachmentRef，objects/attachment-ref）。
///
/// 严格 schema 键集：attachment_id/type/mime/size/sha256/width/height/duration_ms。
/// 消息体只携带 attachment_id，不在 JSON 里反复传 Base64。
library;

/// 附件类型（§11 枚举）。
enum AttachmentType {
  image,
  video,
  audio,
  file;

  static AttachmentType fromWire(String? value) {
    switch (value) {
      case 'image':
        return AttachmentType.image;
      case 'video':
        return AttachmentType.video;
      case 'audio':
        return AttachmentType.audio;
      case 'file':
      default:
        return AttachmentType.file;
    }
  }

  String get wire => name;

  /// 中文展示名。
  String get label {
    switch (this) {
      case AttachmentType.image:
        return '图片';
      case AttachmentType.video:
        return '视频';
      case AttachmentType.audio:
        return '音频';
      case AttachmentType.file:
        return '文件';
    }
  }
}

/// AttachmentRef 值对象。attachment_id 形如 `att_` + 26 字符 ULID。
class AttachmentRef {
  const AttachmentRef({
    required this.attachmentId,
    required this.type,
    required this.mime,
    required this.size,
    this.sha256,
    this.width,
    this.height,
    this.durationMs,
  });

  /// `att_` + ULID（§3）。
  final String attachmentId;
  final AttachmentType type;
  final String mime;

  /// 字节数。
  final int size;
  final String? sha256;
  final int? width;
  final int? height;
  final int? durationMs;

  /// attachment_id 格式（att_ + 26 字符 Crockford Base32 ULID）。
  static final RegExp idPattern =
      RegExp(r'^att_[0-9A-HJKMNP-TV-Z]{26}$');

  bool get hasValidId => idPattern.hasMatch(attachmentId);

  factory AttachmentRef.fromJson(Map<String, dynamic> json) {
    return AttachmentRef(
      attachmentId: json['attachment_id'] as String? ?? '',
      type: AttachmentType.fromWire(json['type'] as String?),
      mime: json['mime'] as String? ?? 'application/octet-stream',
      size: (json['size'] as num?)?.toInt() ?? 0,
      sha256: json['sha256'] as String?,
      width: (json['width'] as num?)?.toInt(),
      height: (json['height'] as num?)?.toInt(),
      durationMs: (json['duration_ms'] as num?)?.toInt(),
    );
  }

  /// 严格 schema 子集序列化（不多一个字段）。
  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'attachment_id': attachmentId,
      'type': type.wire,
      'mime': mime,
      'size': size,
      if (sha256 != null) 'sha256': sha256,
      if (width != null) 'width': width,
      if (height != null) 'height': height,
      if (durationMs != null) 'duration_ms': durationMs,
    };
  }

  /// 聊天请求中的附件引用（仅 id）。
  Map<String, dynamic> toMessageRef() =>
      <String, dynamic>{'attachment_id': attachmentId};

  /// 人类可读大小。
  String get sizeLabel {
    if (size < 1024) return '$size B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)} KB';
    return '${(size / 1024 / 1024).toStringAsFixed(1)} MB';
  }
}
