import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/capture_repository.dart';

/// 存储管理页（Phase 3）：查看占用、压缩、清理、备份导出入口。
class StoragePage extends StatefulWidget {
  const StoragePage({super.key, this.repository});

  /// 测试注入点（为 null 时自建真实实现）。
  final CaptureRepository? repository;

  @override
  State<StoragePage> createState() => _StoragePageState();
}

class _StoragePageState extends State<StoragePage> {
  late final CaptureRepository _repo =
      widget.repository ?? CaptureRepository();

  StorageStats? _stats;
  String? _error;
  bool _busy = false;
  String _lastReport = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final stats = await _repo.storageStats();
      if (!mounted) return;
      setState(() => _stats = stats);
    } on MissingPluginException {
      if (mounted) setState(() => _error = '存储统计暂不可用（原生接口未就绪）');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = '加载失败：${e.message}');
    }
  }

  /// 压缩 30 天前的图片（缩放重编码；收藏受保护由原生侧保证）。
  Future<void> _compress() async {
    final cutoff = DateTime.now()
        .subtract(const Duration(days: 30))
        .millisecondsSinceEpoch;
    setState(() {
      _busy = true;
      _lastReport = '';
    });
    try {
      final r = await _repo.compressImagesBefore(cutoff);
      if (!mounted) return;
      setState(() => _lastReport =
          '已压缩 ${r.filesCompressed} 张图片，节省 ${(r.bytesSaved / 1024 / 1024).toStringAsFixed(1)} MB');
      await _load();
    } on PlatformException catch (e) {
      if (mounted) setState(() => _lastReport = '压缩失败：${e.message}');
    } on MissingPluginException {
      if (mounted) setState(() => _lastReport = '压缩暂不可用（原生接口未就绪）');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 清理 N 天前的记录（默认 90 天；收藏保留）。
  Future<void> _purge() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清理旧记录'),
        content: const Text('将删除 90 天前的截图与 OCR 记录（收藏保留）。确定继续？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('清理'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final cutoff = DateTime.now()
        .subtract(const Duration(days: 90))
        .millisecondsSinceEpoch;
    setState(() {
      _busy = true;
      _lastReport = '';
    });
    try {
      final r = await _repo.purgeBefore(cutoff);
      if (!mounted) return;
      setState(() => _lastReport =
          '已删除 ${r.rowsDeleted} 条记录 / ${r.filesDeleted} 个文件，释放 ${(r.bytesFreed / 1024 / 1024).toStringAsFixed(1)} MB（保留收藏 ${r.favoritesKept}）');
      await _load();
    } on PlatformException catch (e) {
      if (mounted) setState(() => _lastReport = '清理失败：${e.message}');
    } on MissingPluginException {
      if (mounted) setState(() => _lastReport = '清理暂不可用（原生接口未就绪）');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('存储管理')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_error != null)
            Card(
              color: Theme.of(context).colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_error!),
              ),
            ),
          if (_stats != null) _statsCard(_stats!),
          if (_stats == null && _error == null)
            const Center(child: CircularProgressIndicator()),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _busy ? null : _compress,
            icon: const Icon(Icons.compress),
            label: const Text('压缩 30 天前的图片'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy ? null : _purge,
            icon: const Icon(Icons.delete_outline),
            label: const Text('清理 90 天前的记录'),
          ),
          if (_busy)
            const Padding(
              padding: EdgeInsets.all(12),
              child: Center(child: CircularProgressIndicator()),
            ),
          if (_lastReport.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(_lastReport),
            ),
        ],
      ),
    );
  }

  Widget _statsCard(StorageStats s) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('本地占用', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            _row('截图记录', '${s.captures}'),
            _row('动态段', '${s.segments}'),
            _row('收藏', '${s.favorites}'),
            _row('备注', '${s.notes}'),
            _row('图片文件', '${s.imageFiles} 个 · ${s.imageBytesLabel}'),
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          SizedBox(
              width: 96,
              child: Text(label, style: const TextStyle(color: Colors.grey))),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}
