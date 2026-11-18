import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/theme/whale_theme.dart';

/// 锚定层主题单测：token 逐值对原型 mobile.css，ThemeData 映射对草案 §3 表。
void main() {
  group('WhaleTokens 深色组（mobile.css :root）', () {
    test('背景/面/墨三档逐值锚定', () {
      const t = WhaleTokens.dark;
      expect(t.bg, const Color(0xFF0A0E17)); // --m-bg
      expect(t.bg2, const Color(0xFF0D1524)); // --m-bg2
      expect(t.surface, const Color(0xFF111826)); // --m-surface
      expect(t.surface2, const Color(0xFF17202F)); // --m-surface2
      expect(t.elev, const Color(0xFF1B2534)); // --m-elev
      expect(t.ink, const Color(0xFFE8EDF6)); // --m-ink
      expect(t.ink2, const Color(0xFF9AA7BC)); // --m-ink2
      expect(t.ink3, const Color(0xFF67748B)); // --m-ink3
    });

    test('语义六色 + soft 底逐值锚定', () {
      const t = WhaleTokens.dark;
      expect(t.of(WhaleTone.aqua), const Color(0xFF46CDB8)); // Sea Aqua 主色
      expect(t.of(WhaleTone.violet), const Color(0xFF8F7EE0)); // Core Violet 身份色
      expect(t.of(WhaleTone.amber), const Color(0xFFE0A45C));
      expect(t.of(WhaleTone.coral), const Color(0xFFE2686C));
      expect(t.of(WhaleTone.blue), const Color(0xFF5E9BE0));
      expect(t.of(WhaleTone.slate), const Color(0xFF8794A9));
      expect((t.softOf(WhaleTone.aqua).a * 255).round(), 0x24); // rgba(70,205,184,.14)
      expect((t.softOf(WhaleTone.violet).a * 255).round(), 0x2B); // .17
    });
  });

  group('WhaleTokens 浅色组（[data-wm-theme=light]）', () {
    test('主色/身份色/背景逐值锚定', () {
      const t = WhaleTokens.light;
      expect(t.bg, const Color(0xFFF3F5FA));
      expect(t.surface, const Color(0xFFFFFFFF));
      expect(t.of(WhaleTone.aqua), const Color(0xFF0FA292));
      expect(t.of(WhaleTone.violet), const Color(0xFF6C5CD0));
      expect(t.ink, const Color(0xFF131A26));
    });
  });

  group('ThemeData 映射（草案 §3）', () {
    test('深色：scaffold=bg, card=surface, primary=aqua, secondary=violet', () {
      final d = WhaleTheme.dark();
      expect(d.brightness, Brightness.dark);
      expect(d.scaffoldBackgroundColor, WhaleTokens.dark.bg);
      expect(d.canvasColor, WhaleTokens.dark.bg2);
      expect(d.cardColor, WhaleTokens.dark.surface);
      expect(d.dividerColor, WhaleTokens.dark.line);
      expect(d.colorScheme.primary, WhaleTokens.dark.of(WhaleTone.aqua));
      expect(d.colorScheme.secondary, WhaleTokens.dark.of(WhaleTone.violet));
      expect(d.colorScheme.error, WhaleTokens.dark.of(WhaleTone.coral));
      expect(d.textTheme.bodyMedium?.color, WhaleTokens.dark.ink);
      expect(d.textTheme.bodySmall?.color, WhaleTokens.dark.ink2);
    });

    test('浅色平行定义 + 语义色经 ThemeExtension 可取', () {
      final l = WhaleTheme.light();
      expect(l.brightness, Brightness.light);
      expect(l.colorScheme.primary, WhaleTokens.light.of(WhaleTone.aqua));
      final ext = l.extension<WhaleThemeExtension>();
      expect(ext, isNotNull);
      expect(ext!.tokens.of(WhaleTone.amber), WhaleTokens.light.of(WhaleTone.amber));
    });

    test('圆角 20 / 底栏 60 常量锚定（--m-radius / --m-tabbar）', () {
      expect(WhaleTheme.radius, 20);
      expect(WhaleTheme.tabBarHeight, 60);
    });
  });
}
