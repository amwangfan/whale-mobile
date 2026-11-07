import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/conversation.dart';
import '../models/message.dart';
import '../models/persona.dart';
import 'device_config.dart';

/// Core REST 异常：显式可见，不静默重试。
class ChatApiException implements Exception {
  const ChatApiException(this.code, this.message, {this.httpStatus});

  /// Core error.code（bad_request/unauthorized/not_found/...）或本地码
  /// （network_error / bad_response / no_token）。
  final String code;
  final String message;
  final int? httpStatus;

  @override
  String toString() => '[$code] $message';
}

/// 聊天面 REST 封装（contracts/v2 §11 + Core /api/v2 移动端聊天面）。
///
/// 已实现端点（Core routes/mobile_chat.py）：
/// - GET  /api/v2/conversations?cursor=&limit=
/// - GET  /api/v2/conversations/{id}/messages?cursor=&limit=
/// - POST /api/v2/chat/attachments（multipart，见 attachment_service.dart）
/// - GET/DELETE /api/v2/attachments/{id}
/// - POST /api/v2/chat/stream（SSE，见 chat_stream.dart）
///
/// 角色（规划 §5.6）经工作室面：GET /api/studio/personas、
/// PUT /api/studio/conversations/{key}/persona。
///
/// base_url / token 来自设备配置（DeviceConfig：SharedPreferences 覆盖
/// dart-define 默认）。
class ChatApi {
  ChatApi({String? baseUrl, String? token, HttpClient? httpClient})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final HttpClient _http;

  static const Duration timeout = Duration(seconds: 15);

  Future<String> get _baseUrl async =>
      _baseUrlOverride ?? await DeviceConfig.baseUrl();

  Future<String> get _token async =>
      _tokenOverride ?? await DeviceConfig.apiToken();

  Uri _uri(String base, String path, [Map<String, String>? query]) {
    return Uri.parse('$base$path').replace(queryParameters: query);
  }

  Future<Map<String, String>> _headers() async {
    final token = await _token;
    return <String, String>{
      HttpHeaders.acceptHeader: 'application/json',
      if (token.isNotEmpty)
        HttpHeaders.authorizationHeader: 'Bearer $token',
    };
  }

  /// 统一 GET JSON：拆 `{result: ...}` 或 `{error: ...}` 信封。
  Future<Map<String, dynamic>> _getJson(String path,
      [Map<String, String>? query]) async {
    final base = await _baseUrl;
    final headers = await _headers();
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http.getUrl(_uri(base, path, query)).timeout(timeout);
      headers.forEach((k, v) => req.headers.set(k, v));
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }
    final body = await utf8.decodeStream(res).timeout(timeout);
    return _decodeEnvelope(res.statusCode, body);
  }

  /// 统一 JSON body 请求（PUT 等）。
  Future<Map<String, dynamic>> _sendJson(
      String method, String path, Object bodyObj) async {
    final base = await _baseUrl;
    final headers = await _headers();
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http
          .openUrl(method, _uri(base, path))
          .timeout(timeout);
      headers.forEach((k, v) => req.headers.set(k, v));
      req.headers
          .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      req.write(jsonEncode(bodyObj));
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    return _decodeEnvelope(res.statusCode, text);
  }

  Map<String, dynamic> _decodeEnvelope(int status, String body) {
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(body) as Map).cast<String, dynamic>();
    } catch (_) {
      throw ChatApiException('bad_response', 'HTTP $status: 非 JSON 响应',
          httpStatus: status);
    }
    if (status >= 200 && status < 300) {
      final result = json['result'];
      // 附件上传等端点直接返回对象本体（无 result 包装）
      if (result is Map) return result.cast<String, dynamic>();
      return json;
    }
    final err = json['error'];
    if (err is Map) {
      throw ChatApiException(
        err['code'] as String? ?? 'http_$status',
        err['message'] as String? ?? 'HTTP $status',
        httpStatus: status,
      );
    }
    throw ChatApiException('http_$status', 'HTTP $status: $body',
        httpStatus: status);
  }

  // ---------------------------------------------------------------- 会话

  /// GET /api/v2/conversations（updated_at 倒序，cursor 不透明）。
  Future<ConversationPage> listConversations({
    String? cursor,
    int limit = 20,
  }) async {
    final result = await _getJson('/api/v2/conversations', <String, String>{
      'limit': '$limit',
      'cursor': ?cursor,
    });
    final items = (result['conversations'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => Conversation.fromJson(m.cast<String, dynamic>()))
        .toList();
    return ConversationPage(
      conversations: items,
      nextCursor: result['next_cursor'] as String?,
    );
  }

  /// GET /api/v2/conversations/{id}/messages（新→旧倒序）。
  Future<MessagePage> listMessages(
    String conversationId, {
    String? cursor,
    int limit = 50,
  }) async {
    final result = await _getJson(
      '/api/v2/conversations/${Uri.encodeComponent(conversationId)}/messages',
      <String, String>{
        'limit': '$limit',
        'cursor': ?cursor,
      },
    );
    final items = (result['messages'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => ChatMessage.fromJson(m.cast<String, dynamic>()))
        .toList();
    return MessagePage(
      messages: items,
      nextCursor: result['next_cursor'] as String?,
    );
  }

  // ---------------------------------------------------------------- 角色

  /// GET /api/studio/personas（极简视图；不含内部 prompt）。
  Future<List<Persona>> listPersonas() async {
    final result = await _getJson('/api/studio/personas');
    return (result['personas'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => Persona.fromJson(m.cast<String, dynamic>()))
        .toList();
  }

  /// PUT /api/studio/conversations/{key}/persona：绑定/解绑角色。
  /// [personaId] 传 null 表示解绑（回默认角色）。
  Future<void> bindPersona(String conversationKey, String? personaId) async {
    await _sendJson(
      'PUT',
      '/api/studio/conversations/${Uri.encodeComponent(conversationKey)}/persona',
      <String, dynamic>{'persona_id': personaId},
    );
  }

  // ---------------------------------------------------------------- 附件

  /// DELETE /api/v2/attachments/{id}（幂等语义：已删 → 410）。
  Future<void> deleteAttachment(String attachmentId) async {
    final base = await _baseUrl;
    final headers = await _headers();
    try {
      final req = await _http
          .openUrl('DELETE', _uri(base, '/api/v2/attachments/$attachmentId'))
          .timeout(timeout);
      headers.forEach((k, v) => req.headers.set(k, v));
      final res = await req.close().timeout(timeout);
      if (res.statusCode >= 300) {
        final body = await utf8.decodeStream(res);
        _decodeEnvelope(res.statusCode, body);
      }
    } on ChatApiException {
      rethrow;
    } on Exception catch (e) {
      throw ChatApiException('network_error', '$e');
    }
  }

  /// 附件下载地址（用于 Image.network / cached_network_image 的鉴权 header 由
  /// attachment_service 提供）。
  Future<Uri> attachmentUri(String attachmentId) async {
    final base = await _baseUrl;
    return Uri.parse('$base/api/v2/attachments/$attachmentId');
  }

  /// 附件下载鉴权头（cached_network_image 用）。
  Future<Map<String, String>> authHeaders() => _headers();

  void close() => _http.close();
}
