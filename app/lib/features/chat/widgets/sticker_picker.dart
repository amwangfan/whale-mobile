import 'package:flutter/material.dart';

/// 表情包选择器（占位实现）。
///
/// 规划 §5.1 要求 GIF/WebP 表情包；本版本以本地占位表情包代替真实
/// asset 包：网格内为占位贴图（后续将真实 GIF/WebP 放入
/// `app/assets/stickers/` 并登记 pubspec 后替换 [StickerPicker.placeholders]）。
/// 选中后以 `[贴纸:<key>]` 文本占位符插入输入框（真实贴图消息在
/// Core 贴图附件协议就绪后切换为附件发送，不在本阶段伪造）。
class StickerPicker extends StatelessWidget {
  const StickerPicker({super.key, required this.onSelect});

  /// 选中占位贴图（参数为贴图 key）。
  final ValueChanged<String> onSelect;

  /// 占位贴图清单（key → 展示字符）。后续替换为 asset 清单。
  static const Map<String, String> placeholders = <String, String>{
    'smile': '😀',
    'laugh': '😂',
    'thumb_up': '👍',
    'heart': '❤️',
    'cry': '😢',
    'angry': '😠',
    'ok': '👌',
    'party': '🎉',
    'think': '🤔',
    'wow': '😮',
    'sleep': '😴',
    'clap': '👏',
  };

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 200,
      child: GridView.count(
        crossAxisCount: 6,
        padding: const EdgeInsets.all(8),
        children: [
          for (final entry in placeholders.entries)
            InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () => onSelect(entry.key),
              child: Center(
                child: Text(entry.value, style: const TextStyle(fontSize: 28)),
              ),
            ),
        ],
      ),
    );
  }
}
