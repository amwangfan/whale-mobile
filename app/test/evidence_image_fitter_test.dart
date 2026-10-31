import 'dart:convert';

import 'package:image/image.dart' as img;
import 'package:flutter_test/flutter_test.dart';
import 'package:whale_mobile/modules/screenmemo/evidence_image_fitter.dart';

void main() {
  group('EvidenceImageFitter', () {
    test('小图原样通过', () {
      final small = img.encodePng(img.Image(width: 8, height: 8));
      final out = EvidenceImageFitter.fitToBase64(small, 64 * 1024);
      expect(out, isNotNull);
      expect(out!.length, lessThanOrEqualTo(64 * 1024));
    });

    test('大图被压进预算且可解码', () {
      // 造一张噪声大图（噪声最难压缩，逼近最坏情况）
      final big = img.Image(width: 1600, height: 1200);
      final rnd = img.Image(width: 1, height: 1); // 占位避免全零优化
      expect(rnd.width, 1);
      for (var y = 0; y < big.height; y++) {
        for (var x = 0; x < big.width; x++) {
          final v = (x * 31 + y * 17) % 256;
          big.setPixelRgb(x, y, v, (v * 3) % 256, (v * 7) % 256);
        }
      }
      final raw = img.encodeJpg(big, quality: 95);
      const budget = 100 * 1024; // base64 预算
      final out = EvidenceImageFitter.fitToBase64(raw, budget);
      expect(out, isNotNull);
      expect(out!.length, lessThanOrEqualTo(budget));
      expect(img.decodeJpg(base64Decode(out)), isNotNull);
    });

    test('预算极小返回 null（宁缺毋滥）', () {
      final raw = img.encodeJpg(img.Image(width: 800, height: 600), quality: 90);
      expect(EvidenceImageFitter.fitToBase64(raw, 1024), isNull);
    });

    test('非图片字节返回 null', () {
      expect(EvidenceImageFitter.fitToBase64(base64Decode('aGVsbG8='), 99999), isNull);
    });
  });
}
