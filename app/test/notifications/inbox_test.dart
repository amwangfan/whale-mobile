import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/notifications/inbox_page.dart';

/// 通知收件箱：分类过滤 / 已读标记 / 撤回展示（修正方案 N2）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('whale/transport');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  List<Map<String, dynamic>> fixture = [];
  final calls = <MethodCall>[];

  void mockNative() {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'notificationInbox.history':
          return fixture;
        case 'notificationInbox.markRead':
          return <String, dynamic>{'updated': 1};
        case 'notificationInbox.markAllRead':
          return <String, dynamic>{'updated': fixture.length};
        default:
          return null;
      }
    });
  }

  setUp(() {
    calls.clear();
    fixture = [
      {
        'event_id': 'evt_1',
        'notification_id': 'notif_1',
        'category': 'chat.message',
        'title': '新消息',
        'body': '你好',
        'status': 'displayed',
        'created_at': 1794052800000,
        'received_at': 1794052801000,
        'read': false,
      },
      {
        'event_id': 'evt_2',
        'notification_id': 'notif_2',
        'category': 'task.completed',
        'title': '任务完成',
        'body': '搜索任务已完成',
        'status': 'opened',
        'created_at': 1794052800000,
        'received_at': 1794052802000,
        'read': true,
      },
      {
        'event_id': 'evt_3',
        'notification_id': 'notif_3',
        'category': 'approval.requested',
        'title': '审批请求',
        'body': '是否允许发送消息？',
        'status': 'displayed',
        'created_at': 1794052800000,
        'received_at': 1794052803000,
        'read': false,
      },
      {
        'event_id': 'evt_4',
        'notification_id': 'notif_4',
        'category': 'chat.message',
        'title': '旧消息',
        'body': '已被撤回的内容',
        'status': 'revoked',
        'created_at': 1794052700000,
        'received_at': 1794052700000,
        'read': false,
      },
    ];
    mockNative();
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  Future<void> pumpInbox(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: InboxPage()));
    await tester.pumpAndSettle();
  }

  testWidgets('历史列表全量展示', (tester) async {
    await pumpInbox(tester);
    expect(find.text('新消息'), findsOneWidget);
    expect(find.text('任务完成'), findsOneWidget);
    expect(find.text('审批请求'), findsOneWidget);
  });

  testWidgets('分类过滤：聊天分类只显示聊天通知', (tester) async {
    await pumpInbox(tester);
    await tester.tap(find.text('聊天'));
    await tester.pumpAndSettle();
    expect(find.text('新消息'), findsOneWidget);
    expect(find.text('旧消息'), findsOneWidget);
    expect(find.text('任务完成'), findsNothing);
    expect(find.text('审批请求'), findsNothing);
  });

  testWidgets('分类过滤：任务分类', (tester) async {
    await pumpInbox(tester);
    await tester.tap(find.text('任务'));
    await tester.pumpAndSettle();
    expect(find.text('任务完成'), findsOneWidget);
    expect(find.text('新消息'), findsNothing);
  });

  testWidgets('已读标记：点击未读通知调原生 markRead 并消红点', (tester) async {
    await pumpInbox(tester);
    await tester.tap(find.text('新消息'));
    await tester.pumpAndSettle();
    expect(
      calls.any((c) =>
          c.method == 'notificationInbox.markRead' &&
          (c.arguments as Map)['notification_id'] == 'notif_1'),
      isTrue,
    );
  });

  testWidgets('撤回展示：revoked 通知显示「已撤回」且划线', (tester) async {
    await pumpInbox(tester);
    expect(find.textContaining('[已撤回]'), findsOneWidget);
    final title = tester.widget<Text>(find.text('旧消息'));
    expect(title.style?.decoration, TextDecoration.lineThrough);
  });

  testWidgets('全部已读按钮调原生 markAllRead', (tester) async {
    await pumpInbox(tester);
    await tester.tap(find.byIcon(Icons.done_all));
    await tester.pumpAndSettle();
    expect(
      calls.any((c) => c.method == 'notificationInbox.markAllRead'),
      isTrue,
    );
  });

  test('InboxFilter 分组覆盖协议 §13.1 全部分类', () {
    const protocolCategories = {
      'chat.message',
      'task.started',
      'task.progress',
      'task.completed',
      'task.failed',
      'approval.requested',
      'device.offline',
      'device.online',
      'memory.updated',
      'reminder.triggered',
    };
    for (final c in protocolCategories) {
      final matched =
          InboxFilter.values.where((f) => f != InboxFilter.all && f.matches(c));
      expect(matched.length, 1, reason: '$c 应恰好落入一个分类');
    }
    // all 匹配一切
    for (final c in protocolCategories) {
      expect(InboxFilter.all.matches(c), isTrue);
    }
  });
}
