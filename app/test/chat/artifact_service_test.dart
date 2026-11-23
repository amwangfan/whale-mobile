import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/models/artifact.dart';
import 'package:whale_mobile/features/chat/services/artifact_service.dart';

/// Artifact REST 面（契约 §3）：GET Bearer 拉取 + POST 上行 404 特性探测降级。
void main() {
  group('ArtifactRef 模型', () {
    test('解析 + id 形状校验（art_+ULID）', () {
      final ref = ArtifactRef.fromJson(const {
        'id': 'art_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
        'name': 'pic.png',
        'content_type': 'image/png',
        'size': 1234,
        'created_at': 1795400000000,
        'meta': {},
        'url': '/api/artifacts/art_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
        'sha256': 'abc123',
      });
      expect(ref.artifactId, 'art_01J9XK3EM8TQ2ZP0VN6W4RA7YC');
      expect(ref.isImage, isTrue);
      expect(ref.hasValidId, isTrue);
      expect(ref.sha256, 'abc123');
      expect(ref.sizeLabel, '1.2 KB');
    });

    test('att_ 旧式 id 不通过 art_ 校验（命名空间统一为 art_）', () {
      expect(
          ArtifactRef.idPattern.hasMatch('att_01J9XK3EM8TQ2ZP0VN6W4RA7YC'),
          isFalse);
    });
  });

  group('ArtifactService HTTP（假 HTTP server）', () {
    late HttpServer http;
    late String base;
    final requests = <HttpRequest>[];

    setUp(() async {
      requests.clear();
      http = await HttpServer.bind('127.0.0.1', 0);
      base = 'http://127.0.0.1:${http.port}';
      http.listen((req) async {
        requests.add(req);
        switch (req.uri.path) {
          case '/api/artifacts/art_ok':
            if (req.headers.value('authorization') != 'Bearer test-token') {
              req.response.statusCode = 401;
              await req.response.close();
              return;
            }
            req.response
              ..statusCode = 200
              ..headers.contentType = ContentType('image', 'png')
              ..add([1, 2, 3, 4]);
            await req.response.close();
            break;
          case '/api/artifacts':
            // 上行：本假面返回 201（core 已实现形状）；404 场景用另一条路径模拟
            final body = await req.fold<List<int>>(<int>[], (a, b) => a..addAll(b));
            final headers = req.headers;
            req.response
              ..statusCode = 201
              ..headers.contentType = ContentType.json
              ..write(jsonEncode({
                'ok': true,
                'result': {
                  'id': 'art_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
                  'name': headers.value('x-whale-name') ?? '',
                  'content_type':
                      headers.value('x-whale-content-type') ?? 'application/octet-stream',
                  'size': body.length,
                  'created_at': 1795400000000,
                  'meta': {},
                  'url': '/api/artifacts/art_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
                  'sha256': 'deadbeef',
                },
              }));
            await req.response.close();
            break;
          default:
            req.response.statusCode = 404;
            await req.response.close();
        }
      });
    });

    tearDown(() async => http.close(force: true));

    test('GET /api/artifacts/{id}：Bearer 头 + 字节拉取', () async {
      final svc = ArtifactService(baseUrl: base, token: 'test-token');
      final bytes = await svc.download('art_ok');
      expect(bytes, [1, 2, 3, 4]);
      svc.close();
    });

    test('GET 无 token → 401 显式错误', () async {
      final svc = ArtifactService(baseUrl: base, token: '');
      await expectLater(
        svc.download('art_ok'),
        throwsA(isA<ArtifactException>()
            .having((e) => e.code, 'code', 'http_401')),
      );
      svc.close();
    });

    test('POST /api/artifacts：裸体 + X-Whale-* 头 → 201 ArtifactRef',
        () async {
      final svc = ArtifactService(baseUrl: base, token: 'test-token');
      final ref = await svc.upload(
        bytes: utf8.encode('hello'),
        name: 'note.txt',
        contentType: 'text/plain',
      );
      expect(ref.artifactId, 'art_01J9XK3EM8TQ2ZP0VN6W4RA7YC');
      expect(ref.name, 'note.txt');
      expect(ref.contentType, 'text/plain');
      expect(ref.sha256, 'deadbeef');
      final req = requests.last;
      expect(req.headers.value('authorization'), 'Bearer test-token');
      expect(req.headers.value('x-whale-name'), 'note.txt');
      svc.close();
    });
  });

  group('上行 404 特性探测降级（核心差距）', () {
    late HttpServer http;
    late String base;

    setUp(() async {
      http = await HttpServer.bind('127.0.0.1', 0);
      base = 'http://127.0.0.1:${http.port}';
      http.listen((req) async {
        req.response.statusCode = 404;
        await req.response.close();
      });
    });

    tearDown(() async => http.close(force: true));

    test('404 → not_implemented（不当作普通失败重试）', () async {
      final svc = ArtifactService(baseUrl: base, token: 't');
      await expectLater(
        svc.upload(bytes: [1], name: 'a.bin', contentType: 'application/octet-stream'),
        throwsA(isA<ArtifactException>()
            .having((e) => e.code, 'code', 'not_implemented')
            .having((e) => e.httpStatus, 'httpStatus', 404)),
      );
      svc.close();
    });
  });
}
