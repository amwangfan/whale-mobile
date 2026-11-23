import 'dart:math';

import '../../../transport/transport_client_v2.dart';

/// REST 设备签名（contracts/device §11）：云端对设备通道面启用四头 fail-closed
/// 鉴权后，所有 REST 调用点在保留 Bearer 的同时叠加本类生成的四头。
///
/// 四头：
/// - X-Whale-Device-Id    设备身份（Ed25519 公钥 SHA-256 前 16 字节 hex）
/// - X-Whale-Timestamp    Unix 秒
/// - X-Whale-Nonce        随机串（契约界 16..128；Random.secure，每次不同）
/// - X-Whale-Signature    Ed25519 签名 128-hex（私钥不出 Keystore）
///
/// 签名载荷由原生 TransportBridgeV2.signRequestV2 组装（UTF-8）：
///   device_id\nts\nnonce\nMETHOD\npath
/// 其中 method 一律大写（本类负责），path 为不含 host 的线上路径、含 query
/// （调用方用 [pathWithQuery] 从最终请求 URI 取，保证与请求线逐字节一致）。
///
/// 凭证纪律：本类不打印签名值/token/device_id；签名失败向上抛（各服务包装成
/// 显式 sign_error），绝不静默降级为无签名请求。
class DeviceAuth {
  DeviceAuth({TransportClientV2? transport, Random? random})
      : _transport = transport ?? TransportClientV2(),
        _random = random ?? Random.secure();

  static const String headerDeviceId = 'X-Whale-Device-Id';
  static const String headerTimestamp = 'X-Whale-Timestamp';
  static const String headerNonce = 'X-Whale-Nonce';
  static const String headerSignature = 'X-Whale-Signature';

  /// 契约 §11 的 nonce 长度界。
  static const int nonceMinLength = 16;
  static const int nonceMaxLength = 128;

  /// 实现取值：32 字符（界内；字母表 URL/头安全）。
  static const int nonceLength = 32;
  static const String _nonceAlphabet =
      'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

  final TransportClientV2 _transport;
  final Random _random;

  /// 为 [method]（任意大小写，按大写签名）+ [path]（不含 host、含 query）
  /// 生成四头。签名失败（桥未就绪/身份缺失）原样上抛，由调用方包装显式可见。
  Future<Map<String, String>> headers(String method, String path) async {
    final timestamp =
        (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
    final nonce = newNonce();
    final signed = await _transport.signRequest(
      method: method.toUpperCase(),
      path: path,
      timestamp: timestamp,
      nonce: nonce,
    );
    return <String, String>{
      headerDeviceId: signed['device_id'] as String? ?? '',
      headerTimestamp: timestamp,
      headerNonce: nonce,
      headerSignature: signed['signature'] as String? ?? '',
    };
  }

  /// 单次 nonce（Random.secure）。调用方不得缓存复用——签名绑 nonce。
  String newNonce() {
    final buf = StringBuffer();
    for (var i = 0; i < nonceLength; i++) {
      buf.write(_nonceAlphabet[_random.nextInt(_nonceAlphabet.length)]);
    }
    return buf.toString();
  }

  /// 从最终请求 URI 取线上路径（剥 scheme://authority，保留 path?query 的
  /// 线上编码形态）——签名载荷的 path 段必须与 HTTP 请求线一致。
  static String pathWithQuery(Uri uri) {
    final wire = uri.toString();
    final origin = uri.origin;
    return wire.startsWith(origin) ? wire.substring(origin.length) : wire;
  }
}
