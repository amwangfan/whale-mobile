import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/schedule/controllers/schedule_controller.dart';
import 'package:whale_mobile/features/schedule/pages/schedule_page.dart';
import 'package:whale_mobile/features/schedule/pages/schedule_review_page.dart';
import 'package:whale_mobile/features/schedule/services/mock_schedule_repository.dart';
import 'package:whale_mobile/features/schedule/widgets/source_link.dart';
import 'package:whale_mobile/theme/whale_theme.dart';

/// 计数 mock：核在飞锁幂等（同 id 重复提交只应到仓储一次）。
class CountingRepo extends MockScheduleRepository {
  var addCalls = 0;
  @override
  Future<void> addCandidate(String id) async {
    addCalls++;
    await super.addCandidate(id);
  }
}

/// 日程溯源（2026-11-19 过目通过版）：列表去重 / 分屏高亮连线 / 建删幂等 / 窄屏兜底。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Widget app(Widget home) => MaterialApp(theme: WhaleTheme.dark(), home: home);

  group('controller', () {
    test('合并去重：同事件不双卡', () async {
      final c = ScheduleController(MockScheduleRepository());
      await c.load();
      // mock 3 候选中 c3 与正式 i1 同源（messageId=m-9001）→ 去重后 2 条
      expect(c.candidates.length, 2);
      expect(c.items.length, 2);
      expect(c.candidates.any((e) => e.id == 'c3'), isFalse);
    });

    test('写操作在飞锁：并发重复提交只到仓储一次', () async {
      final repo = CountingRepo();
      final c = ScheduleController(repo);
      await c.load();
      final f1 = c.add('c1');
      final f2 = c.add('c1'); // 在飞锁：应被吞
      await Future.wait([f1, f2]);
      expect(repo.addCalls, 1);
      expect(c.candidates.any((e) => e.id == 'c1'), isFalse);
      expect(c.items.any((e) => e.id == 'c1'), isTrue);
    });

    test('写失败如实：候选保留 + 失败记录；恢复后可重试', () async {
      final repo = MockScheduleRepository()..failWrites = true;
      final c = ScheduleController(repo);
      await c.load();
      expect(await c.add('c1'), isFalse);
      expect(c.candidates.any((e) => e.id == 'c1'), isTrue);
      expect(c.failureOf('c1'), isNotNull);
      repo.failWrites = false;
      expect(await c.add('c1'), isTrue);
      expect(c.candidates.any((e) => e.id == 'c1'), isFalse);
    });
  });

  group('widget', () {
    testWidgets('列表两区 + 去重后候选数 + 正式日程不双卡', (tester) async {
      await tester.pumpWidget(app(SchedulePage(
          repository: MockScheduleRepository())));
      await tester.pumpAndSettle();
      expect(find.text('待裁决候选（2）'), findsOneWidget);
      expect(find.text('正式日程（2）'), findsOneWidget);
      expect(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'), findsOneWidget);
      expect(find.text('湖边调试：智能眼镜首测见真章'), findsOneWidget);
      expect(find.text('智能眼镜首测（重复来源）'), findsNothing);
    });

    testWidgets('点开候选 → 分屏：顶栏裁决条 + 来源高亮 + 连线层', (tester) async {
      await tester.pumpWidget(app(SchedulePage(
          repository: MockScheduleRepository())));
      await tester.pumpAndSettle();
      await tester.tap(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'));
      await tester.pumpAndSettle();
      expect(find.byType(ScheduleReviewPage), findsOneWidget);
      // 顶栏（用户令：删/加自底部上移）+ 计数
      expect(find.text('添加'), findsOneWidget);
      expect(find.text('删除'), findsOneWidget);
      expect(find.text('1 / 2'), findsOneWidget);
      // 被提取消息高亮在场（摘录文本），连线层在场
      expect(find.text('天黑一点前往湖边测试4K30帧视频录制效果'), findsOneWidget);
      expect(find.byType(SourceLink), findsOneWidget);
    });

    testWidgets('添加：在飞锁钮 → 成功转正式并自动跳下一条', (tester) async {
      final repo = MockScheduleRepository(delay: const Duration(milliseconds: 300));
      await tester.pumpWidget(app(SchedulePage(repository: repo)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('添加'));
      await tester.pump(); // 在飞
      expect(
          tester
              .widget<FilledButton>(find.widgetWithText(FilledButton, '添加'))
              .onPressed,
          isNull);
      await tester.pump(const Duration(seconds: 2)); // 推假时钟越过 mock 延迟链（add+重拉）
      await tester.pumpAndSettle();
      expect(repo.snapshotItems().any((e) => e.id == 'c1'), isTrue);
      expect(find.text('周六前把演示视频旁白稿过一遍'), findsOneWidget);
      expect(find.text('1 / 1'), findsOneWidget);
    });

    testWidgets('删除：候选出列（reject 幂等）', (tester) async {
      await tester.pumpWidget(app(SchedulePage(
          repository: MockScheduleRepository())));
      await tester.pumpAndSettle();
      await tester.tap(find.text('周六前把演示视频旁白稿过一遍'));
      await tester.pumpAndSettle();
      expect(find.text('2 / 2'), findsOneWidget); // 点的是第二条
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();
      // c2 出列，回到 c1
      expect(find.text('1 / 1'), findsOneWidget);
      expect(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'), findsOneWidget);
    });

    testWidgets('写失败：卡片保留 + SnackBar 如实 + 按钮恢复', (tester) async {
      final repo = MockScheduleRepository()..failWrites = true;
      await tester.pumpWidget(app(SchedulePage(repository: repo)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(find.textContaining('操作未成功'), findsOneWidget);
      expect(find.text('1 / 2'), findsOneWidget); // 候选原样
      expect(
          tester
              .widget<FilledButton>(find.widgetWithText(FilledButton, '添加'))
              .onPressed,
          isNotNull); // 按钮恢复
    });

    testWidgets('宽屏左右分 / 窄屏 <600dp 自动上下分（拍板①）', (tester) async {
      // 宽屏（默认 800×600）：来源在左，卡片在右
      await tester.pumpWidget(app(SchedulePage(
          repository: MockScheduleRepository())));
      await tester.pumpAndSettle();
      await tester.tap(find.text('天黑一点前往湖边测试 4K 30 帧视频录制效果'));
      await tester.pumpAndSettle();
      var source = tester.getTopLeft(find.byType(ListView));
      var card = tester.getTopLeft(find.byType(Card));
      expect(source.dx < card.dx, isTrue); // 左右分

      // 窄屏：来源在上，卡片在下
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1.0;
      await tester.pumpAndSettle();
      source = tester.getTopLeft(find.byType(ListView));
      card = tester.getTopLeft(find.byType(Card));
      expect(source.dy < card.dy, isTrue); // 上下分
      tester.view.reset();
    });
  });
}
