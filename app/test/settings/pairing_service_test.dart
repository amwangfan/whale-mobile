import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/settings/services/pairing_service.dart';

/// owner-first 配对（清单 §5.1）假 core 回环测试：
/// dart:io HttpServer 扮演 core，断言请求线（POST /api/devices/pair +
/// {code, pubkey_hex, label}），并按 core 契约应答
/// {ok:true, result:{device_id, name, scopes, ws_path}}。
void main() {
  const fakeDeviceId = 'ba4bb14c75917cfc0e69fb7a1caf8ba3';
  const fakePubkey =
      '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

  Map<String, dynamic> fakeIdentity() => {
        'device_id': fakeDeviceId,
        'public_key_hex': fakePubkey,
      };

  group('PairingService.wsUrlFor（ws_path → WS 全路径）', () {
    test('http/https 基地址映射为 ws/wss，端口保留', () {
      // 文档保留段示例地址（真实尾网坐标不入仓，见内部账本）
      expect(PairingService.wsUrlFor('http://203.0.113.10:8193', '/api/devices/ws'),
          'ws://203.0.113.10:8193/api/devices/ws');
      expect(PairingService.wsUrlFor('https://rayneo.wangfanlab.com:8443', '/api/devices/ws'),
          'wss://rayneo.wangfanlab.com:8443/api/devices/ws');
    });

    test('ws_path 为绝对 ws(s) 地址时原样采用；缺前导斜杠补全', () {
      expect(PairingService.wsUrlFor('http://h:1', 'wss://other:8443/api/devices/ws'),
          'wss://other:8443/api/devices/ws');
      expect(PairingService.wsUrlFor('http://h:8193', 'api/devices/ws'),
          'ws://h:8193/api/devices/ws');
    });

    test('非法 scheme / 缺主机名显式失败', () {
      expect(() => PairingService.wsUrlFor('ftp://h', '/api/devices/ws'),
          throwsA(isA<PairingException>()));
      expect(() => PairingService.wsUrlFor('http://', '/x'),
          throwsA(anything));
    });
  });

  group('PairingService.pair（假 core）', () {
    HttpServer? server;
    Map<String, dynamic>? lastBody;
    String? lastPath;
    String? lastMethod;

    Future<String> startServer(int status, Object payload) async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server!.listen((req) async {
        lastMethod = req.method;
        lastPath = req.uri.path;
        lastBody =
            jsonDecode(await utf8.decodeStream(req)) as Map<String, dynamic>;
        req.response.statusCode = status;
        req.response.headers.contentType = ContentType.json;
        req.response.write(jsonEncode(payload));
        await req.response.close();
      });
      return 'http://${server!.address.host}:${server!.port}';
    }

    tearDown(() async {
      await server?.close(force: true);
      server = null;
      lastBody = null;
      lastPath = null;
      lastMethod = null;
    });

    test('6 位码 → POST /api/devices/pair 200，应答 ws_path 落进结果', () async {
      final base = await startServer(200, {
        'ok': true,
        'result': {
          'device_id': fakeDeviceId,
          'name': 'magic8',
          'scopes': ['device.tap', 'device.status'],
          'ws_path': '/api/devices/ws',
        },
      });
      final svc = PairingService(
        baseUrl: base,
        identityProvider: () async => fakeIdentity(),
      );
      final r = await svc.pair(code: '482910', label: 'magic8-pro');
      // 请求线证据：方法/路径/体三段与 core _http_redeem 契约逐字对齐
      expect(lastMethod, 'POST');
      expect(lastPath, '/api/devices/pair');
      expect(lastBody, {
        'code': '482910',
        'pubkey_hex': fakePubkey,
        'label': 'magic8-pro',
      });
      // 应答字段：ws_path 是 WS 路径事实源
      expect(r.deviceId, fakeDeviceId);
      expect(r.name, 'magic8');
      expect(r.scopes, ['device.tap', 'device.status']);
      expect(r.wsPath, '/api/devices/ws');
      expect(r.wsUrl, '$base/api/devices/ws'.replaceFirst('http://', 'ws://'));
      svc.close();
    });

    test('错误应答按 core 错误码上抛（404 not_found / 409 conflict）', () async {
      var base = await startServer(404, {
        'ok': false,
        'error': {
          'code': 'not_found',
          'message': 'pairing code invalid or already used',
          'retryable': false,
        },
      });
      var svc = PairingService(
          baseUrl: base, identityProvider: () async => fakeIdentity());
      try {
        await svc.pair(code: '000000');
        fail('应抛 PairingException');
      } on PairingException catch (e) {
        expect(e.code, 'not_found');
        expect(e.httpStatus, 404);
      }
      svc.close();
      await server?.close(force: true);

      final base2 = await startServer(409, {
        'ok': false,
        'error': {'code': 'conflict', 'message': 'pairing code expired'},
      });
      svc = PairingService(
          baseUrl: base2, identityProvider: () async => fakeIdentity());
      try {
        await svc.pair(code: '000000');
        fail('应抛 PairingException');
      } on PairingException catch (e) {
        expect(e.code, 'conflict');
        expect(e.httpStatus, 409);
      }
      svc.close();
    });

    test('非 6 位数字码本地拒绝，不发请求', () async {
      final base = await startServer(200, {'ok': true, 'result': {}});
      final svc = PairingService(
          baseUrl: base, identityProvider: () async => fakeIdentity());
      for (final bad in ['12345', '1234567', 'abcdef', '']) {
        try {
          await svc.pair(code: bad);
          fail('码 $bad 应被拒');
        } on PairingException catch (e) {
          expect(e.code, 'invalid_code');
        }
      }
      expect(lastBody, isNull, reason: '本地校验失败不得发请求');
      svc.close();
    });

    test('core 应答 device_id 与本机身份不一致 → identity_mismatch', () async {
      final base = await startServer(200, {
        'ok': true,
        'result': {
          'device_id': 'ffffffffffffffffffffffffffffffff',
          'name': 'x',
          'scopes': <String>[],
          'ws_path': '/api/devices/ws',
        },
      });
      final svc = PairingService(
          baseUrl: base, identityProvider: () async => fakeIdentity());
      try {
        await svc.pair(code: '482910');
        fail('应抛 identity_mismatch');
      } on PairingException catch (e) {
        expect(e.code, 'identity_mismatch');
      }
      svc.close();
    });

    test('应答缺 ws_path → bad_response（不落半成品状态）', () async {
      final base = await startServer(200, {
        'ok': true,
        'result': {'device_id': fakeDeviceId, 'name': 'x', 'scopes': []},
      });
      final svc = PairingService(
          baseUrl: base, identityProvider: () async => fakeIdentity());
      try {
        await svc.pair(code: '482910');
        fail('应抛 bad_response');
      } on PairingException catch (e) {
        expect(e.code, 'bad_response');
      }
      svc.close();
    });
  });
}
