import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/services/device_auth.dart';
import 'package:whale_mobile/features/chat/services/link_ticket_service.dart';

/// LinkTicketService 单测（假 HTTP 端点 + MethodChannel mock 签名桥）。
///
/// 覆盖：§11 四头线上形状（签名载荷 method=POST、path=/api/devices/link_ticket）、
/// 应答解析、900s 缓存复用、过期重签、401 作废重取、身份不可用显式提示。
/// 凭证纪律：假签名/假 device_id，测试不打印票据值本体。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('whale/transport_v2');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  /// 上桥签名入参留痕（method/path/ts/nonce 形状；签名值本体不断言）。
  final signCalls = <MethodCall>[];
  var signFails = false;

  setUp(() {
    signCalls.clear();
    signFails = false;
    // TestWidgetsFlutterBinding 装了拦网 mock HttpOverrides（一律 400）；
    // 本套件用 dart:io 回环假端点，恢复真实 HTTP。
    HttpOverrides.global = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'signRequestV2') {
        if (signFails) throw PlatformException(code: 'no_identity');
        signCalls.add(call);
        return <String, dynamic>{
          'device_id': 'dev_fake_unit',
          'signature': 'ab' * 64,
        };
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    HttpOverrides.global = null;
  });

  /// 假票据端点：记录请求头，按脚本应答。
  Future<HttpServer> startFakeTicketEndpoint({
    required List<Map<String, String>> capturedHeaders,
    int expiresIn = 900,
    int? forceStatus,
    String? errorCode,
  }) async {
    final http = await HttpServer.bind('127.0.0.1', 0);
    http.listen((req) async {
      capturedHeaders.add(<String, String>{
        'X-Whale-Device-Id': req.headers.value('x-whale-device-id') ?? '',
        'X-Whale-Timestamp': req.headers.value('x-whale-timestamp') ?? '',
        'X-Whale-Nonce': req.headers.value('x-whale-nonce') ?? '',
        'X-Whale-Signature': req.headers.value('x-whale-signature') ?? '',
      });
      if (forceStatus != null) {
        req.response
          ..statusCode = forceStatus
          ..write(jsonEncode({
            'ok': false,
            'error': {'code': errorCode ?? 'unauthorized', 'message': 'denied'},
          }));
        await req.response.close();
        return;
      }
      req.response
        ..statusCode = 200
        ..headers.contentType = ContentType.json
        ..write(jsonEncode({
          'ok': true,
          'result': {
            'ticket': 'tk_${capturedHeaders.length}',
            'expires_in': expiresIn,
            'device_id': 'dev_fake_unit',
          },
        }));
      await req.response.close();
    });
    return http;
  }

  test('签名形状：method=POST、path 逐字节 /api/devices/link_ticket、四头上线路',
      () async {
    final headers = <Map<String, String>>[];
    final http = await startFakeTicketEndpoint(capturedHeaders: headers);
    final svc = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http.port}', auth: DeviceAuth());

    final ticket = await svc.getValid();
    expect(ticket.value, 'tk_1');
    expect(ticket.deviceId, 'dev_fake_unit');
    expect(ticket.expiresAtMs,
        greaterThan(DateTime.now().millisecondsSinceEpoch));

    // 上桥签名入参：POST + 逐字节路径（§11 载荷 device_id\nts\nnonce\nPOST\npath）
    expect(signCalls, hasLength(1));
    final args = (signCalls.single.arguments as Map).cast<String, dynamic>();
    expect(args['method'], 'POST');
    expect(args['path'], '/api/devices/link_ticket');
    expect(int.parse(args['timestamp'] as String), greaterThan(1000000000));
    expect((args['nonce'] as String).length,
        inInclusiveRange(DeviceAuth.nonceMinLength, DeviceAuth.nonceMaxLength));

    // 线上四头齐
    final wire = headers.single;
    expect(wire['X-Whale-Device-Id'], 'dev_fake_unit');
    expect(wire['X-Whale-Signature'], hasLength(128));
    expect(wire['X-Whale-Timestamp'], isNotEmpty);
    expect(wire['X-Whale-Nonce'], isNotEmpty);
    svc.close();
    await http.close(force: true);
  });

  test('900s 内复用缓存（不重复请求）；过期重签', () async {
    final headers = <Map<String, String>>[];
    final http = await startFakeTicketEndpoint(capturedHeaders: headers);
    final svc = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http.port}', auth: DeviceAuth());

    final t1 = await svc.getValid();
    final t2 = await svc.getValid();
    expect(identical(t1, t2), isTrue, reason: 'TTL 内复用同票');
    expect(headers, hasLength(1), reason: '复用期不再发 HTTP');

    svc.close();
    await http.close(force: true);

    // 过期即重签（expires_in=0 → 立即过期）
    final headers2 = <Map<String, String>>[];
    final http2 =
        await startFakeTicketEndpoint(capturedHeaders: headers2, expiresIn: 0);
    final svc2 = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http2.port}', auth: DeviceAuth());
    final a = await svc2.getValid();
    expect(a.value, 'tk_1');
    final b = await svc2.getValid();
    expect(b.value, 'tk_2', reason: '过期后重签拿新票');
    expect(headers2, hasLength(2));
    svc2.close();
    await http2.close(force: true);
  });

  test('401 → unauthorized + 作废缓存（下次重取仍失败但再发请求）', () async {
    final headers = <Map<String, String>>[];
    final http = await startFakeTicketEndpoint(
        capturedHeaders: headers, forceStatus: 401, errorCode: 'unauthorized');
    final svc = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http.port}', auth: DeviceAuth());
    await expectLater(
      svc.getValid(),
      throwsA(isA<LinkTicketException>()
          .having((e) => e.code, 'code', 'unauthorized')
          .having((e) => e.httpStatus, 'httpStatus', 401)),
    );
    await expectLater(svc.getValid(), throwsA(isA<LinkTicketException>()));
    expect(headers, hasLength(2), reason: '作废后重取要真发请求（不是缓存假票）');
    svc.close();
    await http.close(force: true);
  });

  test('身份不可用（签名桥失败）→ 显式 identity_unavailable，不降级为无签名请求',
      () async {
    signFails = true;
    final http = await HttpServer.bind('127.0.0.1', 0);
    http.listen((req) async => req.response.close());
    final svc = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http.port}', auth: DeviceAuth());
    await expectLater(
      svc.getValid(),
      throwsA(isA<LinkTicketException>()
          .having((e) => e.code, 'code', 'identity_unavailable')
          .having((e) => e.message, 'message', contains('设备身份不可用'))),
    );
    svc.close();
    await http.close(force: true);
  });

  test('currentTicketValue：有效票可读、过期返回 null（不触发重签）', () async {
    final headers = <Map<String, String>>[];
    final http = await startFakeTicketEndpoint(capturedHeaders: headers);
    final svc = LinkTicketService(
        baseUrl: 'http://127.0.0.1:${http.port}', auth: DeviceAuth());
    expect(svc.currentTicketValue, isNull);
    await svc.getValid();
    expect(svc.currentTicketValue, 'tk_1');
    svc.invalidate();
    expect(svc.currentTicketValue, isNull);
    svc.close();
    await http.close(force: true);
  });
}
