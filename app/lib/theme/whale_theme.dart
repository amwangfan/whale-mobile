/// whale-mobile 设计语言锚定层 · 主题（草案 docs/whale-mobile-redesign-flutter-mapping.md §3）。
///
/// token 逐值来自原型 `console-redesign/mobile/src/mobile/mobile.css`，深/浅双组。
/// 本层只锚定主题与语义色，不动任何屏结构。
library;

import 'package:flutter/material.dart';

/// 语义 tone（原型 data.ts `Tone`）：状态徽标/标签的统一取色键。
enum WhaleTone { aqua, violet, amber, coral, blue, slate }

/// 一组主题 token 的实体表（深/浅各一份实例）。字段名对齐 CSS token（--m-*）。
class WhaleTokens {
  const WhaleTokens({
    required this.bg,
    required this.bg2,
    required this.surface,
    required this.surface2,
    required this.elev,
    required this.line,
    required this.line2,
    required this.ink,
    required this.ink2,
    required this.ink3,
    required this.tone,
    required this.toneSoft,
  });

  final Color bg;
  final Color bg2;
  final Color surface;
  final Color surface2;
  final Color elev;
  final Color line;
  final Color line2;
  final Color ink;
  final Color ink2;
  final Color ink3;

  /// 六个语义色（主色 aqua / 身份色 violet / 警告 amber / 错误 coral / 信息 blue / 中性 slate）。
  final Map<WhaleTone, Color> tone;

  /// 对应的 soft 底（徽标底色）。
  final Map<WhaleTone, Color> toneSoft;

  Color of(WhaleTone t) => tone[t]!;
  Color softOf(WhaleTone t) => toneSoft[t]!;

  /// 深色组（mobile.css `:root`）。
  static const WhaleTokens dark = WhaleTokens(
    bg: Color(0xFF0A0E17),
    bg2: Color(0xFF0D1524),
    surface: Color(0xFF111826),
    surface2: Color(0xFF17202F),
    elev: Color(0xFF1B2534),
    line: Color(0x13FFFFFF), // rgba(255,255,255,.075)
    line2: Color(0x0BFFFFFF), // rgba(255,255,255,.045)
    ink: Color(0xFFE8EDF6),
    ink2: Color(0xFF9AA7BC),
    ink3: Color(0xFF67748B),
    tone: {
      WhaleTone.aqua: Color(0xFF46CDB8),
      WhaleTone.violet: Color(0xFF8F7EE0),
      WhaleTone.amber: Color(0xFFE0A45C),
      WhaleTone.coral: Color(0xFFE2686C),
      WhaleTone.blue: Color(0xFF5E9BE0),
      WhaleTone.slate: Color(0xFF8794A9),
    },
    toneSoft: {
      WhaleTone.aqua: Color(0x2446CDB8), // .14
      WhaleTone.violet: Color(0x2B8F7EE0), // .17
      WhaleTone.amber: Color(0x29E0A45C), // .16
      WhaleTone.coral: Color(0x29E2686C),
      WhaleTone.blue: Color(0x295E9BE0),
      WhaleTone.slate: Color(0x268794A9), // .15
    },
  );

  /// 浅色组（mobile.css `[data-wm-theme='light']`）。
  static const WhaleTokens light = WhaleTokens(
    bg: Color(0xFFF3F5FA),
    bg2: Color(0xFFEEF1F7),
    surface: Color(0xFFFFFFFF),
    surface2: Color(0xFFF6F8FC),
    elev: Color(0xFFFFFFFF),
    line: Color(0x170F172A), // rgba(15,23,42,.09)
    line2: Color(0x0E0F172A), // rgba(15,23,42,.055)
    ink: Color(0xFF131A26),
    ink2: Color(0xFF5A6779),
    ink3: Color(0xFF8A95A6),
    tone: {
      WhaleTone.aqua: Color(0xFF0FA292),
      WhaleTone.violet: Color(0xFF6C5CD0),
      WhaleTone.amber: Color(0xFFB8791F),
      WhaleTone.coral: Color(0xFFC4514F),
      WhaleTone.blue: Color(0xFF3B77C4),
      WhaleTone.slate: Color(0xFF6B7788),
    },
    toneSoft: {
      WhaleTone.aqua: Color(0x1F0FA292), // .12
      WhaleTone.violet: Color(0x1F6C5CD0),
      WhaleTone.amber: Color(0x21B8791F), // .13
      WhaleTone.coral: Color(0x1FC4514F),
      WhaleTone.blue: Color(0x1F3B77C4),
      WhaleTone.slate: Color(0x1F6B7788),
    },
  );
}

/// 语义色经由 ThemeExtension 暴露给 widget 树（`WhaleTheme.tokensOf(context)`）。
class WhaleThemeExtension extends ThemeExtension<WhaleThemeExtension> {
  const WhaleThemeExtension(this.tokens);

  final WhaleTokens tokens;

  @override
  WhaleThemeExtension copyWith({WhaleTokens? tokens}) =>
      WhaleThemeExtension(tokens ?? this.tokens);

  @override
  WhaleThemeExtension lerp(ThemeExtension<WhaleThemeExtension>? other, double t) =>
      other is WhaleThemeExtension ? other : this;
}

/// 原型 token → ThemeData 的锚定映射（草案 §3 表）。
abstract final class WhaleTheme {
  /// 统一圆角（--m-radius: 20px）。
  static const double radius = 20;

  /// 底栏高度（--m-tabbar: 60px）。
  static const double tabBarHeight = 60;

  static WhaleTokens tokensOf(BuildContext context) =>
      Theme.of(context).extension<WhaleThemeExtension>()!.tokens;

  static ThemeData dark() => _from(WhaleTokens.dark, Brightness.dark);
  static ThemeData light() => _from(WhaleTokens.light, Brightness.light);

  static ThemeData _from(WhaleTokens t, Brightness brightness) {
    final textTheme = TextTheme(
      bodyLarge: TextStyle(color: t.ink),
      bodyMedium: TextStyle(color: t.ink),
      bodySmall: TextStyle(color: t.ink2),
      labelSmall: TextStyle(color: t.ink3),
      titleLarge: TextStyle(color: t.ink),
      titleMedium: TextStyle(color: t.ink),
      titleSmall: TextStyle(color: t.ink2),
    );
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      scaffoldBackgroundColor: t.bg,
      canvasColor: t.bg2,
      cardColor: t.surface,
      dividerColor: t.line,
      colorScheme: ColorScheme(
        brightness: brightness,
        primary: t.of(WhaleTone.aqua), // --m-aqua：Sea Aqua 主色
        onPrimary: t.bg,
        secondary: t.of(WhaleTone.violet), // --m-violet：Core Violet 身份色
        onSecondary: t.bg,
        surface: t.surface,
        onSurface: t.ink,
        error: t.of(WhaleTone.coral),
        onError: t.bg,
        surfaceContainerHighest: t.elev,
        outline: t.line,
      ),
      textTheme: textTheme,
      cardTheme: CardThemeData(
        color: t.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radius),
          side: BorderSide(color: t.line),
        ),
      ),
      bottomNavigationBarTheme: BottomNavigationBarThemeData(
        backgroundColor: t.surface,
        selectedItemColor: t.of(WhaleTone.aqua),
        unselectedItemColor: t.ink3,
      ),
      extensions: [WhaleThemeExtension(t)],
    );
  }
}
