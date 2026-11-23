import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/conversation.dart';
import '../models/message.dart';
import '../models/persona.dart';
import 'device_auth.dart';
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

/// 聊天面 REST 封装（contracts/device §11 + Core 移动端聊天面）。
///
/// 已实现端点（Core routes/mobile_chat.py）：
/// - GET  /api/v2/conversations?cursor=&limit=          （clean break 未覆盖，见下）
/// - GET  /api/v2/conversations/{id}/messages?cursor=&limit=（同上）
/// - POST /api/chat/attachments（multipart，见 attachment_service.dart；去版本化）
/// - GET/DELETE /api/attachments/{id}（去版本化）
/// - POST /api/v2/chat/stream（SSE，见 chat_stream.dart；clean break 未覆盖）
///
/// 2026-11-23 clean break 只覆盖设备通道（WSS/配对）与附件面；会话/消息/SSE
/// 属聊天面另行对齐（新 core 尚无对应 REST）。附件面 core 尚未实现：404 一律
/// 映射为 [ChatApiException] code=not_implemented（特性探测语义），UI 显式提示。
///
/// 角色（规划 §5.6）经工作室面：GET /api/studio/personas、
/// PUT /api/studio/conversations/{key}/persona。
///
/// base_url / token 来自设备配置（DeviceConfig：SharedPreferences 覆盖
/// dart-define 默认）。
class ChatApi {
  ChatApi(
      {String? baseUrl,
      String? token,
      HttpClient? httpClient,
      DeviceAuth? auth})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _auth = auth ?? DeviceAuth(),
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final DeviceAuth _auth;
  final HttpClient _http;

  static const Duration timeout = Duration(seconds: 15);

  Future<String> get _baseUrl async =>
      _baseUrlOverride ?? await DeviceConfig.baseUrl();

  Future<String> get _token async =>
      _tokenOverride ?? await DeviceConfig.apiToken();

  Uri _uri(String base, String path, [Map<String, String>? query]) {
    return Uri.parse('$base$path').replace(queryParameters: query);
  }

  /// 请求头：Accept + Bearer（保留不动）+ 设备签名四头（contracts/device §11，
  /// 云端对设备通道面 fail-closed）。[pathWithQuery] 必须与实际请求线一致（含 query）。
  Future<Map<String, String>> _headers(
      String method, String pathWithQuery) async {
    final token = await _token;
    final Map<String, String> signed;
    try {
      signed = await _auth.headers(method, pathWithQuery);
    } on Exception catch (e) {
      // 签名不可用（桥未就绪/身份缺失）显式失败，绝不静默降级为无签名请求
      throw ChatApiException('sign_error', '$e');
    }
    return <String, String>{
      HttpHeaders.acceptHeader: 'application/json',
      if (token.isNotEmpty)
        HttpHeaders.authorizationHeader: 'Bearer $token',
      ...signed,
    };
  }

  /// 统一 GET JSON：拆 `{result: ...}` 或 `{error: ...}` 信封。
  Future<Map<String, dynamic>> _getJson(String path,
      [Map<String, String>? query]) async {
    final base = await _baseUrl;
    final uri = _uri(base, path, query);
    final headers = await _headers('GET', DeviceAuth.pathWithQuery(uri));
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http.getUrl(uri).timeout(timeout);
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
    final uri = _uri(base, path);
    final headers = await _headers(method, DeviceAuth.pathWithQuery(uri));
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http
          .openUrl(method, uri)
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

  /// 附件 REST 面特性探测（2026-11-23 clean break）：契约路径已去版本化
  /// （/api/chat/attachments、/api/attachments/{id}），但 core 尚未实现该面——
  /// 404 一律视为「未实现」，映射为显式 not_implemented，不当作普通失败重试。
  static ChatApiException notImplemented() => const ChatApiException(
        'not_implemented',
        '当前 Core 未实现附件 REST（契约 §11 去版本化路径；待 core 落地该面）',
        httpStatus: 404,
      );

  /// DELETE /api/attachments/{id}（幂等语义：已删 → 410；404 → not_implemented）。
  Future<void> deleteAttachment(String attachmentId) async {
    final base = await _baseUrl;
    final uri = _uri(base, '/api/attachments/$attachmentId');
    final headers = await _headers('DELETE', DeviceAuth.pathWithQuery(uri));
    try {
      final req = await _http
          .openUrl('DELETE', uri)
          .timeout(timeout);
      headers.forEach((k, v) => req.headers.set(k, v));
      final res = await req.close().timeout(timeout);
      if (res.statusCode == 404) throw notImplemented();
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
    return Uri.parse('$base/api/attachments/$attachmentId');
  }

  /// 附件图片预览鉴权头（cached_network_image / Image.network 的静态头场景）。
  ///
  /// 仅含 Bearer：设备签名四头绑「method+path+ts+nonce」，而此处的调用方
  /// （chat_page）对整页不同附件 id 复用一组静态头——签名既对不上逐 id 的
  /// path，ts 也会过期。云端 fail-closed 后图片预览需改为按附件 id 即时签名
  /// （另案）；此处保持原 Bearer 行为，不伪装已签。
  Future<Map<String, String>> authHeaders() async {
    final token = await _token;
    return <String, String>{
      HttpHeaders.acceptHeader: 'application/json',
      if (token.isNotEmpty)
        HttpHeaders.authorizationHeader: 'Bearer $token',
    };
  }

  void close() => _http.close();
}
