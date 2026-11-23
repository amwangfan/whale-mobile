import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/controllers/approval_controller.dart';
import 'package:whale_mobile/features/chat/models/conversation.dart';
import 'package:whale_mobile/features/chat/pages/conversation_list.dart';
import 'package:whale_mobile/features/chat/services/link_chat_api.dart';
import 'package:whale_mobile/features/chat/services/link_session.dart';
import 'package:whale_mobile/navigation/app_navigation.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

import 'fake_chat_backend.dart';

/// 假 Link 会话（不出网；acquire/release 计数可断言）。
class FakeLinkSession extends LinkSession {
  FakeLinkSession(this._backend);

  final FakeChatBackend _backend;
  late final ApprovalController _approvals = ApprovalController(api: _backend);

  int acquireCount = 0;
  int releaseCount = 0;

  /// acquire 被调用时的调度相位（钉桩：必须在 postFrameCallbacks，即首帧之后）。
  final List<SchedulerPhase> acquirePhases = <SchedulerPhase>[];

  @override
  ChatBackend? get api => _backend;

  @override
  ApprovalController? get approvals => _approvals;

  @override
  LinkState get state => LinkState.connected;

  @override
  bool get isConnected => true;

  @override
  Future<void> acquire() async {
    acquireCount++;
    acquirePhases.add(SchedulerBinding.instance.schedulerPhase);
    // 生产语义：连接就绪后 notify → 页面 _onSession → 刷新列表
    notifyListeners();
  }

  @override
  void release() {
    releaseCount++;
  }

  @override
  Future<void> reconnect() async {}
}

/// 会话列表页（聊天面首帧）渲染纪律——nova GSI 白屏回归的端侧钉桩：
/// 首帧必须画出页面骨架，任何连接工作不得参与首帧路径。
void main() {
  late FakeChatBackend backend;
  late FakeLinkSession session;

  setUp(() {
    backend = FakeChatBackend();
    session = FakeLinkSession(backend);
    AppNavigation.tabIndex.value = 0;
    // 测试绑定默认无初始生命周期事件：显式置 resumed（真机前台启动即此态）
    TestWidgetsFlutterBinding.instance
        .handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  });

  tearDown(() {
    AppNavigation.tabIndex.value = 0;
    backend.dispose();
  });

  testWidgets('首帧即渲染（AppBar/FAB/空态），acquire 推后一帧执行', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ConversationListPage(session: session),
    ));
    // pumpWidget 已完成首帧（含 post-frame 回调）：页面骨架在
    expect(find.text('聊天'), findsOneWidget);
    expect(find.byType(FloatingActionButton), findsOneWidget);
    expect(find.text('还没有会话，点右下角新建'), findsOneWidget);
    // 连接已按需启动，但**必在 postFrameCallbacks 相位**（首帧绘制之后）——
    // 连接工作绝不参与首帧 build/layout/paint 路径。
    expect(session.acquireCount, 1);
    expect(session.acquirePhases, everyElement(SchedulerPhase.postFrameCallbacks));
    await tester.pump(const Duration(milliseconds: 50));
  });

  testWidgets('会话列表正常渲染（title/preview/channel）', (tester) async {
    backend.conversations['mobile:a'] = const Conversation(
      conversationId: 'mobile:a',
      title: '日常',
      channel: 'mobile',
      updatedAt: 1795400000000,
      previewRole: 'assistant',
      previewText: '今晚吃什么',
    );
    await tester.pumpWidget(MaterialApp(
      home: ConversationListPage(session: session),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('日常'), findsOneWidget);
    expect(find.textContaining('今晚吃什么'), findsOneWidget);
  });

  testWidgets('切走聊天 tab 释放连接（按需连接语义）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ConversationListPage(session: session),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(session.acquireCount, 1);
    // 切到别的 tab（tabIndex!=0）→ release
    AppNavigation.tabIndex.value = 1;
    await tester.pump();
    expect(session.releaseCount, 1);
    // 切回 → 再 acquire
    AppNavigation.tabIndex.value = 0;
    await tester.pump();
    expect(session.acquireCount, 2);
  });
}
