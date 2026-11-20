import '../models/schedule_entry.dart';
import 'schedule_repository.dart';

/// 离线 mock（mock 先行，契约形状锚定云端最终版；HTTP 实现对齐后整体替换）。
///
/// 文案仅用参考图例句与通用占位，零真实数据入仓。列表在内存中可变，
/// add/reject/delete 后重拉可见——模拟服务端的幂等写。
class MockScheduleRepository implements ScheduleRepository {
  MockScheduleRepository({this.delay = Duration.zero});

  /// 网络感模拟（默认零延迟，测试友好；UI 手验可调 300ms）。
  final Duration delay;

  /// 测试开关：写操作一律抛错（验证失败如实+手动重试路径）。
  bool failWrites = false;

  /// 测试观测口：当前正式列表快照。
  List<ScheduleEntry> snapshotItems() => List.unmodifiable(_items);

  final List<ScheduleEntry> _candidates = [
    const ScheduleEntry(
      id: 'c1',
      title: '天黑一点前往湖边测试 4K 30 帧视频录制效果',
      date: '2026-11-19',
      state: 'pending',
      createdAt: 1794052800,
      source: ScheduleSource(
        conversation: 'AI 开销讨论',
        messageId: 'm-1001',
        at: 1794052800,
        excerpt: '天黑一点前往湖边测试4K30帧视频录制效果',
      ),
    ),
    const ScheduleEntry(
      id: 'c2',
      title: '周六前把演示视频旁白稿过一遍',
      date: '2026-11-21',
      time: '18:00',
      state: 'pending',
      createdAt: 1794056400,
      source: ScheduleSource(
        conversation: '演示筹备群',
        messageId: 'm-2001',
        at: 1794056400,
        excerpt: '旁白稿周六前过一遍',
      ),
    ),
    // 合成去重样例：与正式日程 i1 同源（messageId 相同）→ 合并渲染时不得双卡。
    const ScheduleEntry(
      id: 'c3',
      title: '智能眼镜首测（重复来源）',
      date: '2026-11-19',
      state: 'pending',
      createdAt: 1794060000,
      source: ScheduleSource(
        conversation: '演示筹备群',
        messageId: 'm-9001',
        at: 1794060000,
        excerpt: '眼镜首测',
      ),
    ),
  ];

  final List<ScheduleEntry> _items = [
    const ScheduleEntry(
      id: 'i1',
      title: '湖边调试：智能眼镜首测见真章',
      date: '2026-11-19',
      state: 'added',
      createdAt: 1794040000,
      source: ScheduleSource(
        conversation: '演示筹备群',
        messageId: 'm-9001',
        at: 1794040000,
        excerpt: '湖边调试见真章',
      ),
    ),
    const ScheduleEntry(
      id: 'i2',
      title: '给妈妈回电话',
      date: '2026-11-20',
      state: 'added',
      createdAt: 1794041000,
    ),
  ];

  @override
  Future<List<ScheduleEntry>> fetchCandidates() async {
    await Future<void>.delayed(delay);
    return List.unmodifiable(_candidates);
  }

  @override
  Future<List<ScheduleEntry>> fetchItems() async {
    await Future<void>.delayed(delay);
    return List.unmodifiable(_items);
  }

  @override
  Future<void> addCandidate(String id) async {
    await Future<void>.delayed(delay);
    if (failWrites) throw StateError('mock write failure');
    final i = _candidates.indexWhere((e) => e.id == id);
    if (i < 0) return; // 幂等：已不在候选即视为已成功
    final e = _candidates.removeAt(i);
    if (_items.any((x) => x.id == e.id)) return; // 幂等：已在正式列表不重复落
    _items.add(ScheduleEntry(
      id: e.id,
      title: e.title,
      date: e.date,
      time: e.time,
      source: e.source,
      state: 'added',
      createdAt: e.createdAt,
    ));
  }

  @override
  Future<void> rejectCandidate(String id) async {
    await Future<void>.delayed(delay);
    if (failWrites) throw StateError('mock write failure');
    _candidates.removeWhere((e) => e.id == id); // 幂等：不在候选即成功
  }

  @override
  Future<ScheduleEntry> createItem({
    required String title,
    required String date,
    String? time,
  }) async {
    await Future<void>.delayed(delay);
    if (failWrites) throw StateError('mock write failure');
    final entry = ScheduleEntry(
      id: 'm${_items.length + 100}',
      title: title,
      date: date,
      time: time,
      state: 'added',
      createdAt: DateTime.now().millisecondsSinceEpoch ~/ 1000,
    );
    _items.add(entry);
    return entry;
  }

  @override
  Future<void> deleteItem(String id) async {
    await Future<void>.delayed(delay);
    if (failWrites) throw StateError('mock write failure');
    _items.removeWhere((e) => e.id == id); // 幂等：不在即成功
  }

  @override
  Future<List<SourceMessage>> fetchSource({
    required String conversation,
    required String messageId,
  }) async {
    await Future<void>.delayed(delay);
    // ±10 条上下文（契约：owner-only）；被提取消息居中，文本即候选摘录。
    final anchor = int.tryParse(messageId.replaceAll('m-', '')) ?? 0;
    if (anchor == 0) return const [];
    String? excerpt;
    for (final e in [..._candidates, ..._items]) {
      if (e.source?.messageId == messageId) {
        excerpt = e.source!.excerpt;
        break;
      }
    }
    final list = <SourceMessage>[];
    for (var i = -5; i <= 5; i++) {
      final id = 'm-${anchor + i}';
      list.add(SourceMessage(
        messageId: id,
        at: 1794052800 + i * 120,
        sender: i.isEven ? '我' : 'Tob',
        text: i == 0
            ? (excerpt ?? '（被提取的来源消息）')
            : '上下文联播消息 ${i >= 0 ? '+' : ''}$i（$conversation）',
      ));
    }
    return list;
  }
}
