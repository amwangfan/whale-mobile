import 'package:file_picker/file_picker.dart';
import 'package:image_picker/image_picker.dart';

import 'chat_images.dart';

/// 已选择待发送的本地图片（聊天图片第一版：data: URL，不走附件上行）。
class PickedImage {
  const PickedImage({
    required this.bytes,
    required this.mime,
    required this.filename,
  });

  /// 已压回 ≤2MB（ChatImages.fitToLimit）。
  final List<int> bytes;
  final String mime;
  final String filename;

  String get dataUrl => ChatImages.toDataUrl(bytes, mime);
}

/// 已选择待上传的本地文件（走 POST /api/artifacts；核心差距 404 探测降级）。
class PickedFile {
  const PickedFile({
    required this.bytes,
    required this.filename,
    required this.mime,
  });

  final List<int> bytes;
  final String filename;
  final String mime;
}

/// 聊天附件选择（image_picker / file_picker）。
class ChatAttachments {
  ChatAttachments({ImagePicker? picker}) : _picker = picker ?? ImagePicker();

  final ImagePicker _picker;

  /// 从相册选图片（>2MB 自动降尺寸/重编码压回界内）。
  Future<PickedImage?> pickImage() async {
    final x = await _picker.pickImage(source: ImageSource.gallery);
    if (x == null) return null;
    final raw = await x.readAsBytes();
    final mime = x.mimeType ?? _mimeFromName(x.name) ?? 'image/jpeg';
    final fitted = ChatImages.fitToLimit(raw, mime);
    return PickedImage(
      bytes: fitted,
      // fitToLimit 未改动时返回同一对象（保持原 mime）；重编码恒出 JPEG
      mime: identical(fitted, raw) ? mime : 'image/jpeg',
      filename: x.name,
    );
  }

  /// 拍摄照片。
  Future<PickedImage?> takePhoto() async {
    final x = await _picker.pickImage(source: ImageSource.camera);
    if (x == null) return null;
    final raw = await x.readAsBytes();
    final mime = x.mimeType ?? 'image/jpeg';
    final fitted = ChatImages.fitToLimit(raw, mime);
    return PickedImage(
      bytes: fitted,
      mime: identical(fitted, raw) ? mime : 'image/jpeg',
      filename: x.name,
    );
  }

  /// 选任意文件（上行 POST /api/artifacts；404=core 未实现该面）。
  Future<PickedFile?> pickFile() async {
    final result = await FilePicker.platform.pickFiles(withData: true);
    final f = result?.files.firstOrNull;
    if (f == null || f.bytes == null) return null;
    return PickedFile(
      bytes: f.bytes!,
      filename: f.name,
      mime: _mimeFromName(f.name) ?? 'application/octet-stream',
    );
  }

  static String? _mimeFromName(String name) {
    final dot = name.lastIndexOf('.');
    if (dot < 0) return null;
    switch (name.substring(dot + 1).toLowerCase()) {
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'png':
        return 'image/png';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'mp4':
        return 'video/mp4';
      case 'webm':
        return 'video/webm';
      case 'mp3':
        return 'audio/mpeg';
      case 'wav':
        return 'audio/wav';
      case 'm4a':
        return 'audio/mp4';
      case 'txt':
        return 'text/plain';
      case 'md':
        return 'text/markdown';
      case 'pdf':
        return 'application/pdf';
      case 'zip':
        return 'application/zip';
    }
    return null;
  }
}
