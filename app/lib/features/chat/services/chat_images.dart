import 'dart:convert';
import 'dart:typed_data';

import 'package:image/image.dart' as img;

import '../../../transport/link/link_client.dart';

/// 聊天图片编解码（契约 §3：第一版 `chat.send.images` 收 data: URL，
/// ≤4 张、每张 ≤2 MB，离线可验）。
///
/// 两条纪律：
/// - 硬上限：每张 ≤2 MB（超出的图片先降尺寸/重编码压回界内，仍超则显式拒绝）；
/// - Link 帧上限 1 MiB：图片回合整体估帧，超界走 SSE 退路（HTTP POST 无帧上限，
///   同一份 Run 语义）——不是静默降级，是传输面选择。
class ChatImages {
  ChatImages._();

  static const int maxImages = 4;
  static const int maxImageBytes = 2 * 1024 * 1024; // 每张 ≤2 MB

  /// 估帧余量（信封+params 其他字段），保守取 64 KiB。
  static const int _frameSlack = 64 * 1024;

  /// 本地图片 → data: URL（[bytes] 需已 ≤2MB；超限先经 [fitToLimit]）。
  static String toDataUrl(List<int> bytes, String mime) =>
      'data:$mime;base64,${base64Encode(bytes)}';

  /// 图片回合 Link 帧估长：超 1 MiB 则本回合走 SSE 退路。
  static bool fitsLinkFrame(String text, List<String> dataUrls) {
    var total = text.length + 512; // 信封 + conversation_id 等
    for (final u in dataUrls) {
      total += u.length;
    }
    return total <= LinkClient.maxFrameBytes - _frameSlack;
  }

  /// 把图片压回 ≤2MB：先原样（已合规），否则解码 → 缩到 ≤1600 长边 →
  /// JPEG q85 重编码，仍超则逐级降质。解码失败原样返回（由调用方按大小拒绝）。
  static List<int> fitToLimit(List<int> bytes, String mime) {
    if (bytes.length <= maxImageBytes) return bytes;
    final decoded = img.decodeImage(Uint8List.fromList(bytes));
    if (decoded == null) return bytes;
    var current = decoded;
    if (current.width > 1600 || current.height > 1600) {
      current = img.copyResize(
        current,
        width: current.width >= current.height ? 1600 : null,
        height: current.height >= current.width ? null : 1600,
      );
    }
    for (final quality in const [85, 70, 55, 40]) {
      final out = img.encodeJpg(current, quality: quality);
      if (out.length <= maxImageBytes) return out;
    }
    return img.encodeJpg(current, quality: 40);
  }
}
