/// 证据图片帧预算压缩：把 ScreenMemo 返回的原图缩到合约单帧预算内。
///
/// 依据 contracts/v1/PROTOCOL.md §2（单帧 ≤ 256KiB）。原图永不出设备；
/// 云端只拿到适配帧预算的压缩副本，元数据标注 original_size_bytes。
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:image/image.dart' as img;

class EvidenceImageFitter {
  /// 单帧总预算 256KiB，信封与元数据预留后给 base64 的预算。
  static const int frameBudgetBytes = 256 * 1024;
  static const int envelopeReserveBytes = 20 * 1024;

  /// base64 文本预算（4/3 膨胀反推已在调用处计入，这里直接限 base64 长度）。
  static int base64BudgetFor(int imageCount) {
    final total = frameBudgetBytes - envelopeReserveBytes;
    final per = total ~/ (imageCount < 1 ? 1 : imageCount);
    return per.clamp(24 * 1024, total);
  }

  /// 返回能放进 [base64Budget] 的 JPEG base64；实在压不下返回 null。
  /// 逐级降低尺寸与质量；下限宽 320px / 质量 40，再小宁可报 too_large。
  static String? fitToBase64(Uint8List raw, int base64Budget) {
    img.Image? decoded;
    try {
      decoded = img.decodeImage(raw);
    } catch (_) {
      return null;
    }
    if (decoded == null) return null;

    // 快路径：原样已在预算内。
    final rawB64 = base64Encode(raw);
    if (rawB64.length <= base64Budget) return rawB64;

    for (final scale in const [0.75, 0.5, 0.35, 0.25]) {
      final w = (decoded.width * scale).round();
      if (w < 320) break;
      final resized = img.copyResize(decoded, width: w);
      for (final quality in const [80, 65, 50, 40]) {
        final jpg = img.encodeJpg(resized, quality: quality);
        final b64 = base64Encode(jpg);
        if (b64.length <= base64Budget) return b64;
      }
    }
    return null;
  }
}
