import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'device_auth.dart';
import 'device_config.dart';

/// Link 票据（C 鉴权）：`POST /api/devices/link_ticket` 签发，900s 有效、
/// 绑 device_id、可复用（重连）、过期/401 重签；撤销设备即刻失效。
class LinkTicket {
  const LinkTicket({
    required this.value,
    required this.deviceId,
    required this.expiresAtMs,
  });

  /// 票据本体（凭证纪律：只进 hello 帧/票据头，零日志）。
  final String value;
  final String deviceId;

  /// 过期时刻（Unix 毫秒）。
  final int expiresAtMs;
}

/// 票据获取异常：显式可见，不静默重试。
class LinkTicketException implements Exception {
  const LinkTicketException(this.code, this.message, {this.httpStatus});

  /// identity_unavailable / network_error / unauthorized / bad_response / http_NNN。
  final String code;
  final String message;
  final int? httpStatus;

  @override
  String toString() => '[$code] $message';
}

/// Link 票据服务（契约 §2.2 C 方案 + core `_http_link_ticket` 同构）。
///
/// 链路：设备 Ed25519 身份（设备 WS 已 ONLINE 的先验身份）→
/// `POST {base}/api/devices/link_ticket`（§11 签名四头，签
/// `device_id\nts\nnonce\nPOST\n/api/devices/link_ticket`，复用
/// [DeviceAuth]）→ `{ticket, expires_in:900, device_id}`。
///
/// 复用纪律：900s 内重连复用同票（留 30s 安全边际）；过期/服务端 401/撤销
/// → [invalidate] 后重取。凭证零日志。
class LinkTicketService {
  LinkTicketService({String? baseUrl, HttpClient? httpClient, DeviceAuth? auth})
      : _baseUrlOverride = baseUrl,
        _http = httpClient ?? HttpClient(),
        _auth = auth ?? DeviceAuth();

  final String? _baseUrlOverride;
  final HttpClient _http;
  final DeviceAuth _auth;

  static const Duration timeout = Duration(seconds: 15);

  /// 复用安全边际：距过期不足 30s 即重签（避免在飞握手用到临期票）。
  static const int safetyMarginMs = 30 * 1000;

  LinkTicket? _cached;

  /// 取有效票据（缓存有效则复用，否则重签）。
  Future<LinkTicket> getValid() async {
    final cached = _cached;
    if (cached != null &&
        DateTime.now().millisecondsSinceEpoch <
            cached.expiresAtMs - safetyMarginMs) {
      return cached;
    }
    final fresh = await _fetch();
    _cached = fresh;
    return fresh;
  }

  /// 作废缓存（401/撤销/鉴权失败后下次 getValid 必重签）。
  void invalidate() {
    _cached = null;
  }

  /// 当前缓存票据值（无则 null；artifacts 头等复用面用，不触发重签）。
  String? get currentTicketValue {
    final cached = _cached;
    if (cached == null) return null;
    if (DateTime.now().millisecondsSinceEpoch >=
        cached.expiresAtMs - safetyMarginMs) {
      return null;
    }
    return cached.value;
  }

  Future<LinkTicket> _fetch() async {
    final base =
        _baseUrlOverride ?? await DeviceConfig.baseUrl();
    final uri = Uri.parse('$base/api/devices/link_ticket');
    // §11 签名四头：设备身份不可用（未配对/桥未就绪）显式失败，不降级。
    final Map<String, String> signed;
    try {
      signed = await _auth.headers('POST', DeviceAuth.pathWithQuery(uri));
    } on Exception catch (e) {
      throw LinkTicketException(
          'identity_unavailable', '设备身份不可用（设备线未就绪/未配对）：$e');
    }
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http.postUrl(uri).timeout(timeout);
      req.headers.set(HttpHeaders.acceptHeader, 'application/json');
      req.headers
          .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      signed.forEach((k, v) => req.headers.set(k, v));
      req.write('{}');
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw LinkTicketException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(text) as Map).cast<String, dynamic>();
    } catch (_) {
      throw LinkTicketException(
          'bad_response', 'HTTP ${res.statusCode}: 非 JSON 响应',
          httpStatus: res.statusCode);
    }
    if (res.statusCode < 200 || res.statusCode >= 300 || json['ok'] != true) {
      final err = json['error'];
      final code = err is Map
          ? err['code'] as String? ?? 'http_${res.statusCode}'
          : 'http_${res.statusCode}';
      // 401/撤销：作废缓存（下次重取仍须先解决身份/配对问题）
      invalidate();
      throw LinkTicketException(
        code,
        err is Map ? err['message'] as String? ?? '' : text,
        httpStatus: res.statusCode,
      );
    }
    final result = (json['result'] as Map?)?.cast<String, dynamic>();
    final ticket = result?['ticket'] as String?;
    final deviceId = result?['device_id'] as String?;
    final expiresIn = (result?['expires_in'] as num?)?.toInt();
    if (result == null ||
        ticket == null ||
        ticket.isEmpty ||
        deviceId == null ||
        expiresIn == null) {
      throw const LinkTicketException(
          'bad_response', '票据应答缺 result.ticket/expires_in/device_id');
    }
    return LinkTicket(
      value: ticket,
      deviceId: deviceId,
      expiresAtMs:
          DateTime.now().millisecondsSinceEpoch + expiresIn * 1000,
    );
  }

  void close() => _http.close();
}
