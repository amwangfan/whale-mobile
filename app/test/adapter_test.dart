import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:whale_mobile/modules/screenmemo/adapter.dart';
import 'package:whale_mobile/modules/screenmemo/mcp_client.dart';

import 'fake_mcp_server.dart';

ScreenQueryRequest query(
  String operation, {
  Map<String, dynamic> filters = const {},
  int maxItems = 10,
  int deadlineMs = 0,
}) =>
    ScreenQueryRequest(
      operation: operation,
      filters: filters,
      maxItems: maxItems,
      deadlineMs: deadlineMs,
    );

void main() {
  group('ScreenMemoAdapter 三能力映射', () {
    late FakeMcpServer server;
    late ScreenMemoAdapter adapter;

    setUp(() async {
      server = FakeMcpServer();
      await server.start();
      adapter = ScreenMemoAdapter(
        client: ScreenMemoMcpClient(
          port: server.port,
          tokenProvider: () async => server.token,
        ),
      );
    });

    tearDown(() async {
      await server.stop();
    });

    test('context.read 无时间窗 → list_recent_dynamics，字段映射正确', () async {
      server.handlers['list_recent_dynamics'] = (args) => toolResult(
        <String, dynamic>{
          'items': <dynamic>[
            <String, dynamic>{
              'segment_id': 11,
              'start_time': 1730000000000,
              'end_time': 1730000060000,
              'app_packages': 'com.tencent.mm, com.android.chrome',
              'summary': '聊微信后浏览网页',
            },
          ],
        },
      );

      final reply = await adapter.handleQuery(query('screen.context.read'));

      expect(reply.status, 'ok');
      expect(reply.source, 'screenmemo.local');
      expect(reply.data, hasLength(1));
      final item = reply.data.first;
      expect(item['segment_id'], 11);
      expect(item['start_time'], 1730000000000);
      expect(item['end_time'], 1730000060000);
      expect(item['apps'], ['com.tencent.mm', 'com.android.chrome']);
      expect(item['summary'], '聊微信后浏览网页');
      expect(item['privacy_class'], 'owner_private');
      // 无时间窗：不应调 get_dynamic_context
      expect(
        server.calls.map((c) => c['name']),
        isNot(contains('get_dynamic_context')),
      );
    });

    test('context.read 带时间窗 → get_dynamic_context，参数透传', () async {
      server.handlers['get_dynamic_context'] = (args) => toolResult(
        <String, dynamic>{'items': <dynamic>[]},
      );

      final reply = await adapter.handleQuery(query(
        'screen.context.read',
        filters: <String, dynamic>{
          'start_time': 1730000000000,
          'end_time': 1730003600000,
        },
        maxItems: 5,
      ));

      expect(reply.status, 'ok');
      expect(server.calls, hasLength(1));
      expect(server.calls.first['name'], 'get_dynamic_context');
      expect(server.calls.first['arguments']['start_time'], 1730000000000);
      expect(server.calls.first['arguments']['limit'], 5);
    });

    test('history.search 三源合并按时间倒序，snippet ≤200 字符', () async {
      server.handlers['search_dynamics'] = (args) => toolResult(
        <String, dynamic>{
          'items': <dynamic>[
            <String, dynamic>{
              'segment_id': 1,
              'start_time': 1000,
              'summary': 'dynamic hit',
            },
          ],
        },
      );
      server.handlers['search_screenshots'] = (args) => toolResult(
        <String, dynamic>{
          'items': <dynamic>[
            <String, dynamic>{
              'image_ref': 'shot:v2.s1.abcd',
              'capture_time': 3000,
              'app_name': '微信',
              'ocr_preview': 'x' * 300, // 超长 → 截断
            },
          ],
        },
      );
      server.handlers['search_docs'] = (args) => toolResult(
        <String, dynamic>{
          'items': <dynamic>[
            <String, dynamic>{
              'doc_key': 'daily-2026-10-31',
              'doc_type': 'daily',
              'title': '每日总结',
              'updated_at': 2000,
              'content': 'doc hit',
            },
          ],
        },
      );

      final reply = await adapter.handleQuery(query(
        'screen.history.search',
        filters: <String, dynamic>{'query': 'test'},
      ));

      expect(reply.status, 'ok');
      expect(reply.data, hasLength(3));
      expect(reply.data.map((e) => e['kind']),
          ['screenshot', 'doc', 'dynamic']); // 3000 > 2000 > 1000
      final shot = reply.data.first;
      expect(shot['evidence_ref'], 'shot:v2.s1.abcd');
      expect((shot['snippet'] as String).length, 200);
      expect(reply.data[1]['doc_key'], 'daily-2026-10-31');
      expect(reply.data[2]['segment_id'], 1);
      // 三源都被调用
      expect(
        server.calls.map((c) => c['name']).toSet(),
        {'search_dynamics', 'search_screenshots', 'search_docs'},
      );
    });

    test('history.search sources 路由：只查指定源', () async {
      server.handlers['search_screenshots'] =
          (args) => toolResult(<String, dynamic>{'items': <dynamic>[]});

      final reply = await adapter.handleQuery(query(
        'screen.history.search',
        filters: <String, dynamic>{
          'query': 'x',
          'sources': ['screenshots'],
        },
      ));

      expect(reply.status, 'ok');
      expect(server.calls.map((c) => c['name']).toList(),
          ['search_screenshots']);
    });

    test('history.search 缺 query → error/missing_query，不调 MCP', () async {
      final reply =
          await adapter.handleQuery(query('screen.history.search'));
      expect(reply.status, 'error');
      expect(reply.data.first['error'], 'missing_query');
      expect(server.calls, isEmpty);
    });

    test('防御过滤：嵌套 file_path/path/lanIp 递归剥除', () async {
      server.handlers['search_screenshots'] = (args) => toolResult(
        <String, dynamic>{
          'items': <dynamic>[
            <String, dynamic>{
              'image_ref': 'shot:v2.s1.abcd',
              'capture_time': 1,
              'file_path': '/data/user/0/com.fqyw/files/a.png', // 顶层
              'summary': 'hit',
              'samples': <dynamic>[
                <String, dynamic>{
                  'file_path': '/data/nested.png', // 嵌套一层
                  'meta': <String, dynamic>{
                    'path': '/data/deep', // 深层 path
                    'lanIp': '192.168.1.5',
                  },
                },
              ],
            },
          ],
          'database': <String, dynamic>{'path': '/data/master.db'},
          'service': <String, dynamic>{'lanIp': '10.0.0.2'},
        },
      );

      final reply = await adapter.handleQuery(query(
        'screen.history.search',
        filters: <String, dynamic>{
          'query': 'x',
          'sources': ['screenshots'],
        },
      ));

      expect(reply.status, 'ok');
      final encoded = jsonEncode(reply.data);
      expect(encoded, isNot(contains('file_path')));
      expect(encoded, isNot(contains('"path"')));
      expect(encoded, isNot(contains('lanIp')));
      expect(encoded, isNot(contains('/data/')));
      expect(encoded, isNot(contains('192.168.1.5')));
      // 但公开字段保留
      expect(reply.data.first['evidence_ref'], 'shot:v2.s1.abcd');
    });

    test('evidence.read 超 5 张截断，base64 与 ref 按序配对', () async {
      final metas = <Map<String, dynamic>>[
        for (var i = 1; i <= 6; i++)
          <String, dynamic>{
            'image_ref': 'shot:v2.s$i.h$i',
            'mime_type': 'image/png',
            'size_bytes': 100 * i,
          },
      ];
      server.handlers['get_evidence_images'] = (args) => toolResult(
        <String, dynamic>{'images': metas, 'limit': 5},
        extraContent: <Map<String, dynamic>>[
          for (var i = 1; i <= 6; i++)
            <String, dynamic>{
              'type': 'image',
              'data': 'base64-$i',
              'mimeType': 'image/png',
            },
        ],
      );

      final reply = await adapter.handleQuery(query(
        'screen.evidence.read',
        filters: <String, dynamic>{
          'image_refs': [for (var i = 1; i <= 6; i++) 'shot:v2.s$i.hhhh'],
        },
      ));

      expect(reply.status, 'ok');
      expect(reply.truncated, isTrue);
      final images =
          reply.data.where((e) => e.containsKey('image_ref')).toList();
      expect(images, hasLength(5)); // 上限 5 张
      expect(images.first['data_base64'], 'base64-1');
      expect(images.last['image_ref'], 'shot:v2.s5.h5');
      expect(images.first['privacy_class'], 'owner_sensitive');
      // 传给 MCP 的 image_refs 也被截到 5
      final sent = server.calls.single['arguments'] as Map<String, dynamic>;
      expect((sent['image_refs'] as List), hasLength(5));
    });

    test('evidence.read 单张读取失败不影响其余配对', () async {
      server.handlers['get_evidence_images'] = (args) => toolResult(
        <String, dynamic>{
          'images': <dynamic>[
            <String, dynamic>{'image_ref': 'r1', 'mime_type': 'image/png'},
            <String, dynamic>{'image_ref': 'r2', 'error': 'file_not_found'},
            <String, dynamic>{'image_ref': 'r3', 'mime_type': 'image/png'},
          ],
        },
        extraContent: <Map<String, dynamic>>[
          <String, dynamic>{'type': 'image', 'data': 'png-1'},
          <String, dynamic>{'type': 'image', 'data': 'png-3'},
        ],
      );

      final reply = await adapter.handleQuery(query(
        'screen.evidence.read',
        filters: <String, dynamic>{
          'image_refs': ['r1', 'r2', 'r3'],
        },
      ));

      expect(reply.status, 'ok');
      expect(reply.data[0]['data_base64'], 'png-1');
      expect(reply.data[1]['error'], 'file_not_found');
      expect(reply.data[1].containsKey('data_base64'), isFalse);
      expect(reply.data[2]['data_base64'], 'png-3');
    });

    test('evidence.read 缺引用 → error/missing_image_ref', () async {
      final reply =
          await adapter.handleQuery(query('screen.evidence.read'));
      expect(reply.status, 'error');
      expect(reply.data.first['error'], 'missing_image_ref');
    });
  });

  group('ScreenMemoAdapter 限流 / 错误映射', () {
    late FakeMcpServer server;
    late ScreenMemoAdapter adapter;

    setUp(() async {
      server = FakeMcpServer();
      await server.start();
      server.handlers['search_dynamics'] =
          (args) => toolResult(<String, dynamic>{'items': <dynamic>[]});
      server.handlers['search_screenshots'] =
          (args) => toolResult(<String, dynamic>{'items': <dynamic>[]});
      server.handlers['search_docs'] =
          (args) => toolResult(<String, dynamic>{'items': <dynamic>[]});
      server.handlers['get_evidence_images'] = (args) => toolResult(
        <String, dynamic>{'images': <dynamic>[]},
      );
      adapter = ScreenMemoAdapter(
        client: ScreenMemoMcpClient(
          port: server.port,
          tokenProvider: () async => server.token,
        ),
      );
    });

    tearDown(() async {
      await server.stop();
    });

    test('search 限流：第 11 次/分钟 → denied/rate_limited', () async {
      for (var i = 0; i < 10; i++) {
        final r = await adapter.handleQuery(query(
          'screen.history.search',
          filters: <String, dynamic>{'query': 'q$i'},
        ));
        expect(r.status, 'ok', reason: '第 ${i + 1} 次应通过');
      }
      final blocked = await adapter.handleQuery(query(
        'screen.history.search',
        filters: <String, dynamic>{'query': 'over'},
      ));
      expect(blocked.status, 'denied');
      expect(blocked.data.first['error'], 'rate_limited');
    });

    test('evidence 限流：第 6 次/分钟 → denied/rate_limited', () async {
      for (var i = 0; i < 5; i++) {
        final r = await adapter.handleQuery(query(
          'screen.evidence.read',
          filters: <String, dynamic>{'image_refs': ['r$i']},
        ));
        expect(r.status, 'ok', reason: '第 ${i + 1} 次应通过');
      }
      final blocked = await adapter.handleQuery(query(
        'screen.evidence.read',
        filters: <String, dynamic>{'image_refs': ['rX']},
      ));
      expect(blocked.status, 'denied');
      expect(blocked.data.first['error'], 'rate_limited');
    });

    test('context.read 不占 search 限额', () async {
      for (var i = 0; i < 10; i++) {
        await adapter.handleQuery(query(
          'screen.history.search',
          filters: <String, dynamic>{'query': 'q$i'},
        ));
      }
      server.handlers['list_recent_dynamics'] =
          (args) => toolResult(<String, dynamic>{'items': <dynamic>[]});
      final r = await adapter.handleQuery(query('screen.context.read'));
      expect(r.status, 'ok');
    });

    test('MCP 宕机 → unavailable', () async {
      final down = ScreenMemoAdapter(
        client: ScreenMemoMcpClient(
          port: await FakeMcpServer.freePort(),
          tokenProvider: () async => 'x',
          timeout: const Duration(seconds: 2),
        ),
      );
      final reply = await down.handleQuery(query('screen.context.read'));
      expect(reply.status, 'unavailable');
      expect(reply.data.first['error'], 'screenmemo_unavailable');
    });

    test('token 错（401）→ denied', () async {
      final denied = ScreenMemoAdapter(
        client: ScreenMemoMcpClient(
          port: server.port,
          tokenProvider: () async => 'wrong',
        ),
      );
      final reply = await denied.handleQuery(query('screen.context.read'));
      expect(reply.status, 'denied');
      expect(reply.data.first['error'], 'mcp_token_denied');
    });

    test('模块开关关闭 → denied/module_disabled，不调 MCP', () async {
      adapter.enabled = false;
      final reply = await adapter.handleQuery(query('screen.context.read'));
      expect(reply.status, 'denied');
      expect(reply.data.first['error'], 'module_disabled');
      expect(server.calls, isEmpty);
    });

    test('deadline 已过 → error/deadline_exceeded，不调 MCP', () async {
      final reply = await adapter.handleQuery(query(
        'screen.context.read',
        deadlineMs: DateTime.now().millisecondsSinceEpoch - 1000,
      ));
      expect(reply.status, 'error');
      expect(reply.data.first['error'], 'deadline_exceeded');
      expect(server.calls, isEmpty);
    });

    test('未知 operation → error/unsupported_operation', () async {
      final reply =
          await adapter.handleQuery(query('screen.live.subscribe'));
      expect(reply.status, 'error');
      expect(reply.data.first['error'], 'unsupported_operation');
    });

    test('单源工具失败不拖垮其他源（partial_failure 标注）', () async {
      server.handlers['search_docs'] = (args) =>
          toolError('search_docs table does not exist');
      final reply = await adapter.handleQuery(query(
        'screen.history.search',
        filters: <String, dynamic>{'query': 'x'},
      ));
      expect(reply.status, 'ok');
      expect(
        reply.data.any((e) => e['kind'] == 'partial_failure'),
        isTrue,
      );
    });
  });
}
