import 'dart:convert';

import 'evidence_image_fitter.dart';
import 'mcp_client.dart';

/// 到达适配器的感知查询（与 contracts/v1 sensor.query payload 对齐的纯 Dart 形态）。
class ScreenQueryRequest {
  const ScreenQueryRequest({
    required this.operation,
    this.filters = const <String, dynamic>{},
    this.maxItems = 10,
    this.deadlineMs = 0,
  });

  final String operation;
  final Map<String, dynamic> filters;
  final int maxItems;

  /// Unix 毫秒截止；0 = 未携带。
  final int deadlineMs;
}

/// 适配器产出（经 sendSensorResult 回云；data 为条目数组，schema: array<object>）。
class SensorResultReply {
  const SensorResultReply._(this.status, this.data, this.truncated, this.source);

  factory SensorResultReply.ok(
    List<Map<String, dynamic>> data, {
    bool truncated = false,
    String source = ScreenMemoAdapter.resultSource,
  }) =>
      SensorResultReply._('ok', data, truncated, source);

  /// 失败/拒绝统一形态：error 语义放 data 里（限流等原因码云端可读）。
  factory SensorResultReply.fail(
    String status,
    String code,
    String message,
  ) =>
      SensorResultReply._(
        status,
        <Map<String, dynamic>>[
          <String, dynamic>{'error': code, 'message': message},
        ],
        false,
        ScreenMemoAdapter.resultSource,
      );

  final String status; // ok / denied / unavailable / error
  final List<Map<String, dynamic>> data;
  final bool truncated;
  final String source;
}

/// 滑动窗口限流器（内存计数，进程重启清零；DESIGN.md §3）。
class SlidingWindowRateLimiter {
  SlidingWindowRateLimiter({
    required this.limit,
    this.window = const Duration(minutes: 1),
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now;

  final int limit;
  final Duration window;
  final DateTime Function() _clock;
  final List<DateTime> _hits = <DateTime>[];

  /// 尝试占用一次配额；超限返回 false（不占用）。
  bool tryAcquire() {
    _prune();
    if (_hits.length >= limit) return false;
    _hits.add(_clock());
    return true;
  }

  int get currentCount {
    _prune();
    return _hits.length;
  }

  void _prune() {
    final cutoff = _clock().subtract(window);
    while (_hits.isNotEmpty && _hits.first.isBefore(cutoff)) {
      _hits.removeAt(0);
    }
  }
}

/// ScreenMemo MCP → contracts 三能力适配器（DESIGN.md §2）。
///
/// 职责：能力路由、响应裁剪（summary/snippet 截断、evidence ≤5 张）、
/// 防御性剥除本地路径字段（file_path / database.path / lanIp，递归）、限流、
/// 错误映射（unavailable / denied / error）。handleQuery 保证不抛异常——
/// 任何失败都落成 SensorResultReply，调用方据此总能回 sensor.result。
class ScreenMemoAdapter {
  ScreenMemoAdapter({
    required ScreenMemoMcpClient client,
    this.enabled = true,
    SlidingWindowRateLimiter? searchLimiter,
    SlidingWindowRateLimiter? evidenceLimiter,
    DateTime Function()? clock,
  })  : _client = client, // ignore: prefer_initializing_formals
        searchLimiter =
            searchLimiter ?? SlidingWindowRateLimiter(limit: searchPerMinute),
        evidenceLimiter =
            evidenceLimiter ?? SlidingWindowRateLimiter(limit: evidencePerMinute),
        _clock = clock ?? DateTime.now;

  static const String resultSource = 'screenmemo.local';
  static const int searchPerMinute = 10;
  static const int evidencePerMinute = 5;
  static const int maxSnippetChars = 200;
  static const int maxSummaryChars = 500;
  static const int maxEvidenceImages = 5;

  /// DESIGN.md §3：MCP 响应中这些键一律剥除（递归；WM-03 源头已去，此处为防御）。
  static const Set<String> blockedKeys = <String>{
    'file_path',
    'path',
    'db_path',
    'lanIp',
    'lan_ip',
  };

  final ScreenMemoMcpClient _client;
  final SlidingWindowRateLimiter searchLimiter;
  final SlidingWindowRateLimiter evidenceLimiter;
  final DateTime Function() _clock;

  /// 内部 MCP 客户端（模块层 resetSession/超时预算用）。
  ScreenMemoMcpClient get client => _client;

  /// 模块开关（本地授权；关闭即 denied，DEFAULT on 由调用方持有）。
  bool enabled;

  /// 健康检查（screenmemo_status），供 UI 连接测试。
  Future<Map<String, dynamic>> ping() => _client.status();

  /// 三能力统一入口。永不抛异常。
  Future<SensorResultReply> handleQuery(ScreenQueryRequest q) async {
    try {
      if (q.deadlineMs > 0 &&
          _clock().millisecondsSinceEpoch > q.deadlineMs) {
        return SensorResultReply.fail('error', 'deadline_exceeded', '请求已过截止时间');
      }
      if (!enabled) {
        return SensorResultReply.fail('denied', 'module_disabled', 'ScreenMemo 模块已被本机关闭');
      }
      switch (q.operation) {
        case 'screen.context.read':
          return await _contextRead(q);
        case 'screen.history.search':
          if (!searchLimiter.tryAcquire()) {
            return SensorResultReply.fail(
              'denied',
              'rate_limited',
              'screen.history.search 超过限流（$searchPerMinute 次/分钟）',
            );
          }
          return await _historySearch(q);
        case 'screen.evidence.read':
          if (!evidenceLimiter.tryAcquire()) {
            return SensorResultReply.fail(
              'denied',
              'rate_limited',
              'screen.evidence.read 超过限流（$evidencePerMinute 次/分钟）',
            );
          }
          return await _evidenceRead(q);
        default:
          return SensorResultReply.fail(
            'error',
            'unsupported_operation',
            '适配器不支持的操作: ${q.operation}',
          );
      }
    } on McpException catch (e) {
      switch (e.kind) {
        case McpErrorKind.unavailable:
          return SensorResultReply.fail(
            'unavailable',
            'screenmemo_unavailable',
            e.message,
          );
        case McpErrorKind.denied:
          return SensorResultReply.fail(
            'denied',
            'mcp_token_denied',
            e.message,
          );
        case McpErrorKind.protocol:
        case McpErrorKind.toolError:
          return SensorResultReply.fail('error', 'mcp_tool_error', e.message);
      }
    } catch (e) {
      return SensorResultReply.fail('error', 'internal', '适配器内部错误: $e');
    }
  }

  // ---- screen.context.read → get_dynamic_context + list_recent_dynamics ----

  Future<SensorResultReply> _contextRead(ScreenQueryRequest q) async {
    final start = asInt(q.filters['start_time']);
    final end = asInt(q.filters['end_time']);
    final Map<String, dynamic> raw;
    if (start != null && end != null) {
      raw = await _client.callToolStructured('get_dynamic_context',
          <String, dynamic>{
            'start_time': start,
            'end_time': end,
            'limit': q.maxItems,
          });
    } else {
      raw = await _client.callToolStructured(
          'list_recent_dynamics', <String, dynamic>{'limit': q.maxItems});
    }
    final items = asList(sanitizeForCloud(raw)['items']);
    final capped = items.take(q.maxItems).toList();
    final data = <Map<String, dynamic>>[
      for (final item in capped)
        if (item is Map) _mapDynamicItem(item.cast<String, dynamic>()),
    ];
    return SensorResultReply.ok(data, truncated: items.length > capped.length);
  }

  // ---- screen.history.search → search_screenshots/search_dynamics/search_docs ----

  Future<SensorResultReply> _historySearch(ScreenQueryRequest q) async {
    final query = (q.filters['query']?.toString() ?? '').trim();
    if (query.isEmpty) {
      return SensorResultReply.fail('error', 'missing_query', 'filters.query 为空');
    }
    final sources = (q.filters['sources'] as List?)
            ?.whereType<String>()
            .toSet() ??
        const <String>{'dynamics', 'screenshots', 'docs'};
    final start = asInt(q.filters['start_time']);
    final end = asInt(q.filters['end_time']);
    final timeArgs = <String, dynamic>{
      'start_time': ?start,
      'end_time': ?end,
    };

    final merged = <Map<String, dynamic>>[];
    final failures = <String>[];
    Future<void> run(String source, Future<void> Function() body) async {
      try {
        await body();
      } on McpException catch (e) {
        // 单源工具失败不拖垮整体（unavailable/denied 仍会向上抛出）。
        if (e.kind == McpErrorKind.toolError) {
          failures.add('$source: ${e.message}');
        } else {
          rethrow;
        }
      }
    }

    if (sources.contains('dynamics')) {
      await run('dynamics', () async {
        final raw = await _client.callToolStructured(
            'search_dynamics', <String, dynamic>{
          'query': query,
          'limit': q.maxItems,
          ...timeArgs,
        });
        for (final item in asList(sanitizeForCloud(raw)['items'])) {
          if (item is Map) {
            merged.add(_mapSearchHit(
              item.cast<String, dynamic>(),
              kind: 'dynamic',
              ts: asInt(item['start_time']),
              snippetSource: item['summary'],
            ));
          }
        }
      });
    }
    if (sources.contains('screenshots')) {
      await run('screenshots', () async {
        final raw = await _client.callToolStructured(
            'search_screenshots', <String, dynamic>{
          'query': query,
          'limit': q.maxItems,
          ...timeArgs,
        });
        for (final item in asList(sanitizeForCloud(raw)['items'])) {
          if (item is Map) {
            final map = item.cast<String, dynamic>();
            final hit = _mapSearchHit(
              map,
              kind: 'screenshot',
              ts: asInt(map['capture_time']),
              snippetSource: map['ocr_preview'] ?? map['summary'],
            );
            final ref = map['image_ref'];
            if (ref is String && ref.isNotEmpty) hit['evidence_ref'] = ref;
            merged.add(hit);
          }
        }
      });
    }
    if (sources.contains('docs')) {
      await run('docs', () async {
        final raw = await _client.callToolStructured(
            'search_docs', <String, dynamic>{
          'query': query,
          'limit': q.maxItems,
          if (q.filters['doc_type'] is String) 'doc_type': q.filters['doc_type'],
          ...timeArgs,
        });
        for (final item in asList(sanitizeForCloud(raw)['items'])) {
          if (item is Map) {
            final map = item.cast<String, dynamic>();
            final hit = _mapSearchHit(
              map,
              kind: 'doc',
              ts: asInt(map['updated_at']) ?? asInt(map['start_time']),
              snippetSource: map['content'] ?? map['title'],
            );
            if (map['doc_key'] != null) hit['doc_key'] = map['doc_key'];
            if (map['title'] != null) hit['title'] = map['title'];
            if (map['doc_type'] != null) hit['doc_type'] = map['doc_type'];
            merged.add(hit);
          }
        }
      });
    }

    merged.sort((a, b) =>
        (b['capture_time'] as int? ?? 0).compareTo(a['capture_time'] as int? ?? 0));
    final capped = merged.take(q.maxItems).toList();
    if (failures.isNotEmpty) {
      capped.add(<String, dynamic>{
        'kind': 'partial_failure',
        'message': failures.join('; '),
      });
    }
    return SensorResultReply.ok(capped, truncated: merged.length > q.maxItems);
  }

  // ---- screen.evidence.read → get_evidence_images（≤5 张，opaque ref） ----

  Future<SensorResultReply> _evidenceRead(ScreenQueryRequest q) async {
    final refs = (q.filters['image_refs'] as List?)
            ?.whereType<String>()
            .where((r) => r.trim().isNotEmpty)
            .toList() ??
        const <String>[];
    final segmentId = asInt(q.filters['segment_id']);
    if (refs.isEmpty && segmentId == null) {
      return SensorResultReply.fail(
        'error',
        'missing_image_ref',
        'evidence.read 需要 filters.image_refs 或 filters.segment_id',
      );
    }
    final result = await _client.callTool(
        'get_evidence_images', <String, dynamic>{
      'limit': maxEvidenceImages,
      if (refs.isNotEmpty) 'image_refs': refs.take(maxEvidenceImages).toList(),
      'segment_id': ?segmentId,
    });
    if (result['isError'] == true) {
      return SensorResultReply.fail(
        'error',
        'evidence_unavailable',
        ScreenMemoMcpClient.extractText(result),
      );
    }
    final clean = sanitizeForCloud(result);
    final structured =
        (clean['structuredContent'] as Map?)?.cast<String, dynamic>() ??
            const <String, dynamic>{};
    final metas = asList(structured['images']);
    // content[] 中 type=image 的条目携带 base64；与 images[] 中非错误元数据按序对齐
    // （读取失败的 ref 只出现在 images[]，不进 content[]，两侧相对顺序一致）。
    final imageContent = <Map<String, dynamic>>[
      for (final entry in asList(clean['content']))
        if (entry is Map && entry['type'] == 'image')
          entry.cast<String, dynamic>(),
    ];
    var contentIdx = 0;
    final data = <Map<String, dynamic>>[];
    // 帧预算：所有图片共享 base64 总预算（§2 单帧 ≤256KiB，原图不出设备）。
    final imageBudget =
        EvidenceImageFitter.base64BudgetFor(metas.length.clamp(1, 99));
    for (final meta in metas) {
      if (data.length >= maxEvidenceImages) break;
      if (meta is! Map) continue;
      final m = meta.cast<String, dynamic>();
      final item = <String, dynamic>{
        'image_ref': m['image_ref'],
        'privacy_class': 'owner_sensitive',
      };
      if (m['error'] != null) {
        item['error'] = m['error'];
      } else {
        item['original_size_bytes'] = m['size_bytes'];
        if (contentIdx < imageContent.length) {
          final rawB64 = imageContent[contentIdx]['data'] as String?;
          contentIdx++;
          if (rawB64 == null) {
            item['error'] = 'missing_image_data';
          } else if (rawB64.length <= imageBudget) {
            item['mime_type'] = m['mime_type'];
            item['data_base64'] = rawB64;
          } else {
            final fitted = EvidenceImageFitter.fitToBase64(
              base64Decode(rawB64),
              imageBudget,
            );
            if (fitted == null) {
              item['error'] = 'too_large_for_frame';
            } else {
              item['mime_type'] = 'image/jpeg';
              item['data_base64'] = fitted;
              item['downscaled'] = true;
            }
          }
        }
      }
      data.add(item);
    }
    final rejected = asInt(structured['rejected_refs']) ?? 0;
    final emittedImages =
        data.where((item) => item.containsKey('image_ref')).length;
    if (rejected > 0 && data.length < maxEvidenceImages) {
      data.add(<String, dynamic>{
        'kind': 'rejected_refs',
        'count': rejected,
        'message': '部分 image_ref 无效/过期/越界，已被 ScreenMemo 拒绝',
      });
    }
    return SensorResultReply.ok(
      data,
      truncated: refs.length > maxEvidenceImages || metas.length > emittedImages,
    );
  }

  // ---- 映射 / 裁剪工具 ----

  Map<String, dynamic> _mapDynamicItem(Map<String, dynamic> item) {
    final apps = (item['app_packages']?.toString() ?? '')
        .split(',')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    return <String, dynamic>{
      'kind': 'dynamic',
      'segment_id': item['segment_id'],
      'start_time': item['start_time'],
      'end_time': item['end_time'],
      if (apps.isNotEmpty) 'apps': apps,
      'summary':
          truncateText(item['summary']?.toString() ?? '', maxSummaryChars),
      'privacy_class': 'owner_private',
    };
  }

  Map<String, dynamic> _mapSearchHit(
    Map<String, dynamic> item, {
    required String kind,
    required int? ts,
    required Object? snippetSource,
  }) {
    return <String, dynamic>{
      'kind': kind,
      'capture_time': ts ?? 0,
      if (item['segment_id'] != null) 'segment_id': item['segment_id'],
      if (item['app_name'] != null) 'app': item['app_name'],
      'snippet': truncateText(snippetSource?.toString() ?? '', maxSnippetChars),
      'privacy_class': 'owner_private',
    };
  }

  /// 递归剥除本地路径/网络标识字段（file_path / path / db_path / lanIp / lan_ip）。
  static dynamic sanitizeForCloud(dynamic value) {
    if (value is Map) {
      final out = <String, dynamic>{};
      value.forEach((dynamic k, dynamic v) {
        final key = k.toString();
        if (blockedKeys.contains(key)) return;
        out[key] = sanitizeForCloud(v);
      });
      return out;
    }
    if (value is List) {
      return value.map<dynamic>(sanitizeForCloud).toList();
    }
    return value;
  }

  static String truncateText(String text, int maxChars) =>
      text.length <= maxChars ? text : text.substring(0, maxChars);

  static int? asInt(Object? v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v);
    return null;
  }

  static List<dynamic> asList(Object? value) =>
      value is List ? value : const <dynamic>[];
}
