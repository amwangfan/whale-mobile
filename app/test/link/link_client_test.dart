import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/services/link_chat_api.dart';
import 'package:whale_mobile/transport/link/link_client.dart';
import 'package:whale_mobile/transport/link/ulid.dart';

import 'fake_link_server.dart';

void main() {
  group('ULID', () {
    test('26 字符 Crockford Base32；同毫秒字典序稳定', () {
      final a = newUlid(1700000000000);
      expect(a, matches(ulidPattern));
      expect(a.length, 26);
      final b = newUlid(1700000000001);
      expect(b.substring(0, 10).compareTo(a.substring(0, 10)),
          greaterThanOrEqualTo(0));
    });
  });

  group('topic glob 匹配', () {
    test('run.* 匹配 run.delta / run.step；精确匹配 notify', () {
      expect(linkTopicMatches('run.*', 'run.delta'), isTrue);
      expect(linkTopicMatches('run.*', 'run.step'), isTrue);
      expect(linkTopicMatches('run.*', 'approval.requested'), isFalse);
      expect(linkTopicMatches('notify', 'notify'), isTrue);
      expect(linkTopicMatches('approval.*', 'approval.requested'), isTrue);
      expect(linkTopicMatches('*', 'anything.at.all'), isTrue);
    });
  });

  group('LinkClient 握手', () {
    late FakeLinkServer server;
    setUp(() async {
      server = FakeLinkServer();
      await server.start();
    });
    tearDown(() async => server.stop());

    test('hello → welcome（client_id/methods/server）', () async {
      final client = LinkClient(uri: server.uri, token: 'test-token');
      final welcomes = <LinkWelcome>[];
      final sub = client.welcomes.listen(welcomes.add);
      client.connect();
      await _until(() => client.isConnected);
      expect(welcomes, hasLength(1));
      expect(welcomes.single.clientId, startsWith('cli_fake_'));
      expect(welcomes.single.methods, contains('chat.send'));
      expect(welcomes.single.isReconnect, isFalse);
      // hello 帧带 token+role+subscribe（凭证不进日志，仅服务端侧校验）
      final hello = server.frames.firstWhere((f) => f['type'] == 'hello');
      final payload = (hello['payload'] as Map).cast<String, dynamic>();
      expect(payload['role'], 'mobile');
      expect(payload['subscribe'],
          containsAll(['run.*', 'approval.*', 'notify', 'task.*']));
      await sub.cancel();
      await client.dispose();
    });

    test('token 错 → unauthorized + 4401 终态不重连', () async {
      final client = LinkClient(
        uri: server.uri,
        token: 'wrong-token',
        backoffSteps: const [Duration(milliseconds: 20)],
      );
      final errors = <LinkError>[];
      final sub = client.protocolErrors.listen(errors.add);
      client.connect();
      await _until(() => errors.isNotEmpty);
      expect(errors.first.code, LinkErrorCode.unauthorized);
      await Future<void>.delayed(const Duration(milliseconds: 120));
      // 终态：不自动重连（hello 只发过一次）
      expect(
          server.frames.where((f) => f['type'] == 'hello').length, 1);
      expect(client.state, LinkState.disconnected);
      await sub.cancel();
      await client.dispose();
    });

    test('ticket 走 hello.ticket 而非 token（C 接口）', () async {
      final client =
          LinkClient(uri: server.uri, ticket: 'ticket-abc', token: '');
      final sub = client.protocolErrors.listen((_) {});
      client.connect();
      await _until(() => server.frames.any((f) => f['type'] == 'hello'));
      final hello = server.frames.firstWhere((f) => f['type'] == 'hello');
      final payload = (hello['payload'] as Map).cast<String, dynamic>();
      expect(payload['ticket'], 'ticket-abc');
      expect(payload.containsKey('token'), isFalse);
      await sub.cancel();
      await client.dispose();
    });

    test('C 路握手：有效 ticket + role=mobile → welcome', () async {
      final ticketServer = FakeLinkServer(expectedTicket: 'tk_good');
      await ticketServer.start();
      final client = LinkClient(uri: ticketServer.uri, ticket: 'tk_good');
      client.connect();
      await _until(() => client.isConnected);
      expect(client.welcome?.clientId, startsWith('cli_fake_'));
      // hello 帧：有 ticket、无 token、role=mobile
      final hello =
          ticketServer.frames.firstWhere((f) => f['type'] == 'hello');
      final payload = (hello['payload'] as Map).cast<String, dynamic>();
      expect(payload['ticket'], 'tk_good');
      expect(payload.containsKey('token'), isFalse);
      expect(payload['role'], 'mobile');
      await client.dispose();
      await ticketServer.stop();
    });

    test('C 路握手：错票 → unauthorized + 4401 终态不重连', () async {
      final ticketServer = FakeLinkServer(expectedTicket: 'tk_good');
      await ticketServer.start();
      final client = LinkClient(
        uri: ticketServer.uri,
        ticket: 'tk_bad',
        backoffSteps: const [Duration(milliseconds: 20)],
      );
      final errors = <LinkError>[];
      final sub = client.protocolErrors.listen(errors.add);
      client.connect();
      await _until(() => errors.isNotEmpty);
      expect(errors.first.code, LinkErrorCode.unauthorized);
      await Future<void>.delayed(const Duration(milliseconds: 120));
      expect(ticketServer.frames.where((f) => f['type'] == 'hello').length, 1);
      expect(client.state, LinkState.disconnected);
      await sub.cancel();
      await client.dispose();
      await ticketServer.stop();
    });

    test('C 路握手：票客户端 role!=mobile → invalid_params 4400', () async {
      final ticketServer = FakeLinkServer(expectedTicket: 'tk_good');
      await ticketServer.start();
      final client =
          LinkClient(uri: ticketServer.uri, ticket: 'tk_good', role: 'console');
      final errors = <LinkError>[];
      final sub = client.protocolErrors.listen(errors.add);
      client.connect();
      await _until(() => errors.isNotEmpty);
      expect(errors.first.code, LinkErrorCode.invalidParams);
      await sub.cancel();
      await client.dispose();
      await ticketServer.stop();
    });
  });

  group('LinkClient RPC', () {
    late FakeLinkServer server;
    late LinkClient client;
    setUp(() async {
      server = FakeLinkServer();
      await server.start();
      client = LinkClient(uri: server.uri, token: 'test-token');
      client.connect();
      await _until(() => client.isConnected);
    });
    tearDown(() async {
      await client.dispose();
      await server.stop();
    });

    test('rpc.request → rpc.response 按 corr 配对（并发两请求不乱）', () async {
      final a = client.request('conversations.list', {'limit': 5});
      final b = client.request('system.status');
      final ra = await a;
      final rb = await b;
      expect(ra.containsKey('conversations'), isTrue);
      expect(rb['version'], '1.0.0-fake');
    });

    test('错误码映射：forbidden + 非 retryable', () async {
      // 越权自检：role=mobile 调 config.get 必须 forbidden
      expect(
        () => client.request('config.get'),
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.forbidden)
            .having((e) => e.retryable, 'retryable', isFalse)),
      );
    });

    test('自超时：不应答的 method → timeout（结果未知，绝不重放）', () async {
      final c = LinkClient(
        uri: server.uri,
        token: 'test-token',
        rpcTimeout: const Duration(milliseconds: 150),
      );
      c.connect();
      await _until(() => c.isConnected);
      server.neverAnswer.add('system.status');
      final before =
          server.frames.where((f) => f['type'] == 'rpc.request').length;
      await expectLater(
        c.request('system.status'),
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.timeout)),
      );
      await Future<void>.delayed(const Duration(milliseconds: 300));
      final after =
          server.frames.where((f) => f['type'] == 'rpc.request').length;
      expect(after, before + 1, reason: 'timeout 绝不自动重放');
      await c.dispose();
    });

    test('未连接时 request → unavailable/retryable（未发送，安全）', () async {
      final c = LinkClient(uri: server.uri, token: 'test-token');
      await expectLater(
        c.request('system.status'),
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.unavailable)
            .having((e) => e.retryable, 'retryable', isTrue)
            .having((e) => e.origin, 'origin', 'local')),
      );
      await c.dispose();
    });

    test('帧上限：>1MiB 本地拒发 invalid_params，服务端零接收', () async {
      final big = 'x' * (1024 * 1024);
      final before =
          server.frames.where((f) => f['type'] == 'rpc.request').length;
      await expectLater(
        client.request('chat.send', {'text': big}),
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.invalidParams)
            .having((e) => e.origin, 'origin', 'local')),
      );
      await Future<void>.delayed(const Duration(milliseconds: 50));
      final after =
          server.frames.where((f) => f['type'] == 'rpc.request').length;
      expect(after, before);
    });

    test('ping 保活：30s 间隔帧上行（注入 60ms 验证）', () async {
      final c = LinkClient(
        uri: server.uri,
        token: 'test-token',
        pingInterval: const Duration(milliseconds: 50),
      );
      c.connect();
      await _until(() => c.isConnected);
      await Future<void>.delayed(const Duration(milliseconds: 180));
      expect(
          server.frames.where((f) => f['type'] == 'ping').length,
          greaterThanOrEqualTo(2));
      await c.dispose();
    });

    test('事件分发：pushEvent → events 流按 topic 过滤', () async {
      final events = <LinkEvent>[];
      final sub = client.eventsMatching('run.*').listen(events.add);
      server.pushEvent('run.delta', {'run_id': 'r1', 'delta': 'x'});
      server.pushEvent('approval.requested', {'id': 'a1'});
      await _until(() => events.isNotEmpty);
      expect(events.single.topic, 'run.delta');
      expect(events.single.eventId, isNotNull);
      await sub.cancel();
    });
  });

  group('LinkClient 断线重连', () {
    late FakeLinkServer server;
    setUp(() async {
      server = FakeLinkServer();
      await server.start();
    });
    tearDown(() async => server.stop());

    test('掉线 → degraded → 退避重连 → welcome(isReconnect=true)；在飞请求'
        '以 unavailable(本地,结果未知) 终结不重放', () async {
      final client = LinkClient(
        uri: server.uri,
        token: 'test-token',
        backoffSteps: const [Duration(milliseconds: 30)],
        pingInterval: const Duration(seconds: 3600),
      );
      final welcomes = <LinkWelcome>[];
      final states = <LinkState>[];
      final wsub = client.welcomes.listen(welcomes.add);
      final ssub = client.states.listen(states.add);
      client.connect();
      await _until(() => client.isConnected);

      // 在飞请求（服务端不应答）期间掉线；先挂上错误断言再断线
      // （错误晚到 20ms：必须先附着监听器，否则 zone 报未捕获异步错误）。
      server.neverAnswer.add('system.status');
      final inflight = client.request('system.status');
      final expectation = expectLater(
        inflight,
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.unavailable)
            .having((e) => e.retryable, 'retryable', isFalse)),
      );
      await Future<void>.delayed(const Duration(milliseconds: 20));
      await server.dropAll();
      await expectation;
      await _until(() => client.isConnected, timeoutMs: 3000);
      expect(states, contains(LinkState.degraded));
      expect(welcomes.length, 2);
      expect(welcomes.last.isReconnect, isTrue);
      await wsub.cancel();
      await ssub.cancel();
      await client.dispose();
    });
  });

  group('LinkChatApi 读路径重试策略', () {
    late FakeLinkServer server;
    late LinkClient client;
    setUp(() async {
      server = FakeLinkServer();
      await server.start();
      client = LinkClient(uri: server.uri, token: 'test-token');
      client.connect();
      await _until(() => client.isConnected);
    });
    tearDown(() async {
      await client.dispose();
      await server.stop();
    });

    test('retryable:true 的读请求自动重试一次后成功', () async {
      // 裸 request 不重试（写路径纪律的底座）
      server.forcedError['conversations.list'] = {
        'code': 'unavailable',
        'message': '依赖不可用',
        'retryable': true,
      };
      await expectLater(
        client.request('conversations.list'),
        throwsA(isA<LinkError>()
            .having((e) => e.code, 'code', LinkErrorCode.unavailable)
            .having((e) => e.retryable, 'retryable', isTrue)),
      );
      // api._read 路径：retryable:true 自动重试一次；300ms 后解除 → 成功
      server.forcedError['conversations.list'] = {
        'code': 'unavailable',
        'message': '依赖不可用',
        'retryable': true,
      };
      Timer(const Duration(milliseconds: 300), () {
        server.forcedError.remove('conversations.list');
      });
      final list = await LinkChatApi(client).listConversations();
      expect(list, isA<List<dynamic>>());
    });

    test('非 retryable 错误不重试（forbidden 直接上抛）', () async {
      server.forcedError['conversations.list'] = {
        'code': 'forbidden',
        'message': 'no',
      };
      int count() => server.frames
          .where((f) =>
              f['type'] == 'rpc.request' &&
              ((f['payload'] as Map)['method']) == 'conversations.list')
          .length;
      final before = count();
      await expectLater(
          LinkChatApi(client).listConversations(), throwsA(isA<LinkError>()));
      await Future<void>.delayed(const Duration(milliseconds: 700));
      expect(count(), before + 1, reason: '非 retryable 不重试');
    });
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
