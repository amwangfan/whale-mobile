import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/services/device_auth.dart';

/// DeviceAuth 单测（离线；MethodChannel 用 mock handler，凭证零日志）。
///
/// 覆盖：四头完整性、method 大写化、path 含 query 原样上桥、timestamp
/// 为 Unix 秒且在调用窗口内、nonce 长度∈[16,128] 且每次不同。
/// 载荷格式（device_id\nts\nnonce\nMETHOD\npath）的逐字节正确性在原生侧
/// TransportBridgeV2Test.signRequestV2 用同种子身份独立复签断言。
void main() {
  // 本套件不发真实 HTTP，Widgets 绑定可安全使用（其 HttpClient 拦截无影响）。
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('whale/transport_v2');

  /// 桥调用留痕（只记入参形状，测试不断言、不打印签名值本体之外的敏感面；
  /// mock 返回的是假 device_id/假签名）。
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'signRequestV2') {
        return <String, dynamic>{
          'device_id': 'dev_unit_test',
          'signature': 'ab' * 64, // 128-hex 形状
        };
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('四头完整：device_id/signature 取自桥回包，ts/nonce 与上桥入参一致', () async {
    final h = await DeviceAuth().headers('GET', '/api/v2/conversations?limit=20');
    expect(
        h.keys,
        containsAll(<String>[
          'X-Whale-Device-Id',
          'X-Whale-Timestamp',
          'X-Whale-Nonce',
          'X-Whale-Signature',
        ]));
    expect(h.length, 4);
    expect(h['X-Whale-Device-Id'], 'dev_unit_test');
    expect(h['X-Whale-Signature'], hasLength(128));

    final call = calls.single;
    expect(call.method, 'signRequestV2');
    final args = (call.arguments as Map).cast<String, dynamic>();
    expect(args['timestamp'], h['X-Whale-Timestamp']);
    expect(args['nonce'], h['X-Whale-Nonce']);
    expect(args['path'], '/api/v2/conversations?limit=20');
  });

  test('method 任意大小写输入均按大写上桥（载荷 METHOD 段）', () async {
    final auth = DeviceAuth();
    await auth.headers('get', '/api/v2/a');
    await auth.headers('Post', '/api/v2/b');
    expect(calls[0].arguments['method'], 'GET');
    expect(calls[1].arguments['method'], 'POST');
  });

  test('timestamp 为 Unix 秒且落在调用前后窗口内', () async {
    final before = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final h = await DeviceAuth().headers('GET', '/api/v2/x');
    final after = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final ts = int.parse(h['X-Whale-Timestamp']!);
    expect(ts, inInclusiveRange(before, after));
    // 秒级（非毫秒）：当前年代量级 1.7e9，十位以内
    expect(ts, greaterThan(1000000000));
    expect(ts, lessThan(10000000000));
  });

  test('nonce 长度 ∈ [16,128]（实现取 32）且字符集头安全', () {
    final auth = DeviceAuth();
    for (var i = 0; i < 64; i++) {
      final n = auth.newNonce();
      expect(n.length,
          inInclusiveRange(DeviceAuth.nonceMinLength, DeviceAuth.nonceMaxLength));
      expect(n.length, DeviceAuth.nonceLength);
      expect(RegExp(r'^[A-Za-z0-9]+$').hasMatch(n), isTrue);
    }
  });

  test('nonce 每次不同（256 次生成唯一 + 两次 headers 调用不复用）', () async {
    final auth = DeviceAuth();
    final seen = <String>{};
    for (var i = 0; i < 256; i++) {
      expect(seen.add(auth.newNonce()), isTrue, reason: 'nonce 重复');
    }
    final h1 = await auth.headers('GET', '/api/v2/a');
    final h2 = await auth.headers('GET', '/api/v2/a');
    expect(h1['X-Whale-Nonce'], isNot(h2['X-Whale-Nonce']));
    expect(calls[0].arguments['nonce'], h1['X-Whale-Nonce']);
    expect(calls[1].arguments['nonce'], h2['X-Whale-Nonce']);
  });

  test('pathWithQuery：剥 host、保留 query 线上编码、无 query 纯路径', () {
    expect(
        DeviceAuth.pathWithQuery(Uri.parse(
            'http://203.0.113.10:8193/api/v2/conversations?limit=20&cursor=ab%2Fcd')),
        '/api/v2/conversations?limit=20&cursor=ab%2Fcd');
    expect(
        DeviceAuth.pathWithQuery(Uri.parse(
            'https://rayneo.wangfanlab.com:8443/api/v2/chat/stream')),
        '/api/v2/chat/stream');
  });
}
