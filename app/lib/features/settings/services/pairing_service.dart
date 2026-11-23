import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../../transport/transport_client_v2.dart';
import '../../chat/services/device_config.dart';

/// 配对结果（POST /api/devices/pair 200 应答的 result 本体 + 派生 WS 地址）。
class PairingResult {
  const PairingResult({
    required this.deviceId,
    required this.name,
    required this.scopes,
    required this.wsPath,
    required this.wsUrl,
  });

  final String deviceId;
  final String name;
  final List<String> scopes;

  /// core 应答的 WS 路径事实源（如 /api/devices/ws）——以后路径再变端侧零改动。
  final String wsPath;

  /// 由 base_url + [wsPath] 合成的 WS 全路径（可直接交 TransportClientV2.connect）。
  final String wsUrl;
}

/// 配对失败：显式可见，不静默重试。
class PairingException implements Exception {
  const PairingException(this.code, this.message, {this.httpStatus});

  /// core 错误码（invalid_params/not_found/conflict/rate_limited/...）或本地码
  /// （network_error/bad_response/identity_unavailable/identity_mismatch/
  /// invalid_code）。
  final String code;
  final String message;
  final int? httpStatus;

  @override
  String toString() => '[$code] $message';
}

/// owner-first 设备配对（2026-11-23 clean break；contracts/device §1/§4）：
///
/// 主人在 core 控制台「设备 → 配对新设备」生成 6 位数字码 → 手机在设置页输入
/// 该码 → `POST {base}/api/devices/pair {code, pubkey_hex, label}` → 立即激活
/// （应答含 device_id/name/scopes/ws_path）。旧「设备上屏出码等主人批准」
/// （device-first，/api/v1/devices/pair*）已被 core 删除，无替代。
///
/// 配对端点免 token（core 侧确认：设备尚无可出示凭据，码本身即授权）。
/// 配对成功后由调用方把 [PairingResult.wsUrl] 交 TransportClientV2.connect
/// （原生侧落库 gateway_url 并清撤销标记）。
class PairingService {
  PairingService({
    String? baseUrl,
    HttpClient? httpClient,
    Future<Map<String, dynamic>> Function()? identityProvider,
  })  : _baseUrlOverride = baseUrl,
        _http = httpClient ?? HttpClient(),
        _identityProvider =
            identityProvider ?? (() => TransportClientV2().getIdentity());

  final String? _baseUrlOverride;
  final HttpClient _http;

  /// 设备身份来源（device_id + Ed25519 公钥 hex）；测试注入假身份。
  final Future<Map<String, dynamic>> Function() _identityProvider;

  static const Duration timeout = Duration(seconds: 15);

  /// core 配对码形态：6 位数字（core registry `zfill(6)`）。
  static final RegExp codePattern = RegExp(r'^\d{6}$');

  /// 由 REST base 地址与 core 应答的 ws_path 合成 WS 全路径。
  ///
  /// - wsPath 已是 ws(s):// 全地址时原样采用（core 直给最终地址的情形）；
  /// - 否则按路径拼到 base 的 authority 上（http→ws、https→wss）。
  /// - base 非法/非 http(s)/ws(s) scheme 抛 [PairingException]（bad_base_url）。
  static String wsUrlFor(String baseUrl, String wsPath) {
    final path = wsPath.trim();
    if (path.startsWith('ws://') || path.startsWith('wss://')) return path;
    final base = Uri.parse(baseUrl.trim());
    final wsScheme = switch (base.scheme.toLowerCase()) {
      'http' => 'ws',
      'https' => 'wss',
      'ws' => 'ws',
      'wss' => 'wss',
      _ => throw PairingException(
          'bad_base_url', 'Core 地址 scheme 无法映射到 ws/wss: ${base.scheme}'),
    };
    if (base.host.isEmpty) {
      throw const PairingException('bad_base_url', 'Core 地址缺少主机名');
    }
    final port = base.hasPort ? ':${base.port}' : '';
    final p = path.startsWith('/') ? path : '/$path';
    return '$wsScheme://${base.host}$port$p';
  }

  /// 兑换配对码：校验 → 取公钥 → POST → 校验应答（含 device_id 与本地身份一致性）。
  Future<PairingResult> pair({required String code, String label = ''}) async {
    final trimmedCode = code.trim();
    if (!codePattern.hasMatch(trimmedCode)) {
      throw const PairingException('invalid_code', '配对码为主人控制台出示的 6 位数字');
    }
    final id = await _identity();
    final pubkey = id['public_key_hex'] as String? ?? '';
    final localDeviceId = id['device_id'] as String? ?? '';
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(pubkey)) {
      throw const PairingException(
          'identity_unavailable', '设备身份不可用（原生桥未就绪）');
    }
    final base =
        _baseUrlOverride ?? await DeviceConfig.baseUrl();
    final uri = Uri.parse('$base/api/devices/pair');
    final HttpClientRequest req;
    final HttpClientResponse res;
    try {
      req = await _http.postUrl(uri).timeout(timeout);
      req.headers
          .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      req.headers.set(HttpHeaders.acceptHeader, 'application/json');
      req.write(jsonEncode(<String, String>{
        'code': trimmedCode,
        'pubkey_hex': pubkey,
        'label': label.trim(),
      }));
      res = await req.close().timeout(timeout);
    } on Exception catch (e) {
      throw PairingException('network_error', '$e');
    }
    final text = await utf8.decodeStream(res).timeout(timeout);
    Map<String, dynamic> json;
    try {
      json = (jsonDecode(text) as Map).cast<String, dynamic>();
    } catch (_) {
      throw PairingException('bad_response', 'HTTP ${res.statusCode}: 非 JSON 响应',
          httpStatus: res.statusCode);
    }
    if (res.statusCode < 200 || res.statusCode >= 300 || json['ok'] != true) {
      final err = json['error'];
      throw PairingException(
        err is Map ? err['code'] as String? ?? 'http_${res.statusCode}' : 'http_${res.statusCode}',
        err is Map ? err['message'] as String? ?? '' : text,
        httpStatus: res.statusCode,
      );
    }
    final result = (json['result'] as Map?)?.cast<String, dynamic>();
    final wsPath = result?['ws_path'] as String?;
    final deviceId = result?['device_id'] as String?;
    if (result == null || wsPath == null || wsPath.isEmpty || deviceId == null) {
      throw const PairingException(
          'bad_response', '配对应答缺少 result.ws_path/device_id');
    }
    if (localDeviceId.isNotEmpty && deviceId != localDeviceId) {
      // core 由公钥推 device_id；不一致说明身份链断裂，绝不带错身份入库。
      throw const PairingException(
          'identity_mismatch', 'core 返回的 device_id 与本机身份不一致');
    }
    return PairingResult(
      deviceId: deviceId,
      name: result['name'] as String? ?? '',
      scopes:
          (result['scopes'] as List?)?.whereType<String>().toList() ?? const [],
      wsPath: wsPath,
      wsUrl: wsUrlFor(base, wsPath),
    );
  }

  Future<Map<String, dynamic>> _identity() async {
    try {
      return await _identityProvider();
    } on Exception catch (e) {
      throw PairingException('identity_unavailable', '$e');
    }
  }

  void close() => _http.close();
}
