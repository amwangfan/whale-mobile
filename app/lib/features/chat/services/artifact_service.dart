import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'dart:typed_data' show BytesBuilder;

import '../models/artifact.dart';
import 'device_config.dart';

/// Artifact REST 面（v3 契约 §3：统一 `/api/artifacts`；Link 面 Bearer 鉴权）。
///
/// - `GET /api/artifacts/{id}`：Bearer 拉流（core 已实现）；
/// - `POST /api/artifacts`：raw body + `X-Whale-Name`/`X-Whale-Content-Type`
///   → 201 `{id,url,content_type,size,sha256}`；**核心差距**：未落地的 core
///   返回 404 → 特性探测降级为 [ArtifactException] code=not_implemented，
///   UI 显式提示，不当作普通失败重试（契约 §3 口径）；
/// - `DELETE /api/artifacts/{id}`：同属核心差距，同样 404 探测。
///
/// 聊天图片第一版不走上行：`chat.send.images` 直接收 data: URL（≤4 张，
/// 每张 ≤2 MB，见 ChatImageCodec）。
class ArtifactService {
  ArtifactService({String? baseUrl, String? token, HttpClient? httpClient})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final HttpClient _http;

  static const Duration timeout = Duration(seconds: 60);

  Future<String> get _baseUrl async =>
      _baseUrlOverride ?? await DeviceConfig.baseUrl();

  Future<String> get _token async =>
      _tokenOverride ?? await DeviceConfig.apiToken();

  /// 附件下载地址（Image.network / cached_network_image 用）。
  Future<Uri> artifactUri(String artifactId) async {
    final base = await _baseUrl;
    return Uri.parse('$base/api/artifacts/$artifactId');
  }

  /// Bearer 鉴权头（Link 面 Bearer/ticket；聊天面不依赖设备签名四头）。
  Future<Map<String, String>> authHeaders() async {
    final token = await _token;
    return <String, String>{
      HttpHeaders.acceptHeader: '*/*',
      if (token.isNotEmpty)
        HttpHeaders.authorizationHeader: 'Bearer $token',
    };
  }

  /// 下载字节（保存/打开用）。404=artifact 不存在（GET 面 core 已实现，
  /// 404 即真 not_found）。
  Future<List<int>> download(String artifactId) async {
    final uri = await artifactUri(artifactId);
    final token = await _token;
    try {
      final req = await _http.getUrl(uri).timeout(timeout);
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      final res = await req.close().timeout(timeout);
      if (res.statusCode != 200) {
        throw ArtifactException(
          res.statusCode == 404 ? 'not_found' : 'http_${res.statusCode}',
          '下载失败 HTTP ${res.statusCode}',
          httpStatus: res.statusCode,
        );
      }
      final builder = BytesBuilder(copy: false);
      await for (final chunk in res) {
        builder.add(chunk);
      }
      return builder.takeBytes();
    } on ArtifactException {
      rethrow;
    } on Exception catch (e) {
      throw ArtifactException('network_error', '$e');
    }
  }

  /// 上行：raw body + X-Whale-Name/X-Whale-Content-Type → 201 ArtifactRef。
  ///
  /// 404 → [ArtifactException] code=not_implemented（核心差距特性探测：
  /// core 未实现该面；UI 显式降级提示，不重试）。
  Future<ArtifactRef> upload({
    required List<int> bytes,
    required String name,
    required String contentType,
  }) async {
    final base = await _baseUrl;
    final token = await _token;
    final uri = Uri.parse('$base/api/artifacts');
    final HttpClientRequest req;
    try {
      req = await _http.postUrl(uri).timeout(timeout);
      req.headers.set(HttpHeaders.contentTypeHeader, 'application/octet-stream');
      req.headers.set('X-Whale-Name', name);
      req.headers.set('X-Whale-Content-Type', contentType);
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      req.contentLength = bytes.length;
      req.add(bytes);
    } on Exception catch (e) {
      throw ArtifactException('network_error', '$e');
    }
    final HttpClientResponse res;
    try {
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw ArtifactException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    if (res.statusCode == 404) throw ArtifactService.notImplemented();
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(text) as Map).cast<String, dynamic>();
    } catch (_) {
      throw ArtifactException('bad_response', 'HTTP ${res.statusCode}: 非 JSON',
          httpStatus: res.statusCode);
    }
    if (res.statusCode != 201 || json['ok'] != true) {
      final err = json['error'];
      throw ArtifactException(
        err is Map ? err['code'] as String? ?? 'http_${res.statusCode}' : 'http_${res.statusCode}',
        err is Map ? err['message'] as String? ?? '' : text,
        httpStatus: res.statusCode,
      );
    }
    final result = (json['result'] as Map?)?.cast<String, dynamic>();
    if (result == null) {
      throw const ArtifactException('bad_response', '上行应答缺少 result');
    }
    return ArtifactRef.fromJson(result);
  }

  /// 特性探测占位：core 未实现上行/删除面（契约 §3 核心差距）。
  static ArtifactException notImplemented() => const ArtifactException(
        'not_implemented',
        '当前 Core 未实现附件上行（契约 §3 核心差距，随 C 排期）；聊天图片请直接发送（≤4 张）',
        httpStatus: 404,
      );

  void close() => _http.close();
}

/// Artifact REST 异常：显式可见，不静默重试。
class ArtifactException implements Exception {
  const ArtifactException(this.code, this.message, {this.httpStatus});

  /// not_implemented / not_found / network_error / bad_response / http_NNN。
  final String code;
  final String message;
  final int? httpStatus;

  @override
  String toString() => '[$code] $message';
}
