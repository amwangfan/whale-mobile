import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../models/attachment.dart';

/// 图片消息（GIF/WebP 由 Flutter 图片解码直接支持）。
///
/// 经 `GET /api/v2/attachments/{id}` 拉取，需要 Bearer 鉴权头——
/// 由调用方（chat_page）从 ChatApi.authHeaders() 解析传入。
class ImageMessage extends StatelessWidget {
  const ImageMessage({
    super.key,
    required this.attachment,
    required this.imageUrl,
    this.headers = const {},
    this.onTap,
  });

  final AttachmentRef attachment;
  final String imageUrl;
  final Map<String, String> headers;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final w = attachment.width;
    final h = attachment.height;
    final aspect = (w != null && h != null && h > 0) ? w / h : 4 / 3;
    return GestureDetector(
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 240, maxHeight: 320),
          child: AspectRatio(
            aspectRatio: aspect.clamp(0.5, 2.0),
            child: CachedNetworkImage(
              imageUrl: imageUrl,
              httpHeaders: headers,
              fit: BoxFit.cover,
              // 加载进度显式可见（不做静默假象）
              progressIndicatorBuilder: (context, url, progress) => Center(
                child: CircularProgressIndicator(value: progress.progress),
              ),
              errorWidget: (context, url, error) => Container(
                color: Theme.of(context).colorScheme.errorContainer,
                alignment: Alignment.center,
                padding: const EdgeInsets.all(8),
                child: Text(
                  '图片加载失败',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onErrorContainer,
                    fontSize: 12,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
