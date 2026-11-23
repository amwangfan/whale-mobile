import 'dart:math';

final Random _rng = Random.secure();

const String _alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

String _encode(int value, int length) {
  final buf = StringBuffer();
  var v = value;
  for (var i = 0; i < length; i++) {
    buf.write(_alphabet[v & 0x1F]);
    v >>= 5;
  }
  return buf.toString().split('').reversed.join();
}

/// 生成一个 ULID（26 字符）。可注入 [nowMs] 便于测试确定性。
String newUlid([int? nowMs]) {
  final t = nowMs ?? DateTime.now().millisecondsSinceEpoch;
  // 48bit 时间（10 字符）+ 80bit 随机（16 字符）
  final timePart = _encode(t & 0xFFFFFFFFFFFF, 10);
  final randPart = StringBuffer();
  for (var i = 0; i < 16; i++) {
    randPart.write(_alphabet[_rng.nextInt(32)]);
  }
  return '$timePart$randPart';
}

/// ULID 形状校验（26 字符 Crockford Base32）。
final RegExp ulidPattern = RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$');
