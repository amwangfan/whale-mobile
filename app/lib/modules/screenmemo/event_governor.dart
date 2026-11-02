/// EV-01（Issue #11）增量感知数据治理：预算、合并、去重、隐私过滤。
///
/// 纯逻辑（无平台依赖，可单测）。策略（全部默认开启，逐条可解释）：
/// - **过期丢弃**：occurred_at 早于 now-staleMs 的事件不补传（stale）；
/// - **隐私过滤**：App 标签命中默认拒绝关键词（输入法/锁屏/支付等）或
///   用户黑名单 → 丢弃（filtered）；用户白名单可豁免默认关键词；
/// - **时间窗合并**：同 App 同 kind 在 mergeWindowMs 内重复出现 → 丢弃
///   （merged；保留首条）；
/// - **simhash 去重**：同 App 正文 simhash 汉明距离 ≤ 阈值 → 丢弃
///   （simhash_dup；OCR 摘要几乎不变的刷屏抑制）；
/// - **隐私预算**：每 App 每小时 / 全局每小时 上限，超限丢弃（budget）。
///
/// 所有丢弃都计数（stats 可观测）；被丢弃事件的游标照常推进
/// （治理决定是终态，不补传）。
library;

/// 治理判决。
class GovernorDecision {
  const GovernorDecision.pass() : pass = true, reason = null;
  const GovernorDecision.drop(this.reason) : pass = false;

  final bool pass;

  /// 丢弃原因：stale / filtered / merged / simhash_dup / budget_app / budget_total
  final String? reason;
}

/// 输入事件（字段取自 poll_events 载荷）。
class GovernedEvent {
  const GovernedEvent({
    required this.id,
    required this.kind,
    required this.summary,
    required this.occurredAtMs,
  });

  final int id;
  final String kind;
  final String summary;
  final int occurredAtMs;

  /// 事件归属 App 标签（summary 约定前缀 "Label: 正文"；无冒号视为未知）。
  String get appLabel {
    final idx = summary.indexOf(':');
    if (idx <= 0) return '';
    return summary.substring(0, idx).trim();
  }

  /// 正文（冒号后部分）。
  String get body {
    final idx = summary.indexOf(':');
    if (idx < 0) return summary;
    return summary.substring(idx + 1).trim();
  }
}

class EventGovernor {
  EventGovernor({
    this.perAppPerHour = 12,
    this.totalPerHour = 120,
    this.mergeWindowMs = 60 * 1000,
    this.simhashThreshold = 4,
    this.staleMs = 10 * 60 * 1000,
    List<String>? extraDenyKeywords,
    List<String> userAllowLabels = const [],
    List<String> userDenyLabels = const [],
  })  : _denyKeywords = [...defaultDenyKeywords, ...?extraDenyKeywords],
        _userAllow = userAllowLabels.map((s) => s.trim()).where((s) => s.isNotEmpty).toList(),
        _userDeny = userDenyLabels.map((s) => s.isNotEmpty ? s : '').where((s) => s.isNotEmpty).toList();

  final int perAppPerHour;
  final int totalPerHour;
  final int mergeWindowMs;
  final int simhashThreshold;
  final int staleMs;

  /// 默认拒绝关键词（App 标签包含即过滤；输入法/锁屏/支付/钱包类）。
  static const List<String> defaultDenyKeywords = [
    '输入法', 'inputmethod', 'keyboard', '锁屏', 'lockscreen', 'keyguard',
    '支付', '钱包', 'wallet', 'alipay', '银行',
  ];

  final List<String> _denyKeywords;
  final List<String> _userAllow;
  final List<String> _userDeny;

  // ---- 运行态 ----
  final Map<String, List<int>> _appTimestamps = {};
  final List<int> _totalTimestamps = [];
  final Map<String, int> _lastSimhash = {};
  final Map<String, int> _lastAt = {};
  final Map<String, String> _lastKind = {};
  final Map<String, int> _stats = {};

  /// 治理统计（reason → count；passed 为通过数）。
  Map<String, int> get stats => Map.unmodifiable(_stats);

  GovernorDecision decide(GovernedEvent e, int nowMs) {
    // 1. 过期不补传
    if (e.occurredAtMs < nowMs - staleMs) return _drop('stale');

    // 2. 隐私过滤（用户白名单可豁免默认关键词；用户黑名单强制）
    final label = e.appLabel;
    if (label.isNotEmpty) {
      final lower = label.toLowerCase();
      if (_userDeny.any((d) => lower.contains(d.toLowerCase()))) {
        return _drop('filtered_user_deny');
      }
      final allowed = _userAllow.any((a) => lower.contains(a.toLowerCase()));
      if (!allowed &&
          _denyKeywords.any((k) => lower.contains(k.toLowerCase()))) {
        return _drop('filtered');
      }
    }

    // 3. 时间窗合并（同 App 同 kind）
    final lastAt = _lastAt[label];
    if (lastAt != null &&
        _lastKind[label] == e.kind &&
        e.occurredAtMs - lastAt < mergeWindowMs) {
      return _drop('merged');
    }

    // 4. simhash 去重（同 App 正文近重复）
    final body = e.body;
    if (body.isNotEmpty) {
      final sh = simhash64(body);
      final prev = _lastSimhash[label];
      if (prev != null && hamming64(prev, sh) <= simhashThreshold) {
        return _drop('simhash_dup');
      }
    }

    // 5. 隐私预算（滑动 1h 窗口）
    _prune(nowMs);
    if ((_appTimestamps[label]?.length ?? 0) >= perAppPerHour) {
      return _drop('budget_app');
    }
    if (_totalTimestamps.length >= totalPerHour) return _drop('budget_total');

    // 通过：记账
    _appTimestamps.putIfAbsent(label, () => []).add(e.occurredAtMs);
    _totalTimestamps.add(e.occurredAtMs);
    if (body.isNotEmpty) _lastSimhash[label] = simhash64(body);
    _lastAt[label] = e.occurredAtMs;
    _lastKind[label] = e.kind;
    _stats['passed'] = (_stats['passed'] ?? 0) + 1;
    return const GovernorDecision.pass();
  }

  GovernorDecision _drop(String reason) {
    _stats[reason] = (_stats[reason] ?? 0) + 1;
    return GovernorDecision.drop(reason);
  }

  void _prune(int nowMs) {
    final cutoff = nowMs - 3600 * 1000;
    for (final l in _appTimestamps.values) {
      l.removeWhere((t) => t < cutoff);
    }
    _totalTimestamps.removeWhere((t) => t < cutoff);
  }

  // ---- simhash（64 位，字符 bigram 特征） ----

  static int simhash64(String text) {
    final weights = List<int>.filled(64, 0);
    final features = <String>[];
    final clean = text.replaceAll(RegExp(r'\s+'), '');
    for (var i = 0; i < clean.length - 1; i++) {
      features.add(clean.substring(i, i + 2));
    }
    if (features.isEmpty && clean.isNotEmpty) features.add(clean);
    for (final f in features) {
      final h = _fnv1a64(f);
      for (var b = 0; b < 64; b++) {
        if ((h >> b) & 1 == 1) {
          weights[b]++;
        } else {
          weights[b]--;
        }
      }
    }
    var hash = 0;
    for (var b = 0; b < 64; b++) {
      if (weights[b] > 0) hash |= (1 << b);
    }
    return hash;
  }

  static int hamming64(int a, int b) {
    var x = a ^ b;
    var count = 0;
    while (x != 0) {
      x &= x - 1;
      count++;
    }
    return count;
  }

  static int _fnv1a64(String s) {
    // FNV-1a 64（Dart int 为 64 位环绕运算）
    var hash = 0xcbf29ce484222325;
    for (final unit in s.codeUnits) {
      hash ^= unit;
      hash = (hash * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF;
    }
    return hash;
  }
}
