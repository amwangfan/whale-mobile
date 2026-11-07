/// ScreenMemo 事件去重与可选过滤。纯 Dart 逻辑，可独立测试。
///
/// 默认只做短时间的重复合并与隐私标签过滤；过期丢弃和数量预算需显式配置：
/// - **过期丢弃**：occurred_at 早于 now-staleMs 的事件不补传（stale）；
/// - **隐私过滤**：App 标签命中默认拒绝关键词（输入法/锁屏/支付等）或
///   用户黑名单 → 丢弃（filtered）；用户白名单可豁免默认关键词；
/// - **时间窗合并**：同 App 同 kind 在 mergeWindowMs 内重复出现 → 丢弃
///   （merged；保留首条）；
/// - **simhash 去重**：同 App、同 kind 在短时间内相似正文 → 丢弃
///   （simhash_dup；OCR 摘要几乎不变的刷屏抑制）；
/// - **数量预算**：可选每 App / 全局小时上限（0 = 不限制）。
///
/// 所有丢弃都计数（stats 可观测）；被丢弃事件的游标照常推进
/// （治理决定是终态，不补传）。
library;

/// 治理判决。
class GovernorDecision {
  const GovernorDecision.pass({this.bodyHash}) : pass = true, reason = null;
  const GovernorDecision.drop(this.reason) : pass = false, bodyHash = null;

  final bool pass;
  final int? bodyHash;

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
    this.perAppPerHour = 0,
    this.totalPerHour = 0,
    this.mergeWindowMs = 60 * 1000,
    this.simhashThreshold = 4,
    this.simhashWindowMs = 5 * 60 * 1000,
    this.staleMs = 0,
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
  final int simhashWindowMs;
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
  final Map<String, int> _stats = {};

  /// 治理统计（reason → count；passed 为通过数）。
  Map<String, int> get stats => Map.unmodifiable(_stats);

  /// 只判定，不占用去重窗口或预算。用于发送前检查。
  /// 丢弃判定计入统计；通过的事件应在确认入队后调用 [recordAccepted]。
  GovernorDecision inspect(GovernedEvent e, int nowMs) =>
      _decide(e, nowMs, recordPass: false);

  /// 适合纯同步调用的判定与记账入口。
  GovernorDecision decide(GovernedEvent e, int nowMs) =>
      _decide(e, nowMs, recordPass: true);

  GovernorDecision _decide(
    GovernedEvent e,
    int nowMs, {
    required bool recordPass,
  }) {
    if (staleMs > 0 && e.occurredAtMs < nowMs - staleMs) {
      return _drop('stale');
    }

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

    // 按 App 与事件类型分别去重，避免跨类型误判。
    final key = '$label|${e.kind}';
    final lastAt = _lastAt[key];
    final delta = lastAt == null ? null : e.occurredAtMs - lastAt;
    if (mergeWindowMs > 0 &&
        delta != null &&
        delta >= 0 &&
        delta < mergeWindowMs) {
      return _drop('merged');
    }

    final body = e.body;
    final bodyHash = body.isEmpty || simhashThreshold < 0
        ? null
        : simhash64(body);
    if (bodyHash != null &&
        simhashWindowMs > 0 &&
        delta != null &&
        delta >= 0 &&
        delta <= simhashWindowMs &&
        _lastSimhash[key] != null &&
        hamming64(_lastSimhash[key]!, bodyHash) <= simhashThreshold) {
      return _drop('simhash_dup');
    }

    if (perAppPerHour > 0 || totalPerHour > 0) {
      _prune(nowMs);
      if (perAppPerHour > 0 &&
          (_appTimestamps[label]?.length ?? 0) >= perAppPerHour) {
        return _drop('budget_app');
      }
      if (totalPerHour > 0 && _totalTimestamps.length >= totalPerHour) {
        return _drop('budget_total');
      }
    }

    if (recordPass) recordAccepted(e, bodyHash: bodyHash);
    return GovernorDecision.pass(bodyHash: bodyHash);
  }

  /// 只有事件成功入队后才占用去重窗口和数量预算。
  void recordAccepted(GovernedEvent e, {int? bodyHash}) {
    final label = e.appLabel;
    final key = '$label|${e.kind}';
    if (perAppPerHour > 0) {
      _appTimestamps.putIfAbsent(label, () => []).add(e.occurredAtMs);
    }
    if (totalPerHour > 0) _totalTimestamps.add(e.occurredAtMs);
    if (bodyHash != null) _lastSimhash[key] = bodyHash;
    _lastAt.remove(key);
    _lastAt[key] = e.occurredAtMs;
    if (_lastAt.length > 512) {
      final oldest = _lastAt.keys.first;
      _lastAt.remove(oldest);
      _lastSimhash.remove(oldest);
    }
    _stats['passed'] = (_stats['passed'] ?? 0) + 1;
  }

  GovernorDecision _drop(String reason) {
    _stats[reason] = (_stats[reason] ?? 0) + 1;
    return GovernorDecision.drop(reason);
  }

  void _prune(int nowMs) {
    final cutoff = nowMs - 3600 * 1000;
    _appTimestamps.removeWhere((_, timestamps) {
      timestamps.removeWhere((t) => t < cutoff);
      return timestamps.isEmpty;
    });
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
