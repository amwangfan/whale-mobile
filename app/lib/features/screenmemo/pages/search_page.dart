import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/capture_repository.dart';
import '../data/search_repository.dart';

/// 屏幕历史搜索页（Phase 3）：本地全文搜索（FTS5 + 中文 LIKE 回退）。
class SearchPage extends StatefulWidget {
  const SearchPage({super.key, this.repository});

  /// 测试注入点（为 null 时自建真实实现）。
  final SearchRepository? repository;

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  late final SearchRepository _repo = widget.repository ?? SearchRepository();

  final TextEditingController _query = TextEditingController();
  List<CaptureRecord>? _results;
  bool _searching = false;
  String? _error;

  Future<void> _search() async {
    final q = _query.text.trim();
    if (q.isEmpty) return;
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      final results = await _repo.search(q, limit: 50);
      if (!mounted) return;
      setState(() {
        _results = results;
        _searching = false;
      });
    } on MissingPluginException {
      if (mounted) {
        setState(() {
          _searching = false;
          _error = '搜索暂不可用（原生接口未就绪）';
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _searching = false;
          _error = '搜索失败：${e.message}';
        });
      }
    }
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _query,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: '搜索屏幕里的文字…',
            border: InputBorder.none,
          ),
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _search(),
        ),
        actions: [
          IconButton(
            tooltip: '搜索',
            icon: const Icon(Icons.search),
            onPressed: _search,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_searching) return const Center(child: CircularProgressIndicator());
    if (_error != null) return Center(child: Text(_error!));
    final results = _results;
    if (results == null) {
      return const Center(child: Text('输入关键词搜索 OCR 文本'));
    }
    if (results.isEmpty) {
      return const Center(child: Text('没有匹配的记录'));
    }
    return ListView.builder(
      itemCount: results.length,
      itemBuilder: (context, i) {
        final r = results[i];
        final time = DateTime.fromMillisecondsSinceEpoch(r.captureTime)
            .toLocal()
            .toString()
            .substring(0, 16);
        return ListTile(
          leading: const Icon(Icons.image_outlined),
          title: Text(r.appLabel?.isNotEmpty == true
              ? r.appLabel!
              : r.packageName),
          subtitle: Text(
            '${r.ocrText ?? "（无 OCR 文本）"}\n$time',
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
          ),
          isThreeLine: true,
        );
      },
    );
  }
}
