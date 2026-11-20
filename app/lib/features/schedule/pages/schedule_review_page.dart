import 'package:flutter/material.dart';

import '../../../theme/whale_theme.dart';
import '../controllers/schedule_controller.dart';
import '../models/schedule_entry.dart';
import '../widgets/source_link.dart';

/// 分屏裁决页（2026-11-19 过目通过版）：点开候选进入。
///
/// 竖屏 50/50 左右分（左=聊天来源，右=候选卡片）；窄屏 <600dp 自动改上下分（拍板①）。
/// 顶栏=裁决条：删除 | 待办建议 i/n（‹ › 翻候选）| 添加——参考图底部两钮按用户令上移至此。
/// 建删写在飞锁、失败如实保留卡片；来源消息高亮 + 与右卡连线（SourceLink）。
class ScheduleReviewPage extends StatefulWidget {
  const ScheduleReviewPage({
    super.key,
    required this.controller,
    required this.candidateId,
  });

  final ScheduleController controller;
  final String candidateId;

  @override
  State<ScheduleReviewPage> createState() => _ScheduleReviewPageState();
}

class _ScheduleReviewPageState extends State<ScheduleReviewPage> {
  late int _index;
  final _scroll = ScrollController();
  final _paneKey = GlobalKey();
  final _cardKey = GlobalKey();
  final _bubbleKey = GlobalKey();

  List<SourceMessage>? _messages;
  bool _loadingSource = false;
  String? _sourceError;

  ScheduleController get _c => widget.controller;

  ScheduleEntry? get _current {
    final list = _c.candidates;
    if (list.isEmpty) return null;
    _index = _index.clamp(0, list.length - 1);
    return list[_index];
  }

  @override
  void initState() {
    super.initState();
    var i = _c.candidates.indexWhere((e) => e.id == widget.candidateId);
    _index = i < 0 ? 0 : i;
    _loadSource();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadSource() async {
    final e = _current;
    final src = e?.source;
    if (e == null || src == null) {
      setState(() {
        _messages = const [];
        _loadingSource = false;
        _sourceError = null;
      });
      return;
    }
    setState(() {
      _loadingSource = true;
      _sourceError = null;
    });
    try {
      final list = await _c.sourceOf(src.conversation, src.messageId);
      if (!mounted) return;
      setState(() {
        _messages = list;
        _loadingSource = false;
      });
      // 定位到被提取的那条（对齐参考图「来源」语义）。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final ctx = _bubbleKey.currentContext;
        if (ctx != null) {
          Scrollable.ensureVisible(ctx,
              duration: const Duration(milliseconds: 250), alignment: 0.4);
        }
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _sourceError = '$err';
        _loadingSource = false;
      });
    }
  }

  void _go(int delta) {
    final list = _c.candidates;
    if (list.length < 2) return;
    setState(() => _index = (_index + delta) % list.length);
    if (_index < 0) _index += list.length;
    _loadSource();
  }

  Future<void> _verdict({required bool add}) async {
    final e = _current;
    if (e == null) return;
    final ok = add ? await _c.add(e.id) : await _c.reject(e.id);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('操作未成功：${_c.failureOf(e.id) ?? "未知原因"}；可手动重试')));
      return;
    }
    if (_c.candidates.isEmpty) {
      Navigator.of(context).pop(); // 全部裁决完，回列表
      return;
    }
    _loadSource(); // 同位次已是下一条候选
  }

  @override
  Widget build(BuildContext context) {
    final tokens = WhaleTheme.tokensOf(context);
    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) {
        final e = _current;
        if (e == null) {
          // 列表已空：回本页弹出交给列表页（postFrame 防 build 期导航）。
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) Navigator.of(context).maybePop();
          });
          return const Scaffold(body: SizedBox.shrink());
        }
        final n = _c.candidates.length;
        final busy = _c.busy(e.id);
        return Scaffold(
          appBar: AppBar(title: const Text('待办建议')),
          body: Column(
            children: [
              _VerdictBar(
                index: _index,
                count: n,
                busy: busy,
                onReject: () => _verdict(add: false),
                onAdd: () => _verdict(add: true),
                onPrev: () => _go(-1),
                onNext: () => _go(1),
              ),
              Expanded(
                child: LayoutBuilder(
                  builder: (context, box) {
                    final wide = box.maxWidth >= 600;
                    final source = _SourcePane(
                      key: _paneKey,
                      entry: e,
                      messages: _messages,
                      loading: _loadingSource,
                      error: _sourceError,
                      scroll: _scroll,
                      bubbleKey: _bubbleKey,
                      onRetry: _loadSource,
                    );
                    final card = _CardPane(cardKey: _cardKey, entry: e);
                    return Stack(
                      children: [
                        Positioned.fill(
                          child: wide
                              ? Row(children: [
                                  Expanded(child: source),
                                  VerticalDivider(width: 1, color: tokens.line),
                                  Expanded(child: card),
                                ])
                              : Column(children: [
                                  Expanded(child: source),
                                  Divider(height: 1, color: tokens.line),
                                  Expanded(child: card),
                                ]),
                        ),
                        Positioned.fill(
                          child: IgnorePointer(
                            child: SourceLink(
                              bubbleKey: _bubbleKey,
                              cardKey: _cardKey,
                              paneKey: _paneKey,
                              wide: wide,
                              scrollController: _scroll,
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// 顶栏裁决条（参考图底部 删除/添加 按用户令上移；计数 i/n + 翻页）。
class _VerdictBar extends StatelessWidget {
  const _VerdictBar({
    required this.index,
    required this.count,
    required this.busy,
    required this.onReject,
    required this.onAdd,
    required this.onPrev,
    required this.onNext,
  });

  final int index;
  final int count;
  final bool busy;
  final VoidCallback onReject;
  final VoidCallback onAdd;
  final VoidCallback onPrev;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final tokens = WhaleTheme.tokensOf(context);
    return Container(
      color: tokens.surface,
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      child: Row(
        children: [
          TextButton(
            onPressed: busy ? null : onReject,
            child: Text('删除', style: TextStyle(color: tokens.of(WhaleTone.coral))),
          ),
          Expanded(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                    onPressed: onPrev,
                    icon: const Icon(Icons.chevron_left),
                    tooltip: '上一条候选'),
                Text('${index + 1} / $count'),
                IconButton(
                    onPressed: onNext,
                    icon: const Icon(Icons.chevron_right),
                    tooltip: '下一条候选'),
              ],
            ),
          ),
          FilledButton(
            onPressed: busy ? null : onAdd,
            child: const Text('添加'),
          ),
        ],
      ),
    );
  }
}

/// 左半：聊天来源（高亮被提取消息；±10 上下文）。
class _SourcePane extends StatelessWidget {
  const _SourcePane({
    super.key,
    required this.entry,
    required this.messages,
    required this.loading,
    required this.error,
    required this.scroll,
    required this.bubbleKey,
    required this.onRetry,
  });

  final ScheduleEntry entry;
  final List<SourceMessage>? messages;
  final bool loading;
  final String? error;
  final ScrollController scroll;
  final GlobalKey bubbleKey;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final tokens = WhaleTheme.tokensOf(context);
    final src = entry.source;
    if (src == null) {
      return Center(
          child: Text('该候选无来源消息', style: TextStyle(color: tokens.ink2)));
    }
    if (loading) return const Center(child: CircularProgressIndicator());
    if (error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('来源加载失败：$error', style: TextStyle(color: tokens.ink2)),
            TextButton(onPressed: onRetry, child: const Text('重试')),
          ],
        ),
      );
    }
    final list = messages ?? const <SourceMessage>[];
    return ListView.builder(
      controller: scroll,
      padding: const EdgeInsets.all(10),
      itemCount: list.length,
      itemBuilder: (context, i) {
        final m = list[i];
        final hit = m.messageId == src.messageId;
        return Container(
          key: hit ? bubbleKey : null,
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
          decoration: BoxDecoration(
            color: hit ? tokens.softOf(WhaleTone.aqua) : tokens.surface,
            borderRadius: BorderRadius.circular(12),
            border: hit
                ? Border(
                    left: BorderSide(color: tokens.of(WhaleTone.aqua), width: 3))
                : null,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(m.sender,
                  style: TextStyle(color: tokens.ink3, fontSize: 11)),
              const SizedBox(height: 2),
              Text(m.text),
            ],
          ),
        );
      },
    );
  }
}

/// 右半：候选卡片（标题 / 日期时间 / 来源行）。
class _CardPane extends StatelessWidget {
  const _CardPane({required this.cardKey, required this.entry});

  /// 连线端点锚（挂在 Card 上而非整 pane，连线落在卡片缘）。
  final GlobalKey cardKey;
  final ScheduleEntry entry;

  @override
  Widget build(BuildContext context) {
    final tokens = WhaleTheme.tokensOf(context);
    final src = entry.source;
    return Center(
      child: Card(
        key: cardKey,
        margin: const EdgeInsets.all(16),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(entry.title,
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 10),
              Text(
                  '${entry.date}${entry.time == null ? '' : ' ${entry.time}'}',
                  style: TextStyle(color: tokens.ink2)),
              if (src != null) ...[
                const SizedBox(height: 10),
                Text('来源：${src.conversation}',
                    style: TextStyle(color: tokens.ink2, fontSize: 12)),
                Text('「${src.excerpt}」',
                    style: TextStyle(
                        color: tokens.ink3,
                        fontSize: 12,
                        fontStyle: FontStyle.italic)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
