import 'package:flutter_test/flutter_test.dart';
import 'package:whale_mobile/modules/screenmemo/event_governor.dart';

void main() {
  const base = 1793500000000; // fixed epoch ms

  GovernedEvent ev(int id, String kind, String summary, {int? at}) =>
      GovernedEvent(
          id: id, kind: kind, summary: summary, occurredAtMs: at ?? base);

  group('EV-01 感知数据治理', () {
    test('正常事件通过并计数', () {
      final g = EventGovernor();
      final d = g.decide(ev(1, 'screen.app_changed', '番茄: 打开'), base);
      expect(d.pass, isTrue);
      expect(g.stats['passed'], 1);
    });

    test('显式开启过期策略时才丢弃', () {
      final g = EventGovernor(staleMs: 10 * 60 * 1000);
      final d = g.decide(
          ev(1, 'screen.app_changed', '番茄: 打开', at: base - 11 * 60 * 1000),
          base);
      expect(d.pass, isFalse);
      expect(d.reason, 'stale');
    });

    test('默认隐私关键词过滤（输入法/支付/锁屏）', () {
      final g = EventGovernor();
      for (final label in ['搜狗输入法', '支付宝支付', 'lockscreen']) {
        final d = g.decide(ev(1, 'screen.app_changed', '$label: x'), base);
        expect(d.pass, isFalse, reason: label);
        expect(d.reason, startsWith('filtered'));
      }
    });

    test('用户黑名单强制 / 白名单豁免默认关键词', () {
      final g = EventGovernor(
        userDenyLabels: ['公司内网'],
        userAllowLabels: ['微信支付'], // 演示：用户明确放行默认关键词
      );
      expect(
          g.decide(ev(1, 'k', '公司内网: 打卡'), base).reason, 'filtered_user_deny');
      expect(g.decide(ev(2, 'k', '微信支付: 30元'), base).pass, isTrue);
    });

    test('同 App 同 kind 时间窗内合并', () {
      final g = EventGovernor(mergeWindowMs: 60000);
      expect(g.decide(ev(1, 'screen.app_changed', '番茄: 打开'), base).pass, isTrue);
      final d = g.decide(
          ev(2, 'screen.app_changed', '番茄: 还在', at: base + 30 * 1000),
          base + 30 * 1000);
      expect(d.pass, isFalse);
      expect(d.reason, 'merged');
      // 窗口外通过（正文显著不同，避开 simhash）
      expect(
          g
              .decide(
                  ev(3, 'screen.app_changed', '番茄: 切换到书架浏览了历史记录 abc xyz',
                      at: base + 61 * 1000),
                  base + 61 * 1000)
              .pass,
          isTrue);
    });

    test('OCR 近重复 simhash 抑制', () {
      // simhash 对长文本稳定：用较长的真实感 OCR 摘要，仅个别字符差异
      const t1 = '第1章 少年离开山村，踏上修行之路。山门巍峨，云雾缭绕，'
          '接引弟子登记来历，发下入门令牌与基础功法，嘱咐勤加修炼。';
      const t2 = '第1章 少年离开山村，踏上修行之路。山门巍峨，云雾缭绕，'
          '接引弟子登记来历，发下入门令牌与基础功法，嘱咐勤加修习。';
      final g = EventGovernor(mergeWindowMs: 0); // 关掉合并单测 simhash
      expect(g.decide(ev(1, 'screen.ocr_ready', '番茄: $t1'), base).pass, isTrue);
      final d = g.decide(ev(2, 'screen.ocr_ready', '番茄: $t2', at: base + 70000),
          base + 70000);
      expect(d.pass, isFalse);
      expect(d.reason, 'simhash_dup');
      // 正文显著不同则通过
      expect(
          g
              .decide(
                  ev(3, 'screen.ocr_ready',
                      '番茄: 第2章 演武场比武，拳脚相交，裁判吹响铜锣。',
                      at: base + 140000),
                  base + 140000)
              .pass,
          isTrue);
    });

    test('每 App 与全局预算上限', () {
      final g = EventGovernor(perAppPerHour: 3, totalPerHour: 5);
      // 正文两两显著不同（绕过 simhash/合并），验证纯预算维度
      const topics = [
        '春天的山谷开满野花，溪流潺潺',
        '量子计算利用叠加态进行并行运算',
        '红烧狮子头需肥瘦相间慢火细炖',
        '马拉松训练要循序渐进控制配速',
        '巴洛克建筑强调曲线与光影对比',
        '深海热泉孕育独特的生态系统',
        '印象派画家偏好户外写生光影',
        '丝绸之路连接了东西方文明交流',
      ];
      for (var i = 1; i <= 3; i++) {
        expect(
            g.decide(
                ev(i, 'screen.ocr_ready', 'A: ${topics[i - 1]}',
                    at: base + i * 120000),
                base + i * 120000)
                .pass,
            isTrue,
            reason: 'i=$i');
      }
      final d4 = g.decide(
          ev(4, 'screen.ocr_ready', 'A: ${topics[3]}', at: base + 4 * 120000),
          base + 4 * 120000);
      expect(d4.reason, 'budget_app');
      // 另一个 App 也受总量约束
      var passedB = 0;
      for (var i = 1; i <= 5; i++) {
        if (g
            .decide(
                ev(100 + i, 'screen.ocr_ready', 'B: ${topics[2 + i]}',
                    at: base + 300000 + i * 120000),
                base + 300000 + i * 120000)
            .pass) {
          passedB++;
        }
      }
      expect(passedB, 5 - 3); // 总量 5，A 已占 3
    });

    test('默认无数量预算和过期丢弃，允许恢复较早事件', () {
      final g = EventGovernor(mergeWindowMs: 0, simhashThreshold: -1);
      for (var i = 0; i < 150; i++) {
        final event = ev(i, 'screen.app_changed', '番茄: 状态$i',
            at: base - 24 * 3600 * 1000 + i * 60000);
        expect(g.decide(event, base).pass, isTrue);
      }
      expect(g.stats['passed'], 150);
    });

    test('先检查再确认入队：发送失败不占用去重窗口', () {
      final g = EventGovernor();
      final e = ev(1, 'screen.ocr_ready', '番茄: 一段 OCR 内容');
      expect(g.inspect(e, base).pass, isTrue);
      // 失败时不调用 recordAccepted；相同事件必须还能重试。
      final retry = g.inspect(e, base);
      expect(retry.pass, isTrue);
      expect(g.stats['passed'], isNull);
      g.recordAccepted(e, bodyHash: retry.bodyHash);
      expect(g.inspect(e, base + 1000).reason, 'merged');
    });

    test('近重复只在时间窗内、同事件类型内生效', () {
      final g = EventGovernor(mergeWindowMs: 0, simhashWindowMs: 300000);
      final e = ev(1, 'screen.ocr_ready', '番茄: 同一段正文');
      expect(g.decide(e, base).pass, isTrue);
      expect(g.decide(ev(2, 'screen.app_changed', '番茄: 同一段正文',
          at: base + 1000), base + 1000).pass, isTrue);
      expect(g.decide(ev(3, 'screen.ocr_ready', '番茄: 同一段正文',
          at: base + 301000), base + 301000).pass, isTrue);
    });

    test('长时间使用时去重表容量有限', () {
      final g = EventGovernor(mergeWindowMs: 0);
      for (var i = 0; i < 520; i++) {
        expect(g.decide(ev(i, 'screen.ocr_ready', 'App$i: 页面变化',
                at: base + i * 100), base + i * 100).pass, isTrue);
      }
      // 最近事件仍被去重，已从缓存淘汰的旧 App 不再占用内存。
      expect(g.decide(ev(521, 'screen.ocr_ready', 'App519: 页面变化',
              at: base + 52000), base + 52000).reason, 'simhash_dup');
      expect(g.decide(ev(522, 'screen.ocr_ready', 'App0: 页面变化',
              at: base + 52000), base + 52000).pass, isTrue);
    });

    test('simhash64 稳定性与 hamming 语义', () {
      final h1 = EventGovernor.simhash64('一样的文本内容');
      final h2 = EventGovernor.simhash64('文本完全不同的内容哦');
      expect(EventGovernor.hamming64(h1, h1), 0);
      expect(EventGovernor.hamming64(h1, h2) > 10, isTrue);
    });
  });
}
