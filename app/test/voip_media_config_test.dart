import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:whale_mobile/transport/transport_client.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('whale/transport');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  late TransportClient client;

  setUp(() {
    calls.clear();
    client = TransportClient();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'getVoipBridgeConfig') {
        return {'host': '127.0.0.1', 'port': 18799, 'tokenConfigured': true};
      }
      return null;
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('media config reads only token presence, not credential', () async {
    final config = await client.getVoipBridgeConfig();
    expect(config['tokenConfigured'], isTrue);
    expect(config.containsKey('token'), isFalse);
  });

  test('endpoint-only edits preserve media token by omitting field', () async {
    await client.setVoipBridgeConfig('127.0.0.1', 18888);
    expect(calls.single.arguments, {'host': '127.0.0.1', 'port': 18888});
  });

  test(
    'media token update and explicit clear are distinct from omission',
    () async {
      await client.setVoipBridgeConfig(
        '127.0.0.1',
        18799,
        token: 'MEDIA_TEST_ONLY',
      );
      expect((calls.last.arguments as Map)['token'], 'MEDIA_TEST_ONLY');
      await client.setVoipBridgeConfig('127.0.0.1', 18799, token: '');
      expect((calls.last.arguments as Map).containsKey('token'), isTrue);
      expect((calls.last.arguments as Map)['token'], '');
    },
  );
}
