import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'dart:typed_data' show BytesBuilder;
import 'package:image_picker/image_picker.dart';

import '../models/attachment.dart';
import 'chat_api.dart';
import 'device_config.dart';

/// 已选择待上传的本地附件。
class PickedAttachment {
  const PickedAttachment({
    required this.bytes,
    required this.filename,
    required this.mime,
    required this.declaredType,
  });

  final List<int> bytes;
  final String filename;
  final String mime;

  /// §11 type 声明（image/video/audio/file；Core 会按 mime 校验）。
  final String declaredType;
}

/// 附件服务：选择（image_picker / file_picker）+ multipart 上传 + 下载。
///
/// 上传：POST /api/v2/chat/attachments（multipart/form-data，字段 file +
/// 可选 type 声明）→ 201 AttachmentRef（响应体即 schema 对象本体）。
class AttachmentService {
  AttachmentService({String? baseUrl, String? token, HttpClient? httpClient})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final HttpClient _http;
  final ImagePicker _picker = ImagePicker();

  static const Duration timeout = Duration(seconds: 60);

  Future<String> get _baseUrl async =>
      _baseUrlOverride ?? await DeviceConfig.baseUrl();

  Future<String> get _token async =>
      _tokenOverride ?? await DeviceConfig.apiToken();

  // ---------------------------------------------------------------- 选择

  /// 从相册选图片。
  Future<PickedAttachment?> pickImage() async {
    final x = await _picker.pickImage(source: ImageSource.gallery);
    if (x == null) return null;
    return PickedAttachment(
      bytes: await x.readAsBytes(),
      filename: x.name,
      mime: x.mimeType ?? _mimeFromName(x.name) ?? 'image/jpeg',
      declaredType: 'image',
    );
  }

  /// 拍摄照片。
  Future<PickedAttachment?> takePhoto() async {
    final x = await _picker.pickImage(source: ImageSource.camera);
    if (x == null) return null;
    return PickedAttachment(
      bytes: await x.readAsBytes(),
      filename: x.name,
      mime: x.mimeType ?? 'image/jpeg',
      declaredType: 'image',
    );
  }

  /// 选任意文件（按 mime 粗分 declaredType）。
  Future<PickedAttachment?> pickFile() async {
    final result = await FilePicker.platform.pickFiles(withData: true);
    final f = result?.files.firstOrNull;
    if (f == null || f.bytes == null) return null;
    final mime = _mimeFromName(f.name) ?? 'application/octet-stream';
    return PickedAttachment(
      bytes: f.bytes!,
      filename: f.name,
      mime: mime,
      declaredType: _typeFromMime(mime),
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

  static String _typeFromMime(String mime) {
    if (mime.startsWith('image/')) return 'image';
    if (mime.startsWith('video/')) return 'video';
    if (mime.startsWith('audio/')) return 'audio';
    return 'file';
  }

  // ---------------------------------------------------------------- 上传

  /// multipart 上传 → AttachmentRef。手写 multipart（不引入 http 包）。
  Future<AttachmentRef> upload(PickedAttachment picked) async {
    final base = await _baseUrl;
    final token = await _token;
    final uri = Uri.parse('$base/api/v2/chat/attachments');
    const boundary = '----whale-mobile-upload-boundary';
    final HttpClientRequest req;
    try {
      req = await _http.postUrl(uri).timeout(timeout);
      req.headers.set(HttpHeaders.contentTypeHeader,
          'multipart/form-data; boundary=$boundary');
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }

    final body = BytesBuilder();
    void writeField(String name, String value) {
      body.add(utf8.encode(
          '--$boundary\r\nContent-Disposition: form-data; name="$name"\r\n\r\n$value\r\n'));
    }

    writeField('type', picked.declaredType);
    body.add(utf8.encode(
        '--$boundary\r\nContent-Disposition: form-data; name="file"; '
        'filename="${picked.filename}"\r\n'
        'Content-Type: ${picked.mime}\r\n\r\n'));
    body.add(picked.bytes);
    body.add(utf8.encode('\r\n--$boundary--\r\n'));

    req.contentLength = body.length;
    req.add(body.takeBytes());
    final HttpClientResponse res;
    try {
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(text) as Map).cast<String, dynamic>();
    } catch (_) {
      throw ChatApiException('bad_response', 'HTTP ${res.statusCode}: 非 JSON',
          httpStatus: res.statusCode);
    }
    if (res.statusCode != 201) {
      final err = json['error'];
      throw ChatApiException(
        err is Map ? err['code'] as String? ?? 'http_${res.statusCode}' : 'http_${res.statusCode}',
        err is Map ? err['message'] as String? ?? '' : text,
        httpStatus: res.statusCode,
      );
    }
    return AttachmentRef.fromJson(json);
  }

  // ---------------------------------------------------------------- 下载

  /// 下载附件字节（用于保存/打开）。
  Future<List<int>> download(String attachmentId) async {
    final base = await _baseUrl;
    final token = await _token;
    final uri = Uri.parse('$base/api/v2/attachments/$attachmentId');
    try {
      final req = await _http.getUrl(uri).timeout(timeout);
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      final res = await req.close().timeout(timeout);
      if (res.statusCode != 200) {
        throw ChatApiException('http_${res.statusCode}',
            '下载失败 HTTP ${res.statusCode}',
            httpStatus: res.statusCode);
      }
      final builder = BytesBuilder(copy: false);
      await for (final chunk in res) {
        builder.add(chunk);
      }
      return builder.takeBytes();
    } on ChatApiException {
      rethrow;
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }
  }

  void close() => _http.close();
}
