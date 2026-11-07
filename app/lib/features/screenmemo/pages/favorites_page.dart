import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/favorites_repository.dart';

/// 收藏页（Phase 3）：收藏的截图与动态段。
class FavoritesPage extends StatefulWidget {
  const FavoritesPage({super.key, this.repository});

  /// 测试注入点（为 null 时自建真实实现）。
  final FavoritesRepository? repository;

  @override
  State<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends State<FavoritesPage> {
  late final FavoritesRepository _repo =
      widget.repository ?? FavoritesRepository();

  List<FavoriteEntry>? _items;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final items = await _repo.list();
      if (!mounted) return;
      setState(() => _items = items);
    } on MissingPluginException {
      if (mounted) setState(() => _error = '收藏暂不可用（原生接口未就绪）');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = '加载失败：${e.message}');
    }
  }

  Future<void> _remove(FavoriteEntry e) async {
    try {
      await _repo.remove(captureId: e.captureId, segmentId: e.segmentId);
      await _load();
    } on PlatformException catch (err) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('取消收藏失败：${err.message}')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('收藏')),
      body: RefreshIndicator(onRefresh: _load, child: _buildBody()),
    );
  }

  Widget _buildBody() {
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_error!),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    final items = _items;
    if (items == null) return const Center(child: CircularProgressIndicator());
    if (items.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: const [
          SizedBox(height: 120),
          Center(child: Text('暂无收藏')),
        ],
      );
    }
    return ListView.builder(
      itemCount: items.length,
      itemBuilder: (context, i) {
        final e = items[i];
        final time = DateTime.fromMillisecondsSinceEpoch(e.createdAt)
            .toLocal()
            .toString()
            .substring(0, 16);
        return ListTile(
          leading: Icon(e.isSegment ? Icons.burst_mode : Icons.image_outlined),
          title: Text(e.isSegment
              ? '动态段 ${e.segmentId}'
              : '截图 #${e.captureId}'),
          subtitle: Text(
              '${e.note?.isNotEmpty == true ? '${e.note}\n' : ''}收藏于 $time'),
          trailing: IconButton(
            tooltip: '取消收藏',
            icon: const Icon(Icons.star),
            onPressed: () => _remove(e),
          ),
        );
      },
    );
  }
}
