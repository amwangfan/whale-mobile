import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/capture_repository.dart';
import 'favorites_page.dart';
import 'search_page.dart';
import 'storage_page.dart';

/// 感知时间线页（Phase 3）：按时间倒序展示动态段（连续同应用屏幕片段聚合）。
///
/// 数据来自本地 CaptureStore（owner_sensitive，不出设备）。
class TimelinePage extends StatefulWidget {
  const TimelinePage({super.key, this.repository});

  /// 测试注入点（为 null 时自建真实实现）。
  final CaptureRepository? repository;

  @override
  State<TimelinePage> createState() => _TimelinePageState();
}

class _TimelinePageState extends State<TimelinePage> {
  late final CaptureRepository _repo = widget.repository ?? CaptureRepository();

  List<ScreenSegment>? _segments;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final segs = await _repo.listSegments(limit: 100);
      if (!mounted) return;
      setState(() => _segments = segs);
    } on MissingPluginException {
      // 原生侧尚未注册通道方法（开发期/单测环境）：显式展示，不伪装为空数据
      if (mounted) setState(() => _error = '采集存储暂不可用（原生接口未就绪）');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = '加载失败：${e.message}');
    }
  }

  String _timeRange(ScreenSegment s) {
    String fmt(int ms) {
      final t = DateTime.fromMillisecondsSinceEpoch(ms).toLocal();
      return '${t.hour.toString().padLeft(2, '0')}:'
          '${t.minute.toString().padLeft(2, '0')}';
    }

    final day = DateTime.fromMillisecondsSinceEpoch(s.startedAt).toLocal();
    return '${day.month}-${day.day} ${fmt(s.startedAt)}–${fmt(s.endedAt)}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('感知'),
        actions: [
          IconButton(
            tooltip: '搜索',
            icon: const Icon(Icons.search),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => const SearchPage(),
            )),
          ),
          IconButton(
            tooltip: '收藏',
            icon: const Icon(Icons.star_outline),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => const FavoritesPage(),
            )),
          ),
          IconButton(
            tooltip: '存储管理',
            icon: const Icon(Icons.storage),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => const StoragePage(),
            )),
          ),
        ],
      ),
      body: RefreshIndicator(onRefresh: _load, child: _buildBody()),
    );
  }

  Widget _buildBody() {
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48, color: Colors.grey),
            const SizedBox(height: 8),
            Text(_error!),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    final segs = _segments;
    if (segs == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (segs.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: const [
          SizedBox(height: 120),
          Center(child: Text('暂无屏幕记忆\n（在「我的 → 模块开关」开启影子采集持久化）',
              textAlign: TextAlign.center)),
        ],
      );
    }
    return ListView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      itemCount: segs.length,
      itemBuilder: (context, i) {
        final s = segs[i];
        return ListTile(
          leading: const Icon(Icons.burst_mode),
          title: Text(s.summary?.isNotEmpty == true
              ? s.summary!
              : '${s.packageName} · ${s.memberCount} 张'),
          subtitle: Text(
              '${_timeRange(s)} · ${s.memberCount} 张${s.closed ? '' : ' · 进行中'}'),
          onTap: () => _showSegment(s),
        );
      },
    );
  }

  /// 段详情：成员截图数与证据引用（截图本体经原生证据读取，本版展示元数据）。
  void _showSegment(ScreenSegment s) {
    showModalBottomSheet<void>(
      context: context,
      builder: (context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(s.packageName,
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text('时间：${_timeRange(s)}'),
              Text('片段数：${s.memberCount}'),
              if (s.summary != null && s.summary!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text('摘要：${s.summary}'),
                ),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text('证据引用：${s.evidenceRef}',
                    style: Theme.of(context).textTheme.bodySmall),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
