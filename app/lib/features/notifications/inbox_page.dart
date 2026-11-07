import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 通知收件箱（修正方案 N2 Flutter 侧）。
///
/// 数据来自原生 NotificationInbox（SQLite 本地历史），经 `whale/transport`
/// 通道读取。
///
/// **通道约定（新约定）**——原生侧需在 TransportBridge 注册：
/// - `notificationInbox.history` {limit?, offset?} → List<Map>
///   （字段：event_id/notification_id/category/title/body/status/
///   created_at/expires_at/target_json?/received_at/updated_at/read）
/// - `notificationInbox.markRead` {notification_id} → {updated: int}
/// - `notificationInbox.markAllRead` → {updated: int}
/// - `notificationInbox.count` → {count: int}
///
/// 撤回（notification.revoke，协议 §13.3）落库为 status=revoked，UI 展示
/// 「已撤回」样式而不删除记录。
class InboxPage extends StatefulWidget {
  const InboxPage({super.key, MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;

  @override
  State<InboxPage> createState() => _InboxPageState();
}

/// 收件箱记录（NotificationInbox.Record 的 Flutter 视图）。
class InboxRecord {
  const InboxRecord({
    required this.eventId,
    required this.notificationId,
    required this.category,
    required this.title,
    required this.body,
    required this.status,
    required this.createdAt,
    this.receivedAt = 0,
    this.read = false,
  });

  final String eventId;
  final String notificationId;
  final String category;
  final String title;
  final String body;

  /// received/displayed/opened/dismissed/action_submitted/expired/failed/
  /// revoked（revoked 为本地扩展：core 撤回）。
  final String status;
  final int createdAt;
  final int receivedAt;
  final bool read;

  bool get isRevoked => status == 'revoked';

  factory InboxRecord.fromMap(Map<String, dynamic> m) => InboxRecord(
        eventId: m['event_id'] as String? ?? '',
        notificationId: m['notification_id'] as String? ?? '',
        category: m['category'] as String? ?? '',
        title: m['title'] as String? ?? '',
        body: m['body'] as String? ?? '',
        status: m['status'] as String? ?? 'received',
        createdAt: (m['created_at'] as num?)?.toInt() ?? 0,
        receivedAt: (m['received_at'] as num?)?.toInt() ?? 0,
        read: m['read'] as bool? ?? (m['read'] as num?) == 1,
      );
}

/// 分类过滤选项（协议 §13.1 category 枚举的分组）。
enum InboxFilter {
  all('全部', null),
  chat('聊天', {'chat.message'}),
  task('任务', {
    'task.started',
    'task.progress',
    'task.completed',
    'task.failed',
  }),
  approval('审批', {'approval.requested'}),
  device('设备', {'device.offline', 'device.online'}),
  other('其他', {'memory.updated', 'reminder.triggered'});

  const InboxFilter(this.label, this.categories);

  final String label;
  final Set<String>? categories;

  bool matches(String category) =>
      categories == null || categories!.contains(category);
}

class _InboxPageState extends State<InboxPage> {
  List<InboxRecord>? _records;
  String? _error;
  InboxFilter _filter = InboxFilter.all;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final res = await widget._channel.invokeMethod<List<dynamic>>(
        'notificationInbox.history',
        <String, dynamic>{'limit': 200},
      );
      if (!mounted) return;
      setState(() {
        _records = (res ?? const [])
            .whereType<Map>()
            .map((m) => InboxRecord.fromMap(m.cast<String, dynamic>()))
            .toList();
      });
    } on MissingPluginException {
      if (mounted) setState(() => _error = '通知收件箱暂不可用（原生接口未就绪）');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = '加载失败：${e.message}');
    }
  }

  Future<void> _markRead(InboxRecord r) async {
    if (r.read) return;
    try {
      await widget._channel.invokeMethod<void>(
        'notificationInbox.markRead',
        <String, dynamic>{'notification_id': r.notificationId},
      );
    } on MissingPluginException {
      /* 原生未就绪：仅本地视图更新 */
    }
    if (!mounted) return;
    setState(() {
      final i = _records?.indexOf(r) ?? -1;
      if (i >= 0) {
        _records![i] = InboxRecord(
          eventId: r.eventId,
          notificationId: r.notificationId,
          category: r.category,
          title: r.title,
          body: r.body,
          status: r.status,
          createdAt: r.createdAt,
          receivedAt: r.receivedAt,
          read: true,
        );
      }
    });
  }

  Future<void> _markAllRead() async {
    try {
      await widget._channel.invokeMethod('notificationInbox.markAllRead');
    } on MissingPluginException {/* 原生未就绪 */}
    await _load();
  }

  List<InboxRecord> get _filtered {
    final records = _records ?? const <InboxRecord>[];
    return records.where((r) => _filter.matches(r.category)).toList();
  }

  String _timeLabel(int ms) {
    if (ms == 0) return '';
    final t = DateTime.fromMillisecondsSinceEpoch(ms).toLocal();
    return '${t.month}-${t.day} '
        '${t.hour.toString().padLeft(2, '0')}:'
        '${t.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('通知'),
        actions: [
          IconButton(
            tooltip: '全部已读',
            icon: const Icon(Icons.done_all),
            onPressed: _markAllRead,
          ),
        ],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              children: [
                for (final f in InboxFilter.values)
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ChoiceChip(
                      label: Text(f.label),
                      selected: _filter == f,
                      onSelected: (_) => setState(() => _filter = f),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(child: _buildList()),
        ],
      ),
    );
  }

  Widget _buildList() {
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
    if (_records == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final items = _filtered;
    if (items.isEmpty) {
      return RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 120),
            Center(child: Text(_filter == InboxFilter.all ? '暂无通知' : '该分类暂无通知')),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        itemCount: items.length,
        itemBuilder: (context, i) {
          final r = items[i];
          return _recordTile(r);
        },
      ),
    );
  }

  Widget _recordTile(InboxRecord r) {
    final revoked = r.isRevoked;
    final titleStyle = TextStyle(
      fontWeight: r.read ? FontWeight.normal : FontWeight.bold,
      decoration: revoked ? TextDecoration.lineThrough : null,
      color: revoked ? Theme.of(context).colorScheme.outline : null,
    );
    return ListTile(
      leading: Icon(_categoryIcon(r.category),
          color: revoked ? Theme.of(context).colorScheme.outline : null),
      title: Text(r.title.isEmpty ? '（无标题）' : r.title, style: titleStyle),
      subtitle: Text(
        [
          if (revoked) '[已撤回] ',
          r.body,
          '\n${_timeLabel(r.receivedAt != 0 ? r.receivedAt : r.createdAt)}',
          if (r.status == 'failed') ' · 展示失败',
          if (r.status == 'expired') ' · 已过期',
        ].join(),
        maxLines: 3,
        overflow: TextOverflow.ellipsis,
        style: revoked
            ? TextStyle(color: Theme.of(context).colorScheme.outline)
            : null,
      ),
      isThreeLine: true,
      trailing: r.read
          ? null
          : const Icon(Icons.circle, size: 8, color: Colors.redAccent),
      onTap: () => _markRead(r),
    );
  }

  IconData _categoryIcon(String category) {
    if (InboxFilter.chat.categories!.contains(category)) return Icons.chat;
    if (InboxFilter.task.categories!.contains(category)) return Icons.task_alt;
    if (InboxFilter.approval.categories!.contains(category)) {
      return Icons.verified_user;
    }
    if (InboxFilter.device.categories!.contains(category)) {
      return Icons.smartphone;
    }
    return Icons.notifications_none;
  }
}
