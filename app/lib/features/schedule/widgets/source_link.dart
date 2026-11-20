import 'package:flutter/material.dart';

import '../../../theme/whale_theme.dart';

/// 来源高亮 ↔ 候选卡片的连线层（2026-11-19 过目通过版）。
///
/// 叠在分屏 Stack 之上（IgnorePointer），贝塞尔连接「被提取消息气泡」与「候选卡片」：
/// - 端点：宽屏=气泡右缘中点→卡片左缘中点；窄屏上下分=气泡底缘中点→卡片顶缘中点。
/// - 端点用 GlobalKey 取 RenderBox 实测位置；滚动经 ScrollController 监听逐帧重算。
/// - 源消息滚出视口：端点收边到视口缘并画指向箭头（不画悬空线）。
class SourceLink extends StatefulWidget {
  const SourceLink({
    super.key,
    required this.bubbleKey,
    required this.cardKey,
    required this.paneKey,
    required this.wide,
    required this.scrollController,
  });

  final GlobalKey bubbleKey;
  final GlobalKey cardKey;
  final GlobalKey paneKey;
  final bool wide;
  final ScrollController scrollController;

  @override
  State<SourceLink> createState() => SourceLinkState();
}

class SourceLinkState extends State<SourceLink> {
  Rect? _bubble;
  Rect? _card;
  Rect? _pane;

  @override
  void initState() {
    super.initState();
    widget.scrollController.addListener(_scheduleRecompute);
    WidgetsBinding.instance.addPostFrameCallback((_) => _recompute());
  }

  @override
  void didUpdateWidget(SourceLink oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.scrollController != widget.scrollController) {
      oldWidget.scrollController.removeListener(_scheduleRecompute);
      widget.scrollController.addListener(_scheduleRecompute);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _recompute());
  }

  @override
  void dispose() {
    widget.scrollController.removeListener(_scheduleRecompute);
    super.dispose();
  }

  void _scheduleRecompute() {
    WidgetsBinding.instance.addPostFrameCallback((_) => _recompute());
  }

  Rect? _rectOf(GlobalKey key, RenderBox ref) {
    final box = key.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.attached || !box.hasSize) return null;
    return ref.globalToLocal(box.localToGlobal(Offset.zero)) & box.size;
  }

  void _recompute() {
    if (!mounted) return;
    final ref = context.findRenderObject() as RenderBox?;
    if (ref == null || !ref.attached) return;
    final b = _rectOf(widget.bubbleKey, ref);
    final c = _rectOf(widget.cardKey, ref);
    final p = _rectOf(widget.paneKey, ref);
    if (b == _bubble && c == _card && p == _pane) return;
    setState(() {
      _bubble = b;
      _card = c;
      _pane = p;
    });
  }

  @override
  Widget build(BuildContext context) {
    final color = WhaleTheme.tokensOf(context).of(WhaleTone.aqua);
    return CustomPaint(
      painter: LinkPainter(
        bubble: _bubble,
        card: _card,
        pane: _pane,
        wide: widget.wide,
        color: color,
      ),
    );
  }
}

/// 纯绘制（端点几何见 [SourceLink]）。可见=贝塞尔实线双端点；滚出=收边+指向箭头。
class LinkPainter extends CustomPainter {
  LinkPainter({
    required this.bubble,
    required this.card,
    required this.pane,
    required this.wide,
    required this.color,
  });

  final Rect? bubble;
  final Rect? card;
  final Rect? pane;
  final bool wide;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final b = bubble, c = card, p = pane;
    if (b == null || c == null || p == null) return;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    final dots = Paint()..color = color;

    final Offset anchor = wide ? c.centerLeft : c.topCenter;
    final bool visible = b.overlaps(p);
    if (visible) {
      final Offset from = wide ? b.centerRight : b.bottomCenter;
      final gap = (anchor - from).distance / 2;
      final path = Path()
        ..moveTo(from.dx, from.dy)
        ..cubicTo(
          from.dx + (wide ? gap : 0),
          from.dy + (wide ? 0 : gap),
          anchor.dx - (wide ? gap : 0),
          anchor.dy - (wide ? 0 : gap),
          anchor.dx,
          anchor.dy,
        );
      canvas.drawPath(path, paint);
      canvas.drawCircle(from, 3, dots);
      canvas.drawCircle(anchor, 3, dots);
      return;
    }

    // 收边：气泡滚出视口 → 端点钉在视口缘，箭头指向气泡所在方向。
    final center = b.center;
    final edge = Offset(
      wide ? p.right : center.dx.clamp(p.left + 10, p.right - 10),
      center.dy.clamp(p.top + 10, p.bottom - 10),
    );
    final above = center.dy < p.top;
    canvas.drawLine(edge, anchor, paint);
    canvas.drawCircle(anchor, 3, dots);
    final dir = above ? -1.0 : 1.0;
    final tip = Offset(edge.dx, edge.dy);
    final path = Path()
      ..moveTo(tip.dx, tip.dy)
      ..lineTo(tip.dx - 5, tip.dy + 9 * dir)
      ..lineTo(tip.dx + 5, tip.dy + 9 * dir)
      ..close();
    canvas.drawPath(path, dots);
  }

  @override
  bool shouldRepaint(LinkPainter old) =>
      old.bubble != bubble ||
      old.card != card ||
      old.pane != pane ||
      old.wide != wide ||
      old.color != color;
}
