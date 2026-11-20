import 'package:flutter/material.dart';

import '../../../theme/whale_theme.dart';
import '../controllers/schedule_controller.dart';
import '../models/schedule_entry.dart';
import '../services/mock_schedule_repository.dart';
import '../services/schedule_repository.dart';
import 'schedule_review_page.dart';

/// 日程页（增量添加，不碰旧页/旧导航）：待裁决候选 + 正式日程两区。
///
/// - 候选卡：标题 + 日期时间 + 来源行 + 待裁决标；点开进分屏裁决页。
/// - 正式卡：长按 → 确认 → DELETE /items/{id}（幂等）。
/// - AppBar「+」：手动建（POST /items，标题+日期+可选时间最小表单）。
class SchedulePage extends StatefulWidget {
  const SchedulePage({super.key, ScheduleRepository? repository})
      // ignore: prefer_initializing_formals —— 命名参数须保持公开名 repository（跨库调用），字段才是私有的。
      : _repository = repository;

  final ScheduleRepository? _repository;

  @override
  State<SchedulePage> createState() => _SchedulePageState();
}

class _SchedulePageState extends State<SchedulePage> {
  late final ScheduleController controller =
      ScheduleController(widget._repository ?? MockScheduleRepository());

  @override
  void initState() {
    super.initState();
    controller.load();
  }

  @override
  Widget build(BuildContext context) {
    final tokens = WhaleTheme.tokensOf(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('日程'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: '手动建日程',
            onPressed: _createDialog,
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          switch (controller.phase) {
            case SchedulePhase.loading:
              return const Center(child: CircularProgressIndicator());
            case SchedulePhase.error:
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('加载失败：${controller.error ?? ""}',
                        style: TextStyle(color: tokens.ink2)),
                    FilledButton(
                        onPressed: controller.load, child: const Text('重试')),
                  ],
                ),
              );
            case SchedulePhase.ready:
              return _list(context, tokens);
          }
        },
      ),
    );
  }

  Widget _list(BuildContext context, WhaleTokens tokens) {
    final cs = controller.candidates;
    final items = controller.items;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        _header('待裁决候选（${cs.length}）', tokens),
        if (cs.isEmpty)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text('暂无候选', style: TextStyle(color: tokens.ink3)),
          ),
        for (final e in cs) _candidateCard(e, tokens),
        const SizedBox(height: 16),
        _header('正式日程（${items.length}）', tokens),
        if (items.isEmpty)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text('暂无日程', style: TextStyle(color: tokens.ink3)),
          ),
        for (final e in items) _itemCard(e, tokens),
      ],
    );
  }

  Widget _header(String text, WhaleTokens tokens) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
        child: Text(text,
            style: TextStyle(color: tokens.ink2, fontSize: 13)),
      );

  Widget _candidateCard(ScheduleEntry e, WhaleTokens tokens) {
    final failure = controller.failureOf(e.id);
    return Card(
      child: ListTile(
        title: Text(e.title),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_when(e)),
            if (e.source != null)
              Text('来源：${e.source!.conversation} · ${e.source!.excerpt}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: tokens.ink3, fontSize: 12)),
            if (failure != null)
              Text('上次操作未成功：$failure（可重试）',
                  style:
                      TextStyle(color: tokens.of(WhaleTone.coral), fontSize: 12)),
          ],
        ),
        trailing: _chip('待裁决', WhaleTone.amber, tokens),
        onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => ScheduleReviewPage(
              controller: controller, candidateId: e.id),
        )),
      ),
    );
  }

  Widget _itemCard(ScheduleEntry e, WhaleTokens tokens) {
    final failure = controller.failureOf(e.id);
    return Card(
      child: ListTile(
        title: Text(e.title),
        subtitle: failure == null
            ? Text(_when(e))
            : Text('删除未成功：$failure（可重试）',
                style: TextStyle(color: tokens.of(WhaleTone.coral), fontSize: 12)),
        onLongPress: () => _confirmDelete(e),
      ),
    );
  }

  Widget _chip(String text, WhaleTone tone, WhaleTokens tokens) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: tokens.softOf(tone),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Text(text,
            style: TextStyle(color: tokens.of(tone), fontSize: 11)),
      );

  String _when(ScheduleEntry e) =>
      '${e.date}${e.time == null ? '' : ' ${e.time}'}';

  Future<void> _confirmDelete(ScheduleEntry e) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除该日程？'),
        content: Text(e.title),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (yes == true) await controller.deleteItem(e.id);
  }

  Future<void> _createDialog() async {
    final title = TextEditingController();
    final date = TextEditingController();
    final time = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('手动建日程'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
                controller: title,
                decoration: const InputDecoration(labelText: '标题')),
            TextField(
                controller: date,
                decoration: const InputDecoration(labelText: '日期（2026-11-20）')),
            TextField(
                controller: time,
                decoration: const InputDecoration(labelText: '时间（可选，18:00）')),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('保存')),
        ],
      ),
    );
    if (ok != true) return;
    final t = title.text.trim();
    final d = date.text.trim();
    if (t.isEmpty || d.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('标题与日期必填；未创建')));
      }
      return;
    }
    await controller.createItem(
        title: t, date: d, time: time.text.trim().isEmpty ? null : time.text.trim());
  }
}
