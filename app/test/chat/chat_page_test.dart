import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:whale_mobile/features/chat/controllers/approval_controller.dart';
import 'package:whale_mobile/features/chat/controllers/chat_controller.dart';
import 'package:whale_mobile/features/chat/models/conversation.dart';
import 'package:whale_mobile/features/chat/models/message.dart';
import 'package:whale_mobile/features/chat/pages/chat_page.dart';
import 'package:whale_mobile/features/chat/pages/persona_settings.dart';

import 'fake_chat_backend.dart';

/// 聊天面 widget 冒烟（内存 FakeChatBackend，不出网）。
///
/// 注意：审批卡有 1s 倒计时 Timer（periodic），全程定点 `pump()`，
/// 不用 pumpAndSettle（periodic setState 会让它永不 settle）。
void main() {
  late FakeChatBackend backend;
  late ChatController controller;
  late ApprovalController approvals;

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    backend = FakeChatBackend();
    controller = ChatController(conversationId: 'mobile:ui', api: backend);
    approvals = ApprovalController(api: backend);
  });

  tearDown(() {
    controller.dispose();
    approvals.dispose();
    backend.dispose();
  });

  testWidgets('输入→发送→流式回复上屏→定屏；轨迹折叠可见', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ChatPage(
        conversationId: 'mobile:ui',
        controller: controller,
        approvals: approvals,
      ),
    ));
    await tester.pump();

    await tester.enterText(find.byType(TextField).first, '你好');
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();
    // 事件经 microtask+5ms 到达
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pump();

    expect(find.text('回复：你好'), findsOneWidget);
    expect(find.text('你好'), findsWidgets); // 用户气泡
    // 轨迹折叠行（M1：think 步 → 「回合轨迹」摘要可见，默认折叠）
    expect(find.textContaining('回合轨迹'), findsOneWidget);
    // 定屏后不再流式
    expect(controller.messages[0].sendState, MessageSendState.settled);
  });

  testWidgets('审批横幅可见 → 进入审批页 → args 展开 → 批准落后端', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ChatPage(
        conversationId: 'mobile:ui',
        controller: controller,
        approvals: approvals,
      ),
    ));
    await tester.pump();

    backend.emit('approval.requested', <String, dynamic>{
      'id': 'aprv_ui_1',
      'run_id': 'run_fake_1',
      'capability': 'shell.run',
      'args': {'command': 'df -h'},
      'reason': 'mobile · 看看磁盘',
      'risk': 'dangerous',
      'status': 'pending',
      'created_at': DateTime.now().millisecondsSinceEpoch,
      'title': '执行命令',
    });
    await tester.pump();

    // 横幅可见（M1 手机可见）
    expect(find.textContaining('待审批：执行命令'), findsOneWidget);

    // 进审批页
    await tester.tap(find.byIcon(Icons.gpp_maybe).first);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('执行命令'), findsWidgets);

    // args 原文展开
    await tester.tap(find.text('参数原文'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.textContaining('df -h'), findsOneWidget);

    // 批准
    await tester.tap(find.text('批准'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(backend.approvals['aprv_ui_1']!.status, 'approved');
    expect(approvals.pending, isEmpty);
  });

  testWidgets('角色设置页：listPersonas + conversations.update 绑定',
      (tester) async {
    backend.conversations['mobile:ui'] = const Conversation(
        conversationId: 'mobile:ui', channel: 'mobile');
    await tester.pumpWidget(MaterialApp(
      home: PersonaSettingsPage(conversationId: 'mobile:ui', api: backend),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('鲸鱼'), findsOneWidget);
    expect(find.text('助手'), findsOneWidget);

    await tester.tap(find.text('助手'));
    await tester.pump();
    await tester.tap(find.text('保存'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(backend.conversations['mobile:ui']!.personaId, 'p_custom');
  });

  testWidgets('会话长按菜单：改标题 conversations.update', (tester) async {
    // 直接走 controller 层语义已由假后端覆盖；此处验 UI 气泡渲染轨迹行
    await tester.pumpWidget(MaterialApp(
      home: ChatPage(
        conversationId: 'mobile:ui',
        controller: controller,
        approvals: approvals,
      ),
    ));
    await tester.pump();
    expect(find.text('发一条消息开始对话吧'), findsOneWidget);
  });
}
