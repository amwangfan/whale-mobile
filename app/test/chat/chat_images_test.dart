import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;

import 'package:whale_mobile/features/chat/services/chat_images.dart';
import 'package:whale_mobile/transport/link/link_client.dart';

/// 聊天图片口径（契约 §3）：≤4 张每张 ≤2MB data: URL；Link 帧上限 1MiB。
void main() {
  group('ChatImages', () {
    test('toDataUrl 形状', () {
      final url = ChatImages.toDataUrl([1, 2, 3], 'image/png');
      expect(url, startsWith('data:image/png;base64,'));
      expect(base64Decode(url.split(',').last), [1, 2, 3]);
    });

    test('fitToLimit：小图原样返回（同对象）', () {
      final small = List<int>.filled(100, 7);
      expect(identical(ChatImages.fitToLimit(small, 'image/png'), small),
          isTrue);
    });

    test('fitToLimit：大图重编码压回 ≤2MB', () {
      // 造一张纯噪点图（PNG 不可压，必超 2MB）
      final rng = Random(42);
      final big = img.Image(width: 2600, height: 2000);
      for (var y = 0; y < big.height; y++) {
        for (var x = 0; x < big.width; x++) {
          big.setPixelRgb(
              x, y, rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
        }
      }
      final raw = img.encodePng(big);
      expect(raw.length, greaterThan(ChatImages.maxImageBytes));
      final fitted = ChatImages.fitToLimit(raw, 'image/png');
      expect(fitted.length, lessThanOrEqualTo(ChatImages.maxImageBytes));
      // 重编码后是合法 JPEG
      expect(img.decodeJpg(Uint8List.fromList(fitted)), isNotNull);
    });

    test('fitsLinkFrame：纯文本恒真；大图组超界走 SSE 退路', () {
      expect(ChatImages.fitsLinkFrame('你好', const []), isTrue);
      // 900KB base64 一张 → 仍在界内
      final one =
          'data:image/jpeg;base64,${'A' * (600 * 1024)}';
      expect(ChatImages.fitsLinkFrame('你好', [one]), isTrue);
      // 两张 600KB → 超 1MiB → false（走 SSE）
      expect(ChatImages.fitsLinkFrame('你好', [one, one]), isFalse);
      expect(LinkClient.maxFrameBytes, 1024 * 1024);
    });
  });

  group('linkUriFor 派生', () {
    test('http→ws / https→wss，路径 /api/link，无硬编码端口', () {
      expect(LinkClient.linkUriFor('http://203.0.113.10:8189').toString(),
          'ws://203.0.113.10:8189/api/link');
      expect(LinkClient.linkUriFor('https://rayneo.wangfanlab.com').toString(),
          'wss://rayneo.wangfanlab.com/api/link');
      expect(
          () => LinkClient.linkUriFor('ftp://x'), throwsA(isA<LinkError>()));
    });
  });
}
