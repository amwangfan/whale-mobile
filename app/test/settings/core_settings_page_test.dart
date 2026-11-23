import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:whale_mobile/features/settings/pages/core_settings_page.dart';
import 'package:whale_mobile/features/settings/services/pairing_service.dart';
import 'package:whale_mobile/transport/transport_client_v2.dart';

/// 配对面 widget 冒烟（清单 §5.1 的 UI 半）：6 位码输入 → 配对服务 →
/// 成功后以应答 ws_path 派生的 WS 全路径发起 connect。
class _FakePairingService extends PairingService {
  String? lastCode;
  String? lastLabel;

  @override
  Future<PairingResult> pair({required String code, String label = ''}) async {
    lastCode = code;
    lastLabel = label;
    return const PairingResult(
      deviceId: 'ba4bb14c75917cfc0e69fb7a1caf8ba3',
      name: 'magic8',
      scopes: ['device.tap'],
      wsPath: '/api/devices/ws',
      wsUrl: 'wss://rayneo.wangfanlab.com:8443/api/devices/ws',
    );
  }
}

class _FakeTransport extends TransportClientV2 {
  String? lastConnectedUrl;

  @override
  Future<Map<String, dynamic>> connect(String url) async {
    lastConnectedUrl = url;
    return const {};
  }
}

void main() {
  testWidgets('设置页输入 6 位码 → 配对成功 → 以 ws_path 连接', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final pairing = _FakePairingService();
    final transport = _FakeTransport();
    await tester.pumpWidget(MaterialApp(
      home: CoreSettingsPage(pairingService: pairing, transport: transport),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, '配对码（6 位数字）'), '482910');
    await tester.enterText(
        find.widgetWithText(TextField, '设备备注（可选）'), 'magic8-pro');
    await tester.tap(find.text('配对并连接'));
    await tester.pumpAndSettle();

    expect(pairing.lastCode, '482910');
    expect(pairing.lastLabel, 'magic8-pro');
    // 配对成功面板展示 ws_path；连接用的是应答派生的全路径
    expect(find.textContaining('配对成功并已发起连接'), findsOneWidget);
    expect(find.textContaining('/api/devices/ws'), findsWidgets);
    expect(transport.lastConnectedUrl,
        'wss://rayneo.wangfanlab.com:8443/api/devices/ws');
  });
}
