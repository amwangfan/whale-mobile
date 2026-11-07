import 'package:flutter/material.dart';

import '../controllers/chat_controller.dart';
import '../models/attachment.dart';
import '../models/message.dart';
import '../services/attachment_service.dart';
import '../services/chat_api.dart';
import '../widgets/attachment_picker.dart';
import '../widgets/message_bubble.dart';
import '../widgets/sticker_picker.dart';
import 'persona_settings.dart';

/// 聊天页（Phase 4）：流式消息列表 + 输入栏（文字/附件/表情包）。
class ChatPage extends StatefulWidget {
  const ChatPage({
    super.key,
    required this.conversationId,
    this.controller,
    this.api,
    this.attachmentService,
  });

  final String conversationId;

  /// 测试注入点（为 null 时各自建真实实现）。
  final ChatController? controller;
  final ChatApi? api;
  final AttachmentService? attachmentService;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  late final ChatController _controller = widget.controller ??
      ChatController(conversationId: widget.conversationId);
  late final ChatApi _api = widget.api ?? ChatApi();
  late final AttachmentService _attachments =
      widget.attachmentService ?? AttachmentService();

  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();
  final FocusNode _inputFocus = FocusNode();

  bool _stickerOpen = false;

  /// 待发送附件（已上传完成，跟随下一条消息）。
  final List<AttachmentRef> _pendingAttachments = <AttachmentRef>[];

  /// 附件上传中的显式状态（不做静默假象）。
  bool _uploading = false;
  String? _uploadError;

  /// 图片附件鉴权头（cached_network_image）。
  Map<String, String> _imageHeaders = const {};
  String _baseUrl = '';

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onUpdate);
    _scroll.addListener(_maybeLoadOlder);
    _controller.loadInitial();
    _resolveImageAuth();
  }

  Future<void> _resolveImageAuth() async {
    try {
      final headers = await _api.authHeaders();
      // baseUrl 用于拼附件下载地址
      final uri = await _api.attachmentUri('placeholder');
      if (!mounted) return;
      setState(() {
        _imageHeaders = headers;
        _baseUrl = uri.toString().replaceAll('placeholder', '');
      });
    } catch (_) {/* 配置缺失时图片加载失败会显式占位 */}
  }

  String _attachmentUrl(String attachmentId) => '$_baseUrl$attachmentId';

  void _onUpdate() {
    if (mounted) setState(() {});
  }

  void _maybeLoadOlder() {
    // reverse 列表：滚动到「视觉顶部」= pixels 接近 maxScrollExtent
    if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 200) {
      _controller.loadOlder();
    }
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    _inputFocus.dispose();
    _controller.removeListener(_onUpdate);
    if (widget.controller == null) _controller.dispose();
    if (widget.api == null) _api.close();
    if (widget.attachmentService == null) _attachments.close();
    super.dispose();
  }

  // ---------------------------------------------------------------- 发送

  Future<void> _send() async {
    final text = _input.text;
    final atts = List<AttachmentRef>.of(_pendingAttachments);
    if (text.trim().isEmpty && atts.isEmpty) return;
    _input.clear();
    setState(() => _pendingAttachments.clear());
    await _controller.send(text, attachments: atts);
  }

  Future<void> _pickAndUpload(Future<PickedAttachment?> Function() pick) async {
    setState(() {
      _uploading = true;
      _uploadError = null;
    });
    try {
      final picked = await pick();
      if (picked == null) {
        setState(() => _uploading = false);
        return; // 用户取消
      }
      final ref = await _attachments.upload(picked);
      if (!mounted) return;
      setState(() {
        _pendingAttachments.add(ref);
        _uploading = false;
      });
    } on ChatApiException catch (e) {
      if (mounted) {
        setState(() {
          _uploading = false;
          _uploadError = '附件上传失败：$e';
        });
      }
    }
  }

  void _openAttachmentPicker() {
    AttachmentPicker.show(
      context,
      onTakePhoto: () => _pickAndUpload(_attachments.takePhoto),
      onPickImage: () => _pickAndUpload(_attachments.pickImage),
      onPickFile: () => _pickAndUpload(_attachments.pickFile),
    );
  }

  void _openPersonaSettings() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => PersonaSettingsPage(
          conversationId: widget.conversationId,
          api: _api,
        ),
      ),
    );
  }

  /// 附件点击：图片全屏预览；其余显式提示（下载/打开留待系统集成）。
  void _openAttachment(AttachmentRef att) {
    if (att.type == AttachmentType.image) {
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => Scaffold(
            appBar: AppBar(title: Text(att.attachmentId)),
            body: Center(
              child: InteractiveViewer(
                child: Image.network(
                  _attachmentUrl(att.attachmentId),
                  headers: _imageHeaders,
                ),
              ),
            ),
          ),
        ),
      );
    } else {
      showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(att.type.label),
          content: Text(
            '${att.mime} · ${att.sizeLabel}\n引用：${att.attachmentId}',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('关闭'),
            ),
          ],
        ),
      );
    }
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.conversationId.replaceFirst('mobile:', '')),
        actions: [
          IconButton(
            tooltip: '角色设置',
            icon: const Icon(Icons.person),
            onPressed: _openPersonaSettings,
          ),
        ],
      ),
      body: Column(
        children: [
          if (_controller.error != null) _errorBanner(_controller.error!),
          Expanded(child: _messageList()),
          if (_uploading || _uploadError != null) _uploadBanner(),
          if (_pendingAttachments.isNotEmpty) _pendingBar(),
          _inputBar(),
          if (_stickerOpen)
            StickerPicker(onSelect: (key) {
              // 占位贴图：以 [贴纸:key] 文本插入输入框（真实贴图附件后续接入）
              _input.text = '${_input.text}[贴纸:$key]';
              _input.selection = TextSelection.collapsed(
                  offset: _input.text.length);
            }),
        ],
      ),
    );
  }

  Widget _errorBanner(String text) {
    return Material(
      color: Theme.of(context).colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            const Icon(Icons.error_outline, size: 18),
            const SizedBox(width: 8),
            Expanded(
              child: Text(text, style: const TextStyle(fontSize: 12)),
            ),
            TextButton(
              onPressed: _controller.refresh,
              child: const Text('刷新'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _messageList() {
    if (_controller.loadingInitial && _controller.messages.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_controller.messages.isEmpty) {
      return const Center(child: Text('发一条消息开始对话吧'));
    }
    return ListView.builder(
      controller: _scroll,
      reverse: true, // index 0 = 最新（视觉底部）
      itemCount: _controller.messages.length +
          (_controller.loadingOlder ? 1 : 0),
      itemBuilder: (context, i) {
        if (i >= _controller.messages.length) {
          return const Padding(
            padding: EdgeInsets.all(12),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        final m = _controller.messages[i];
        return MessageBubble(
          message: m,
          currentPhase:
              m.sendState == MessageSendState.streaming
                  ? _controller.currentPhase
                  : null,
          resolveAttachmentUrl: _attachmentUrl,
          attachmentHeaders: _imageHeaders,
          onAttachmentTap: _openAttachment,
        );
      },
    );
  }

  Widget _uploadBanner() {
    return Container(
      width: double.infinity,
      color: _uploadError != null
          ? Theme.of(context).colorScheme.errorContainer
          : Theme.of(context).colorScheme.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Text(
        _uploadError ?? '附件上传中…',
        style: const TextStyle(fontSize: 12),
      ),
    );
  }

  Widget _pendingBar() {
    return SizedBox(
      height: 56,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        children: [
          for (final att in _pendingAttachments)
            Padding(
              padding: const EdgeInsets.all(6),
              child: Chip(
                avatar: Icon(
                  att.type == AttachmentType.image
                      ? Icons.image
                      : Icons.insert_drive_file,
                  size: 16,
                ),
                label: Text('${att.type.label} ${att.sizeLabel}',
                    style: const TextStyle(fontSize: 12)),
                onDeleted: () =>
                    setState(() => _pendingAttachments.remove(att)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _inputBar() {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            IconButton(
              tooltip: '附件',
              icon: const Icon(Icons.attach_file),
              onPressed:
                  _controller.sending || _uploading ? null : _openAttachmentPicker,
            ),
            IconButton(
              tooltip: '表情包',
              icon: Icon(_stickerOpen
                  ? Icons.keyboard
                  : Icons.emoji_emotions_outlined),
              onPressed: () =>
                  setState(() => _stickerOpen = !_stickerOpen),
            ),
            Expanded(
              child: TextField(
                controller: _input,
                focusNode: _inputFocus,
                minLines: 1,
                maxLines: 5,
                decoration: const InputDecoration(
                  hintText: '输入消息…',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                onSubmitted: (_) => _send(),
              ),
            ),
            const SizedBox(width: 4),
            IconButton(
              tooltip: '发送',
              icon: const Icon(Icons.send),
              onPressed: _controller.sending || _uploading ? null : _send,
            ),
          ],
        ),
      ),
    );
  }
}
