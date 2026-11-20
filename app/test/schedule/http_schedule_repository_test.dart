import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/schedule/services/http_schedule_repository.dart';

/// HttpScheduleRepository × 本地 HttpServer：按云端契约形状自证（离线、零真机/零外发）。
void main() {
  late HttpServer server;
  late HttpScheduleRepository repo;
  final requests = <String>[];

  Future<void> start(Map<String, Object?> Function(String method, String path,
          Map<String, String> query, Object? body) handler) async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    repo = HttpScheduleRepository(
        baseUrl: 'http://127.0.0.1:${server.port}', token: 'tk');
    server.listen((req) async {
      final body = await utf8.decodeStream(req);
      final bodyObj = body.isEmpty ? null : jsonDecode(body);
      final path = req.uri.path;
      final out = handler(req.method, path, req.uri.queryParameters, bodyObj);
      if (out.isEmpty) {
        // 记录鉴权头与请求行后 404
        if (req.headers.value('authorization') == 'Bearer tk') {
          requests.add('${req.method} $path auth-ok');
        } else {
          requests.add('${req.method} $path auth-MISSING');
        }
        req.response.statusCode = 404;
        req.response.write(jsonEncode({'ok': false, 'error': 'not found'}));
      } else {
        requests.add('${req.method} $path ${bodyObj ?? ''}');
        req.response.headers.contentType = ContentType.json;
        req.response.write(jsonEncode(out));
      }
      await req.response.close();
    });
  }

  tearDown(() async => server.close());

  test('candidates/list 解析（线上 0c76d12 实况键：candidates/item_id/ISO 时间；契约文档拼写亦兼容）', () async {
    await start((method, path, query, body) {
      if (path == '/v1/schedule/candidates') {
        return {
          'candidates': [
            {
              'item_id': 'c1',
              'title': '湖边测试',
              'date': '2026-11-19',
              'created_at': '2026-11-19T10:00:00+08:00',
              'source': {
                'conversation': '群A',
                'messageId': 'm-1',
                'at': '2026-11-19T09:58:00+08:00',
                'excerpt': '摘录'
              }
            }
          ]
        };
      }
      if (path == '/v1/schedule/list') {
        return {
          'items': [
            {'item_id': 'i1', 'title': '正式', 'date': '2026-11-20'}
          ]
        };
      }
      // 线上实况（glasses.py 部署版）：裁决=POST /candidates {id,action}；删除=DELETE /items body{id}
      if (path == '/v1/schedule/candidates' && method == 'POST') {
        return {'ok': true};
      }
      if (path == '/v1/schedule/items' && method == 'POST') {
        return {'ok': true, 'item': body};
      }
      if (path == '/v1/schedule/items' && method == 'DELETE') {
        return {'ok': (body as Map?)?['id'] == 'i1'};
      }
      return {};
    });

    final cs = await repo.fetchCandidates();
    expect(cs.single.id, 'c1'); // item_id 拼写
    expect(cs.single.source?.messageId, 'm-1');
    expect(cs.single.createdAt, greaterThan(0)); // ISO → epoch
    expect(cs.single.source!.at, greaterThan(0));
    expect(cs.single.dedupeKey, 'm:m-1');
    final items = await repo.fetchItems();
    expect(items.single.id, 'i1');

    await repo.addCandidate('c1');
    await repo.rejectCandidate('c1');
    final created = await repo.createItem(title: 't', date: '2026-11-20');
    expect(created.title, 't');
    await repo.deleteItem('i1');

    expect(requests,
        contains('POST /v1/schedule/candidates {id: c1, action: add}'));
    expect(requests,
        contains('POST /v1/schedule/candidates {id: c1, action: reject}'));
    expect(requests, contains('DELETE /v1/schedule/items {id: i1}'));
    // 删除 ok:false 如实抛错（不静默）
    expect(() => repo.deleteItem('ghost'), throwsA(isA<ScheduleApiException>()));
  });

  test('source 上下文端点：query 与解析', () async {
    await start((method, path, query, body) {
      if (path == '/v1/schedule/source') {
        expect(query['conversation'], '群A');
        expect(query['messageId'], 'm-1');
        return {
          'context': [
            {'messageId': 'm-1', 'at': '2026-11-19T09:58:00+08:00', 'sender': '我', 'text': '正文'}
          ]
        };
      }
      return {};
    });
    final list = await repo.fetchSource(conversation: '群A', messageId: 'm-1');
    expect(list.single.text, '正文');
  });

  test('错误如实：非 2xx / ok!=true → ScheduleApiException（不静默）', () async {
    await start((method, path, query, body) => {}); // 一切 404
    expect(() => repo.fetchCandidates(),
        throwsA(isA<ScheduleApiException>()));
  });
}
