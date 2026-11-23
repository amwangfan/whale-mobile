import 'package:flutter/material.dart';

/// 附件选择底部弹层：拍照 / 相册图片 / 文件。
///
/// 真实选择在 chat_page 里经 ChatAttachments 完成（image_picker /
/// file_picker 插件），本组件只负责入口 UI。
/// 图片走 chat.send.images（data: URL ≤4 张每张 ≤2MB）；文件走
/// POST /api/artifacts（core 未实现时 404 特性探测降级）。
class AttachmentPicker extends StatelessWidget {
  const AttachmentPicker({
    super.key,
    required this.onTakePhoto,
    required this.onPickImage,
    required this.onPickFile,
  });

  final VoidCallback onTakePhoto;
  final VoidCallback onPickImage;
  final VoidCallback onPickFile;

  /// 以 ModalBottomSheet 形式弹出。
  static Future<void> show(
    BuildContext context, {
    required VoidCallback onTakePhoto,
    required VoidCallback onPickImage,
    required VoidCallback onPickFile,
  }) {
    return showModalBottomSheet<void>(
      context: context,
      builder: (_) => SafeArea(
        child: AttachmentPicker(
          onTakePhoto: onTakePhoto,
          onPickImage: onPickImage,
          onPickFile: onPickFile,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ListTile(
          leading: const Icon(Icons.photo_camera),
          title: const Text('拍照'),
          onTap: () {
            Navigator.of(context).pop();
            onTakePhoto();
          },
        ),
        ListTile(
          leading: const Icon(Icons.photo_library),
          title: const Text('相册图片'),
          subtitle: const Text('支持 GIF / WebP'),
          onTap: () {
            Navigator.of(context).pop();
            onPickImage();
          },
        ),
        ListTile(
          leading: const Icon(Icons.insert_drive_file),
          title: const Text('文件'),
          subtitle: const Text('任意类型，≤50MB'),
          onTap: () {
            Navigator.of(context).pop();
            onPickFile();
          },
        ),
      ],
    );
  }
}
