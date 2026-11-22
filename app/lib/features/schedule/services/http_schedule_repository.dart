import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/schedule_entry.dart';
import '../../chat/services/device_auth.dart';
import '../../chat/services/device_config.dart';
import 'schedule_repository.dart';

/// /v1/schedule/* 的 HTTP 实现（云端 0c76d12 已上线；2026-11-19 联调）。
///
/// 基址与凭据沿用 DeviceConfig（Core 控制台同一 Bearer；联调期可
/// `--dart-define=WM_CORE_BASE_URL/WM_CORE_TOKEN` 注入，不入库）。
/// 线上信封实况（与契约文档的字面出入，2026-11-20 实测 glasses.py 部署版）：
/// 候选列表键 `candidates`、来源上下文键 `context`、条目 id 写作 item_id、时间为
/// ISO 串；候选裁决=POST /candidates {id,action:add|reject}；删除=DELETE /items
/// body {id}（非路径分段）；无 ok 包裹（2xx 即成功，删除回 {"ok":bool}）。
class HttpScheduleRepository implements ScheduleRepository {
  HttpScheduleRepository(
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
  static const String _prefix = '/v1/schedule';

  Future<String> get _baseUrl async =>
      _baseUrlOverride ?? await DeviceConfig.baseUrl();
  Future<String> get _token async =>
      _tokenOverride ?? await DeviceConfig.apiToken();

  Future<Map<String, dynamic>> _request(
    String method,
    String path, {
    Map<String, String>? query,
    Object? body,
  }) async {
    final base = await _baseUrl;
    final token = await _token;
    final uri =
        Uri.parse('$base$_prefix$path').replace(queryParameters: query);
    // 设备签名四头（契约 §11；Bearer 保留不动）。签名载荷 path=线上路径含 query。
    final Map<String, String> signed;
    try {
      signed = await _auth.headers(method, DeviceAuth.pathWithQuery(uri));
    } on Exception catch (e) {
      throw ScheduleApiException('sign_error', '$e');
    }
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http.openUrl(method, uri).timeout(timeout);
      req.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      signed.forEach((k, v) => req.headers.set(k, v));
      if (body != null) {
        req.headers.set(
            HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
        req.write(jsonEncode(body));
      }
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw ScheduleApiException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(text) as Map).cast<String, dynamic>();
    } catch (_) {
      throw ScheduleApiException('bad_response', 'HTTP ${res.statusCode}: 非 JSON 响应',
          httpStatus: res.statusCode);
    }
    if (res.statusCode >= 200 && res.statusCode < 300) return json;
    throw ScheduleApiException('http_${res.statusCode}',
        'HTTP ${res.statusCode}: ${json['error'] ?? text}',
        httpStatus: res.statusCode);
  }

  static List<ScheduleEntry> _entries(Map<String, dynamic> json) =>
      // 线上候选键 candidates、正式键 items；两种键名都收（契约文档写作 items）。
      (json['items'] as List? ?? json['candidates'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => ScheduleEntry.fromMap(m.cast<String, dynamic>()))
          .toList();

  @override
  Future<List<ScheduleEntry>> fetchCandidates() async =>
      _entries(await _request('GET', '/candidates'));

  @override
  Future<List<ScheduleEntry>> fetchItems() async =>
      _entries(await _request('GET', '/list'));

  @override
  Future<void> addCandidate(String id) =>
      _request('POST', '/candidates', body: <String, dynamic>{
        'id': id,
        'action': 'add',
      }).then((_) {});

  @override
  Future<void> rejectCandidate(String id) =>
      _request('POST', '/candidates', body: <String, dynamic>{
        'id': id,
        'action': 'reject',
      }).then((_) {});

  @override
  Future<ScheduleEntry> createItem({
    required String title,
    required String date,
    String? time,
  }) async {
    final json = await _request('POST', '/items',
        body: <String, dynamic>{'title': title, 'date': date, 'time': ?time});
    final item = json['item'];
    if (item is Map) return ScheduleEntry.fromMap(item.cast<String, dynamic>());
    // 云端不回体时的兜底视图（已与服务端对账：行存在）
    return ScheduleEntry(
        id: '', title: title, date: date, time: time, state: 'added', createdAt: 0);
  }

  @override
  Future<void> deleteItem(String id) async {
    // 线上：DELETE /items，id 在 JSON body（非路径分段）；{"ok":bool} 如实判读。
    final json =
        await _request('DELETE', '/items', body: <String, dynamic>{'id': id});
    if (json['ok'] != true) {
      throw ScheduleApiException('not_found', '删除未成功：$id');
    }
  }

  @override
  Future<List<SourceMessage>> fetchSource({
    required String conversation,
    required String messageId,
  }) async {
    final json = await _request('GET', '/source', query: <String, String>{
      'conversation': conversation,
      'messageId': messageId,
    });
    // 线上键为 context（±10 条，owner-only）；契约文档写作 items——两键都收。
    return (json['context'] as List? ?? json['items'] as List? ?? const [])
        .whereType<Map>()
        .map((m) => SourceMessage.fromMap(m.cast<String, dynamic>()))
        .toList();
  }

  void close() => _http.close();
}

/// 日程 REST 异常：显式可见（code=network_error/bad_response/http_*）。
class ScheduleApiException implements Exception {
  const ScheduleApiException(this.code, this.message, {this.httpStatus});

  final String code;
  final String message;
  final int? httpStatus;

  @override
  String toString() => '[$code] $message';
}
