import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/controllers/approval_controller.dart';
import 'package:whale_mobile/features/chat/models/approval.dart';
import 'package:whale_mobile/features/chat/services/link_chat_api.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

import '../link/fake_link_server.dart';

/// 审批中心（M1：手机可见可裁决；一次性 + 600s + 重复 conflict + 断线补齐）。
void main() {
  late FakeLinkServer server;
  late LinkClient client;
  late LinkChatApi api;
  late ApprovalController controller;

  final approvalRow = <String, dynamic>{
    'id': 'aprv_test_1',
    'run_id': 'run_fake_1',
    'capability': 'shell.run',
    'args': {'command': 'df -h'},
    'reason': 'mobile · 看看磁盘',
    'risk': 'dangerous',
    'status': 'pending',
    'created_at': DateTime.now().millisecondsSinceEpoch,
    'resolved_at': null,
    'resolved_by': null,
    'note': null,
    'title': '执行命令',
    'provider': 'core',
  };

  setUp(() async {
    server = FakeLinkServer();
    await server.start();
    client = LinkClient(
      uri: server.uri,
      token: 'test-token',
      backoffSteps: const [Duration(milliseconds: 30)],
      pingInterval: const Duration(seconds: 3600),
    );
    client.connect();
    await _until(() => client.isConnected);
    api = LinkChatApi(client);
    controller = ApprovalController(api: api);
  });

  tearDown(() async {
    controller.dispose();
    await client.dispose();
    await server.stop();
  });

  test('approval.requested 事件 → 待决列表（args 原文保留）', () async {
    server.pushEvent('approval.requested', Map.of(approvalRow));
    await _until(() => controller.pending.isNotEmpty);
    final a = controller.pending.single;
    expect(a.id, 'aprv_test_1');
    expect(a.capability, 'shell.run');
    expect(a.args['command'], 'df -h');
    expect(a.displayTitle, '执行命令');
    expect(a.risk, 'dangerous');
    expect(a.isPending, isTrue);
  });

  test('裁决：approve + note → 服务端落 approved；approval.resolved 事件移除待决',
      () async {
    server.pushEvent('approval.requested', Map.of(approvalRow));
    await _until(() => controller.pending.isNotEmpty);
    final err = await controller.resolve(controller.pending.single, true,
        note: '主人同意');
    expect(err, isNull);
    await _until(() => controller.pending.isEmpty);
    expect(server.approvals['aprv_test_1']!['status'], 'approved');
    expect(server.approvals['aprv_test_1']!['note'], '主人同意');
    expect(controller.history.single.status, 'approved');
    expect(controller.lastResolveMessage, '已批准');
  });

  test('重复核销 → conflict → 显式提示 + 待决权威刷新', () async {
    server.pushEvent('approval.requested', Map.of(approvalRow));
    await _until(() => controller.pending.isNotEmpty);
    final a = controller.pending.single;
    expect(await controller.resolve(a, false), isNull);
    await _until(() => controller.pending.isEmpty);
    // 重复裁决同一 id（如双击/他端已裁决）
    final err = await controller.resolve(a, true);
    expect(err, contains('已被处理'));
  });

  test('断线补齐：断线窗口的 requested 经 catchUp（events.query）补齐', () async {
    await server.dropAll();
    await _until(() => client.state != LinkState.connected);
    // 断线窗口：服务端发起审批（只记日志）
    server.requestApprovalOffline(Map.of(approvalRow));
    await _until(() => client.isConnected, timeoutMs: 3000);
    // 重连 welcome 触发 catchUp（ApprovalController 监听 welcomes）
    await _until(() => controller.pending.isNotEmpty, timeoutMs: 3000);
    expect(controller.pending.single.id, 'aprv_test_1');
  });

  test('历史列表：status=null 拉全部（含已决 note/resolved_by）', () async {
    server.pushEvent('approval.requested', Map.of(approvalRow));
    await _until(() => controller.pending.isNotEmpty);
    await controller.resolve(controller.pending.single, true, note: 'n1');
    await _until(() => controller.pending.isEmpty);
    await controller.loadHistory();
    expect(controller.history.single.note, 'n1');
    expect(controller.history.single.resolvedBy, 'mobile');
  });

  test('600s 倒计时口径：remainingMs 递减', () {
    final a = Approval.fromJson(Map.of(approvalRow));
    final now = DateTime.now().millisecondsSinceEpoch;
    final remain = a.remainingMs(now);
    expect(remain, greaterThan(590 * 1000));
    expect(remain, lessThanOrEqualTo(600 * 1000));
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
