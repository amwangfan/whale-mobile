import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:whale_mobile/features/chat/services/link_session.dart';
import 'package:whale_mobile/features/chat/services/link_ticket_service.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

import '../link/fake_link_server.dart';

/// LinkSession C 路集成（假 Link server + 真 LinkClient，dart:io 回环）。
void main() {
  late FakeLinkServer server;

  setUp(() async {
    server = FakeLinkServer(expectedTicket: 'tk_good');
    await server.start();
    // DeviceConfig.baseUrl → 假 server（LinkClient.linkUriFor 派生 /api/link）
    final port = server.uri.port;
    SharedPreferences.setMockInitialValues(<String, Object>{
      'whale_core_base_url': 'http://127.0.0.1:$port',
    });
  });

  tearDown(() async => server.stop());

  test('建连前先取票：config.ticket 来自 provider；hello→welcome→聊天面就绪',
      () async {
    final captured = <LinkSessionConfig>[];
    var ticketCalls = 0;
    final session = LinkSession(
      ticketProvider: () async {
        ticketCalls++;
        return 'tk_good';
      },
      clientFactory: (config) async {
        captured.add(config);
        return LinkClient(
          uri: config.uri,
          ticket: config.ticket,
          role: 'mobile',
          backoffSteps: const [Duration(milliseconds: 30)],
          pingInterval: const Duration(seconds: 3600),
        );
      },
    );
    await session.acquire();
    await _until(() => session.isConnected);
    expect(ticketCalls, 1);
    expect(captured.single.ticket, 'tk_good');
    expect(captured.single.uri.path, '/api/link');
    expect(session.api, isNotNull);
    expect(session.approvals, isNotNull);
    // hello 确实带 ticket 上线路
    final hello = server.frames.firstWhere((f) => f['type'] == 'hello');
    expect((hello['payload'] as Map)['ticket'], 'tk_good');
    session.dispose();
  });

  test('票据获取失败 → 明确提示（identity_unavailable 透传），api 不就绪',
      () async {
    final session = LinkSession(
      ticketProvider: () async => throw const LinkTicketException(
          'identity_unavailable', '设备身份不可用（设备线未就绪/未配对）'),
    );
    await session.acquire();
    expect(session.error, contains('Link 票据获取失败'));
    expect(session.error, contains('设备身份不可用'));
    expect(session.api, isNull);
    expect(session.isConnected, isFalse);
    session.dispose();
  });

  test('4401（票失效）→ 作废重签 + 重连恢复（有界重试）', () async {
    var ticketCalls = 0;
    final session = LinkSession(
      // 第一张票坏、第二张好（模拟 401 后重签拿到新票）
      ticketProvider: () async => (++ticketCalls == 1) ? 'tk_bad' : 'tk_good',
      clientFactory: (config) async => LinkClient(
        uri: config.uri,
        ticket: config.ticket,
        role: 'mobile',
        backoffSteps: const [Duration(milliseconds: 30)],
        pingInterval: const Duration(seconds: 3600),
      ),
    );
    await session.acquire();
    // 第一次：bad 票 → 4401 终态 → session 作废重签 → 第二次：good 票 → welcome
    await _until(() => session.isConnected, timeoutMs: 5000);
    expect(ticketCalls, greaterThanOrEqualTo(2));
    expect(session.error, isNull);
    final hellos =
        server.frames.where((f) => f['type'] == 'hello').toList();
    expect((hellos.first['payload'] as Map)['ticket'], 'tk_bad');
    expect((hellos.last['payload'] as Map)['ticket'], 'tk_good');
    session.dispose();
  });

  test('连续鉴权失败超限 → 终态提示（不无限重签）', () async {
    var ticketCalls = 0;
    final session = LinkSession(
      ticketProvider: () async {
        ticketCalls++;
        return 'tk_bad';
      },
      clientFactory: (config) async => LinkClient(
        uri: config.uri,
        ticket: config.ticket,
        role: 'mobile',
        backoffSteps: const [Duration(milliseconds: 20)],
        pingInterval: const Duration(seconds: 3600),
      ),
    );
    await session.acquire();
    await _until(() => session.error != null, timeoutMs: 5000);
    expect(session.error, contains('连续失败'));
    expect(session.error, contains('设备可能已被撤销'));
    final failures = ticketCalls;
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(ticketCalls, failures, reason: '超限后不再重签');
    session.dispose();
  });
}

Future<void> _until(bool Function() cond, {int timeoutMs = 3000}) async {
  final deadline = DateTime.now().millisecondsSinceEpoch + timeoutMs;
  while (!cond()) {
    if (DateTime.now().millisecondsSinceEpoch > deadline) {
      fail('condition not met within ${timeoutMs}ms');
    }
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
