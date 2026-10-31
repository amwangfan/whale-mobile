import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:whale_mobile/modules/screenmemo/event_governor.dart';
import 'package:whale_mobile/modules/screenmemo/event_poller.dart';
import 'package:whale_mobile/modules/screenmemo/mcp_client.dart';
import 'package:whale_mobile/transport/transport_client.dart';

import 'fake_mcp_server.dart';

/// 一次 sendSensorEvent 调用的记录。
class SentEvent {
  SentEvent({
    required this.kind,
    required this.summary,
    required this.privacyClass,
    this.occurredAt,
    this.evidenceRef,
    this.eventId,
  });

  final String kind;
  final String summary;
  final int? occurredAt;
  final String privacyClass;
  final String? evidenceRef;
  final String? eventId;
}

/// 可变的轮询前置条件（测试逐位翻转）。
class Conditions {
  bool paused = false;
  bool token = true;
  bool module = true;
}

/// 治理全放的 governor（poller 单测聚焦游标/幂等，治理由 event_governor_test 覆盖）。
EventGovernor permissiveGovernor() => EventGovernor(
      perAppPerHour: 1 << 30,
      totalPerHour: 1 << 30,
      mergeWindowMs: 0,
      simhashThreshold: -1,
      staleMs: 1 << 60,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // 测试 binding 会把 HttpClient 换成全局 400 的假实现；恢复真实网络栈以打 fake server。
  HttpOverrides.global = null;

  group('ScreenMemoEventPoller', () {
    late FakeMcpServer server;
    late ScreenMemoMcpClient client;
    late StreamController<TransportState> states;
    late Conditions cond;
    late List<SentEvent> sent;
    late List<String> logs;
    late List<Map<String, dynamic>> events;
    late int failOnId; // 入队时对该 id 抛异常（0 = 不失败）
    late ScreenMemoEventPoller poller;

    Map<String, dynamic> pollHandler(Map<String, dynamic> args) {
      final since = (args['since_id'] as num?)?.toInt() ?? 0;
      final limit = (args['limit'] as num?)?.toInt() ?? 50;
      final page = events
          .where((e) => (e['id'] as int) > since)
          .take(limit)
          .toList();
      return toolResult(<String, dynamic>{
        'events': page,
        'since_id': since,
        'limit': limit,
        'max_id': page.isEmpty ? since : page.last['id'],
        'count': page.length,
      });
    }

    Map<String, dynamic> event(
      int id, {
      String kind = 'screen.ocr_ready',
      int occurredAt = 1729999998500,
      String privacyClass = 'owner_private',
      String summary = 'OCR 完成：设置页',
      String? evidenceRef,
    }) =>
        <String, dynamic>{
          'id': id,
          'kind': kind,
          'occurred_at': occurredAt,
          'privacy_class': privacyClass,
          'summary': summary,
          'evidence_ref': evidenceRef,
          // 服务端已保证不带路径；此处模拟脏数据验证端侧剥除不炸。
          'file_path': '/data/local/tmp/x.png',
        };

    setUp(() async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      server = FakeMcpServer();
      await server.start();
      client = ScreenMemoMcpClient(
        port: server.port,
        tokenProvider: () async => server.token,
      );
      states = StreamController<TransportState>.broadcast();
      cond = Conditions();
      sent = <SentEvent>[];
      logs = <String>[];
      events = <Map<String, dynamic>>[];
      failOnId = 0;
      server.handlers['poll_events'] = pollHandler;
      // 默认未配 token：start 的立即 kick 会静默跳过，之后测试翻开关手动驱动。
      cond.token = false;
      poller = ScreenMemoEventPoller(
        governor: permissiveGovernor(),
        client: client,
        sendEvent: ({
          required kind,
          required summary,
          occurredAt,
          privacyClass = 'owner_private',
          evidenceRef,
          eventId,
        }) async {
          final id = int.parse((eventId ?? 'sm-0000000000000').substring(3));
          if (id == failOnId) {
            throw StateError('enqueue failed for $eventId');
          }
          sent.add(SentEvent(
            kind: kind,
            summary: summary,
            occurredAt: occurredAt,
            privacyClass: privacyClass,
            evidenceRef: evidenceRef,
            eventId: eventId,
          ));
          return eventId ?? '';
        },
        transportStates: states.stream,
        isAllPaused: () async => cond.paused,
        hasToken: () async => cond.token,
        moduleEnabled: () => cond.module,
        initialTransportState: TransportState.online,
        interval: const Duration(hours: 1), // 测试手动驱动，定时器不触发
        logger: logs.add,
      );
      await poller.start();
      cond.token = true;
    });

    tearDown(() async {
      await poller.dispose();
      await states.close();
      await server.stop();
    });

    test('游标推进：成功入队才推进并持久化，增量轮询带 since_id', () async {
      events.addAll([event(1), event(2), event(3)]);
      await poller.pollOnce();
      expect(sent, hasLength(3));
      expect(poller.cursor, 3);
      expect(poller.uploadedCount, 3);

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(ScreenMemoEventPoller.cursorKey), 3);

      // 新增两条：下一轮 since_id=3，只发新事件。
      events.addAll([event(4), event(5)]);
      await poller.pollOnce();
      expect(sent, hasLength(5));
      expect(poller.cursor, 5);
      final lastCall = server.calls.last['arguments'] as Map<String, dynamic>;
      expect(lastCall['since_id'], 3);
      expect(lastCall['limit'], 100);
    });

    test('event_id 幂等形状 sm-<id>，字段透传 + 重启后游标恢复', () async {
      events.add(event(7,
          kind: 'screen.segment_ready',
          occurredAt: 1729999999000,
          privacyClass: 'owner_private',
          summary: '段落生成完成',
          evidenceRef: 'v1.abc.def'));
      await poller.pollOnce();

      expect(sent, hasLength(1));
      final s = sent.single;
      expect(s.eventId, 'sm-0000000000007');
      expect(s.kind, 'screen.segment_ready');
      expect(s.occurredAt, 1729999999000);
      expect(s.privacyClass, 'owner_private');
      expect(s.evidenceRef, 'v1.abc.def');
      expect(poller.lastEventAtMs, 1729999999000);

      // 模拟重启：新 poller 用同一 SharedPreferences，游标从 7 续传。
      final poller2 = ScreenMemoEventPoller(
        governor: permissiveGovernor(),
        client: client,
        sendEvent: pollerSendNoop,
        transportStates: states.stream,
        isAllPaused: () async => true,
        hasToken: () async => false,
        moduleEnabled: () => false,
        initialTransportState: TransportState.disconnected,
        interval: const Duration(hours: 1),
      );
      await poller2.start();
      expect(poller2.cursor, 7);
      await poller2.dispose();
    });

    test('summary 截断到 200 字符', () async {
      events.add(event(1, summary: '长' * 500));
      await poller.pollOnce();
      expect(sent.single.summary.length, 200);
    });

    test('MCP 工具错误：静默跳过、不推进游标、记失败', () async {
      events.add(event(1));
      server.handlers['poll_events'] =
          (args) => toolError('event export is disabled');
      await poller.pollOnce();
      expect(sent, isEmpty);
      expect(poller.cursor, 0);
      expect(poller.consecutiveFailures, 1);
      expect(poller.lastError, isNotNull);

      // 恢复后从游标 0 续传。
      server.handlers['poll_events'] = pollHandler;
      await poller.pollOnce();
      expect(sent, hasLength(1));
      expect(poller.cursor, 1);
      expect(poller.consecutiveFailures, 0);
    });

    test('MCP 不可达：不炸、不推进、计失败', () async {
      await poller.dispose();
      final deadPort = await FakeMcpServer.freePort();
      poller = ScreenMemoEventPoller(
        client: ScreenMemoMcpClient(
          port: deadPort,
          tokenProvider: () async => server.token,
        ),
        sendEvent: pollerSendNoop,
        transportStates: states.stream,
        isAllPaused: () async => false,
        hasToken: () async => false, // start-kick 跳过
        moduleEnabled: () => true,
        initialTransportState: TransportState.online,
        interval: const Duration(hours: 1),
        logger: logs.add,
      );
      await poller.start();
      events.add(event(1));
      await poller.pollOnce(); // hasToken=false → 跳过
      expect(sent, isEmpty);

      // 打开 token：MCP 连接拒绝 → unavailable，静默跳过。
      var tokenReady = false;
      final poller2 = ScreenMemoEventPoller(
        governor: permissiveGovernor(),
        client: ScreenMemoMcpClient(
          port: deadPort,
          tokenProvider: () async => server.token,
        ),
        sendEvent: pollerSendNoop,
        transportStates: states.stream,
        isAllPaused: () async => false,
        hasToken: () async => tokenReady,
        moduleEnabled: () => true,
        initialTransportState: TransportState.online,
        interval: const Duration(hours: 1),
        logger: logs.add,
      );
      await poller2.start();
      tokenReady = true;
      await poller2.pollOnce();
      expect(poller2.cursor, 0);
      expect(poller2.consecutiveFailures, 1);
      expect(logs.any((l) => l.contains('poll_events')), isTrue);
      await poller2.dispose();
    });

    test('全局暂停 / 模块关 / 传输离线：不发 MCP 请求', () async {
      events.add(event(1));
      final callsBefore = server.calls.length;

      cond.paused = true;
      await poller.pollOnce();
      cond.paused = false;
      cond.module = false;
      await poller.pollOnce();
      cond.module = true;

      expect(server.calls.length, callsBefore); // 没有任何 poll_events 请求
      expect(sent, isEmpty);
      expect(poller.cursor, 0);

      // 恢复后正常续传。
      await poller.pollOnce();
      expect(sent, hasLength(1));
    });

    test('传输离线不轮询（disconnected 状态）', () async {
      await poller.dispose();
      poller = ScreenMemoEventPoller(
        governor: permissiveGovernor(),
        client: client,
        sendEvent: pollerSendNoop,
        transportStates: states.stream,
        isAllPaused: () async => false,
        hasToken: () async => true,
        moduleEnabled: () => true,
        initialTransportState: TransportState.disconnected,
        interval: const Duration(hours: 1),
        logger: logs.add,
      );
      await poller.start();
      events.add(event(1));
      final callsBefore = server.calls.length;
      await poller.pollOnce();
      expect(server.calls.length, callsBefore);
      expect(sent, isEmpty);
    });

    test('批次中途入队失败：游标停在最后成功处，下轮幂等重试', () async {
      events.addAll([event(1), event(2), event(3)]);
      failOnId = 2;
      await poller.pollOnce();
      expect(sent.map((s) => s.eventId), ['sm-0000000000001']);
      expect(poller.cursor, 1);
      expect(poller.consecutiveFailures, 1);

      failOnId = 0;
      await poller.pollOnce();
      expect(sent.map((s) => s.eventId), ['sm-0000000000001', 'sm-0000000000002', 'sm-0000000000003']);
      expect(poller.cursor, 3);
    });

    test('连续失败 ≥5 次后指数退避，封顶 maxBackoff', () async {
      server.handlers['poll_events'] = (args) => toolError('down');
      for (var i = 0; i < 4; i++) {
        await poller.pollOnce();
      }
      expect(poller.consecutiveFailures, 4);
      expect(poller.currentDelay, const Duration(hours: 1)); // 未退避

      // 第 5 次失败进入退避：1h << 1 = 2h，超过默认 maxBackoff(5min) → 封顶。
      await poller.pollOnce();
      expect(poller.consecutiveFailures, 5);
      expect(poller.currentDelay, const Duration(minutes: 5));

      // 换小间隔验证指数增长：15s → 30s → 60s → … → 封顶 5min。
      await poller.dispose();
      var tokenReady = false;
      poller = ScreenMemoEventPoller(
        governor: permissiveGovernor(),
        client: client,
        sendEvent: pollerSendNoop,
        transportStates: states.stream,
        isAllPaused: () async => false,
        hasToken: () async => tokenReady,
        moduleEnabled: () => true,
        initialTransportState: TransportState.online,
        interval: const Duration(seconds: 15),
        maxBackoff: const Duration(minutes: 5),
        logger: logs.add,
      );
      await poller.start();
      tokenReady = true;
      for (var i = 0; i < 5; i++) {
        await poller.pollOnce();
      }
      expect(poller.consecutiveFailures, 5);
      expect(poller.currentDelay, const Duration(seconds: 30));
      await poller.pollOnce();
      expect(poller.currentDelay, const Duration(seconds: 60));
      for (var i = 0; i < 3; i++) {
        await poller.pollOnce();
      }
      expect(poller.consecutiveFailures, 9);
      expect(poller.currentDelay, const Duration(minutes: 5));
    });
  });
}

/// 丢弃型 sender（不关心上行内容的测试用）。
Future<String> pollerSendNoop({
  required String kind,
  required String summary,
  int? occurredAt,
  String privacyClass = 'owner_private',
  String? evidenceRef,
  String? eventId,
}) async =>
    eventId ?? '';
