import 'package:flutter/material.dart';

import '../models/conversation.dart';
import '../services/chat_api.dart';
import 'chat_page.dart';

/// 会话列表页（Phase 4）。
///
/// 新建会话：Core 无显式 create 端点——会话在发送第一条消息时以
/// conversation_id 隐式创建；这里生成客户端会话 ID 进入聊天页即可。
/// 删除/恢复会话：Core v2 移动端面无删除端点，暂不提供（见开发报告遗留项）。
class ConversationListPage extends StatefulWidget {
  const ConversationListPage({super.key, this.api});

  /// 测试注入点（为 null 时自建真实实现）。
  final ChatApi? api;

  @override
  State<ConversationListPage> createState() => _ConversationListState();
}

class _ConversationListState extends State<ConversationListPage> {
  late final ChatApi _api = widget.api ?? ChatApi();
  final List<Conversation> _items = <Conversation>[];
  final ScrollController _scroll = ScrollController();

  bool _loading = false;
  bool _loadingMore = false;
  String? _error;
  String? _nextCursor;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_maybeLoadMore);
    _load();
  }

  @override
  void dispose() {
    _scroll.dispose();
    if (widget.api == null) _api.close();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await _api.listConversations();
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(page.conversations);
        _nextCursor = page.nextCursor;
      });
    } on ChatApiException catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
    if (mounted) setState(() => _loading = false);
  }

  void _maybeLoadMore() {
    if (_nextCursor == null || _loadingMore || _loading) return;
    if (_scroll.position.pixels >
        _scroll.position.maxScrollExtent - 200) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    final cursor = _nextCursor;
    if (cursor == null) return;
    setState(() => _loadingMore = true);
    try {
      final page = await _api.listConversations(cursor: cursor);
      if (!mounted) return;
      setState(() {
        final seen = _items.map((c) => c.conversationId).toSet();
        _items.addAll(
            page.conversations.where((c) => !seen.contains(c.conversationId)));
        _nextCursor = page.nextCursor;
      });
    } on ChatApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('加载更多失败：$e')));
      }
    }
    if (mounted) setState(() => _loadingMore = false);
  }

  /// 新建会话：输入会话名 → 生成 conversation_id 并进入聊天页。
  Future<void> _newConversation() async {
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建会话'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: '会话名',
            hintText: '例如：日常、工作',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.of(context).pop(controller.text.trim()),
            child: const Text('创建'),
          ),
        ],
      ),
    );
    if (!mounted || name == null || name.isEmpty) return;
    // conversation_id 白名单字符（服务端 [:160] 截断；保持可读）
    final slug = name.replaceAll(RegExp(r'[^0-9A-Za-z一-鿿:_-]'), '_');
    final cid = 'mobile:$slug';
    await _openChat(cid);
  }

  Future<void> _openChat(String conversationId) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ChatPage(conversationId: conversationId),
      ),
    );
    _load(); // 返回后刷新列表（预览/排序变化）
  }

  String _preview(Conversation c) {
    final text = c.lastMessageText;
    if (text == null || text.isEmpty) return '（暂无消息）';
    final prefix = c.lastMessageRole == 'user' ? '我：' : '';
    return prefix +
        (text.length > 60 ? '${text.substring(0, 60)}…' : text);
  }

  String _timeLabel(int? ms) {
    if (ms == null) return '';
    final t = DateTime.fromMillisecondsSinceEpoch(ms).toLocal();
    final now = DateTime.now();
    if (t.year == now.year && t.month == now.month && t.day == now.day) {
      return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    }
    return '${t.month}-${t.day}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('聊天')),
      floatingActionButton: FloatingActionButton(
        onPressed: _newConversation,
        tooltip: '新建会话',
        child: const Icon(Icons.add),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading && _items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null && _items.isEmpty) {
      // 网络异常显式可见 + 手动重试（不静默重试）
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48, color: Colors.grey),
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(_error!, textAlign: TextAlign.center),
            ),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (_items.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: const [
          SizedBox(height: 120),
          Center(child: Text('还没有会话，点右下角新建')),
        ],
      );
    }
    return ListView.builder(
      controller: _scroll,
      physics: const AlwaysScrollableScrollPhysics(),
      itemCount: _items.length + (_loadingMore ? 1 : 0),
      itemBuilder: (context, i) {
        if (i >= _items.length) {
          return const Padding(
            padding: EdgeInsets.all(16),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        final c = _items[i];
        return ListTile(
          leading: CircleAvatar(
            child: Text(c.conversationId.isNotEmpty
                ? c.conversationId
                    .replaceFirst('mobile:', '')
                    .characters
                    .first
                : '?'),
          ),
          title: Text(c.conversationId.replaceFirst('mobile:', '')),
          subtitle: Text(
            _preview(c),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          trailing: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(_timeLabel(c.updatedAt),
                  style: Theme.of(context).textTheme.bodySmall),
              if (c.personaId != null)
                Text('角色:${c.personaId}',
                    style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
          onTap: () => _openChat(c.conversationId),
        );
      },
    );
  }
}
