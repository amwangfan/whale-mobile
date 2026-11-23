import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../../navigation/app_navigation.dart';
import '../../../transport/link/link_client.dart';
import '../../../transport/link/ulid.dart';
import '../models/conversation.dart';
import '../services/link_chat_api.dart';
import '../services/link_session.dart';
import 'approvals_page.dart';
import 'chat_page.dart';

/// 会话列表页（v3 统一主干：`conversations.list` 经 Link RPC）。
///
/// 连接生命周期（M2/O1）：聊天 tab 可见且应用前台时持有 Link 连接，
/// 切走/退后台释放（通知永不丢走设备 WS）。
/// 新建会话：客户端生成 `mobile:ULID`，首条消息隐式创建；标题可在
/// 会话菜单改（conversations.update）。删除会话：conversations.delete。
class ConversationListPage extends StatefulWidget {
  const ConversationListPage({super.key, this.session});

  /// 测试注入点（默认全局 LinkSession.instance）。
  final LinkSession? session;

  @override
  State<ConversationListPage> createState() => _ConversationListState();
}

class _ConversationListState extends State<ConversationListPage>
    with WidgetsBindingObserver {
  LinkSession get _session => widget.session ?? LinkSession.instance;

  final List<Conversation> _items = <Conversation>[];
  final ScrollController _scroll = ScrollController();

  bool _loading = false;
  String? _error;
  bool _holding = false;
  StreamSubscription<LinkEvent>? _notifySub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    AppNavigation.tabIndex.addListener(_syncHold);
    _session.addListener(_onSession);
    // 首帧纪律：Link 建连一律推后一帧——聊天面首帧（本页 skeleton）先于
    // 一切连接工作绘制，连接（WS/TLS/SharedPreferences）不参与首帧路径。
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncHold());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    AppNavigation.tabIndex.removeListener(_syncHold);
    _session.removeListener(_onSession);
    _notifySub?.cancel();
    _releaseHold();
    _scroll.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _syncHold();
  }

  /// 按需连接：聊天 tab 可见（index 0）且应用前台 → acquire；否则 release。
  void _syncHold() {
    final visible = AppNavigation.tabIndex.value == 0;
    final resumed = WidgetsBinding.instance.lifecycleState ==
        AppLifecycleState.resumed;
    if (visible && resumed && !_holding) {
      _holding = true;
      _session.acquire();
    } else if ((!visible || !resumed) && _holding) {
      _releaseHold();
    }
  }

  void _releaseHold() {
    if (!_holding) return;
    _holding = false;
    _session.release();
  }

  void _onSession() {
    final api = _session.api;
    if (api != null) {
      _notifySub ??= api.events
          .where((e) => e.topic == 'notify')
          .listen(_onNotify);
      // 连接就绪/重连后刷新列表
      _load();
    }
    if (!mounted) return;
    // 会话通知可能落在 build 阶段（连接事件驱动）：build 中不直接 setState。
    if (SchedulerBinding.instance.schedulerPhase != SchedulerPhase.idle) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() {});
      });
    } else {
      setState(() {});
    }
  }

  /// notify 事件（服务端主动消息）：聊天页活跃时以 SnackBar 呈现。
  /// 通知永不丢走设备 WS（既有不动），这里只是 Link 在线时的即时展示。
  void _onNotify(LinkEvent ev) {
    if (!mounted) return;
    final body = ev.data['body'] as String? ?? '';
    final title = ev.data['title'] as String? ?? '';
    if (body.isEmpty && title.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(title.isEmpty ? body : '$title：$body')),
    );
  }

  Future<void> _load() async {
    final api = _session.api;
    if (api == null || _loading) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final items = await api.listConversations(limit: 100);
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(items);
      });
    } on LinkError catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
    if (mounted) setState(() => _loading = false);
  }

  /// 新建会话：mobile:<ULID>，进入聊天页；标题由首条消息自动落
  /// （或经会话菜单 conversations.update 改）。
  Future<void> _newConversation() async {
    final cid = 'mobile:${newUlid()}';
    await _openChat(cid, '');
  }

  Future<void> _openChat(String conversationId, String title) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ChatPage(conversationId: conversationId, title: title),
      ),
    );
    _load(); // 返回后刷新列表（预览/排序变化）
  }

  /// 会话菜单：改标题 / 删除（conversations.update/delete）。
  Future<void> _conversationMenu(Conversation c) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.edit),
              title: const Text('改标题'),
              onTap: () => Navigator.of(context).pop('rename'),
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text('删除会话'),
              onTap: () => Navigator.of(context).pop('delete'),
            ),
          ],
        ),
      ),
    );
    if (!mounted || action == null) return;
    final api = _session.api;
    if (api == null) return;
    switch (action) {
      case 'rename':
        await _rename(api, c);
        break;
      case 'delete':
        await _delete(api, c);
        break;
    }
  }

  Future<void> _rename(ChatBackend api, Conversation c) async {
    final controller = TextEditingController(text: c.title);
    final title = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('改标题'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(hintText: '会话标题'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (!mounted || title == null || title.isEmpty) return;
    try {
      await api.updateConversation(c.conversationId, title: title);
      _load();
    } on LinkError catch (e) {
      _showErr('改标题失败：$e');
    }
  }

  Future<void> _delete(ChatBackend api, Conversation c) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除会话'),
        content: Text('删除「${c.displayName}」及其全部消息？不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(context).colorScheme.error),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (!mounted || ok != true) return;
    try {
      await api.deleteConversation(c.conversationId);
      _load();
    } on LinkError catch (e) {
      _showErr('删除失败：$e');
    }
  }

  void _showErr(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  void _openApprovals() {
    final approvals = _session.approvals;
    if (approvals == null) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ApprovalsPage(controller: approvals),
      ),
    );
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
    final approvals = _session.approvals;
    return Scaffold(
      appBar: AppBar(
        title: const Text('聊天'),
        actions: [
          ListenableBuilder(
            listenable: _session,
            builder: (context, _) {
              final n = approvals?.pending.length ?? 0;
              return IconButton(
                tooltip: '审批',
                icon: Badge(
                  isLabelVisible: n > 0,
                  label: Text('$n'),
                  child: const Icon(Icons.gpp_maybe),
                ),
                onPressed: _openApprovals,
              );
            },
          ),
        ],
      ),
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
    final session = _session;
    if (session.error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 48, color: Colors.grey),
              const SizedBox(height: 8),
              Text(session.error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              FilledButton(
                  onPressed: session.reconnect, child: const Text('重连')),
            ],
          ),
        ),
      );
    }
    if (session.api == null || (_loading && _items.isEmpty)) {
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
      itemCount: _items.length,
      itemBuilder: (context, i) {
        final c = _items[i];
        return ListTile(
          leading: CircleAvatar(
            child: Text(
                c.displayName.isNotEmpty ? c.displayName.characters.first : '?'),
          ),
          title: Text(c.displayName),
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
              Text(c.channel, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
          onTap: () => _openChat(c.conversationId, c.title),
          onLongPress: () => _conversationMenu(c),
        );
      },
    );
  }

  String _preview(Conversation c) {
    final text = c.previewText;
    if (text == null || text.isEmpty) return '（暂无消息）';
    final prefix = c.previewRole == 'user' ? '我：' : '';
    return prefix + text;
  }
}
