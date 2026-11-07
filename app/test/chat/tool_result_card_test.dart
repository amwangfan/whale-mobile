import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/widgets/tool_result_card.dart';

/// 工具状态卡片四态区分（规划 §5.5 硬要求：不伪装成普通文字）。
void main() {
  Widget wrap(Widget child) =>
      MaterialApp(home: Scaffold(body: child));

  testWidgets('模型生成中：蓝灰 + 旋转进度 + 「模型正在生成」', (tester) async {
    await tester.pumpWidget(wrap(const ToolResultCard(
      state: ToolCardState.generating,
    )));
    expect(find.text('模型正在生成…'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byIcon(Icons.check_circle_outline), findsNothing);
  });

  testWidgets('工具执行中：显示工具名与确定进度', (tester) async {
    await tester.pumpWidget(wrap(const ToolResultCard(
      state: ToolCardState.running,
      toolName: 'shell.run',
      summary: '正在执行',
      progress: 40,
    )));
    expect(find.textContaining('工具正在执行'), findsOneWidget);
    expect(find.textContaining('shell.run'), findsOneWidget);
    final indicator =
        tester.widget<CircularProgressIndicator>(find.byType(CircularProgressIndicator));
    expect(indicator.value, closeTo(0.4, 0.001));
  });

  testWidgets('工具失败：红色错误图标 + 「工具执行失败」', (tester) async {
    await tester.pumpWidget(wrap(const ToolResultCard(
      state: ToolCardState.failed,
      toolName: 'device.tap',
      receipt: 'failed: stale_observation',
    )));
    expect(find.textContaining('工具执行失败'), findsOneWidget);
    expect(find.byIcon(Icons.error_outline), findsOneWidget);
    expect(find.textContaining('stale_observation'), findsOneWidget);
  });

  testWidgets('完成：绿色对勾 + 「已完成」', (tester) async {
    await tester.pumpWidget(wrap(const ToolResultCard(
      state: ToolCardState.done,
      toolName: 'screen.search',
      summary: '找到 3 条结果',
    )));
    expect(find.textContaining('已完成'), findsOneWidget);
    expect(find.byIcon(Icons.check_circle_outline), findsOneWidget);
    expect(find.text('找到 3 条结果'), findsOneWidget);
  });

  test('回执文本推断失败态（succeeded→done / failed→failed）', () {
    expect(ToolResultCard.stateFromResult('succeeded: ok'), ToolCardState.done);
    expect(ToolResultCard.stateFromResult('failed: unavailable'),
        ToolCardState.failed);
    expect(ToolResultCard.stateFromResult('timeout after 10s'),
        ToolCardState.failed);
    expect(ToolResultCard.stateFromResult(null), ToolCardState.done);
  });
}
