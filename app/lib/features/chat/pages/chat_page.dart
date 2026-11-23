import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../../transport/link/link_client.dart';
import '../controllers/approval_controller.dart';
import '../controllers/chat_controller.dart';
import '../models/message.dart';
import '../services/artifact_service.dart';
import '../services/chat_attachments.dart';
import '../services/chat_images.dart';
import '../services/link_session.dart';
import '../widgets/attachment_picker.dart';
import '../widgets/message_bubble.dart';
import '../widgets/sticker_picker.dart';
import 'approvals_page.dart';
import 'persona_settings.dart';

/// 聊天页（v3 统一主干）：Link 事件流驱动 + SSE 退路 + 审批可见可裁决。
///
/// 生命周期（M2/O1）：页面活跃持有 LinkSession 连接，离开释放
/// （引用计数；通知永不丢走设备 WS，既有不动）。
class ChatPage extends StatefulWidget {
  const ChatPage({
    super.key,
    required this.conversationId,
    this.title = '',
    this.session,
    this.controller,
    this.approvals,
    this.artifactService,
    this.attachments,
  });

  final String conversationId;

  /// 会话标题（列表带入；空则显示 id）。
  final String title;

  /// 会话级 Link 连接（默认全局 LinkSession.instance；测试可注入假会话）。
  final LinkSession? session;

  /// 测试注入点（非空时直接使用，不走 session 建连）。
  final ChatController? controller;

  /// 测试注入点（审批中心；默认 session.approvals）。
  final ApprovalController? approvals;

  /// 测试注入点。
  final ArtifactService? artifactService;
  final ChatAttachments? attachments;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  LinkSession get _session => widget.session ?? LinkSession.instance;

  ChatController? _controller;
  ApprovalController? _approvals;
  late final ArtifactService _artifacts =
      widget.artifactService ?? ArtifactService();
  late final ChatAttachments _picker = widget.attachments ?? ChatAttachments();

  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();
  final FocusNode _inputFocus = FocusNode();

  bool _stickerOpen = false;

  /// 待发送图片（data: URL ≤4 张每张 ≤2MB）。
  final List<PickedImage> _pendingImages = <PickedImage>[];
  String? _attachError;

  /// artifact 下载鉴权头。
  Map<String, String> _artifactHeaders = const {};
  String _artifactBase = '';

  @override
  void initState() {
    super.initState();
    _session.addListener(_onSession);
    _scroll.addListener(_maybeLoadOlder);
    if (widget.controller != null) {
      _useController(widget.controller!);
    } else {
      _session.acquire();
      _onSession(); // api 可能已就绪
    }
    _resolveArtifactAuth();
  }

  void _onSession() {
    final api = _session.api;
    if (_controller == null && api != null) {
      _useController(ChatController(conversationId: widget.conversationId, api: api));
    }
    _safeSetState();
  }

  /// 连接/控制器事件可能落在 build 阶段：build 中不直接 setState。
  void _safeSetState() {
    if (!mounted) return;
    if (SchedulerBinding.instance.schedulerPhase != SchedulerPhase.idle) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() {});
      });
    } else {
      setState(() {});
    }
  }

  void _useController(ChatController controller) {
    _controller = controller;
    _approvals = widget.approvals ?? _session.approvals;
    _controller!.addListener(_onUpdate);
    _approvals?.addListener(_onUpdate);
    _controller!.attach();
    _controller!.loadInitial();
  }



  void _onUpdate() {
    _safeSetState();
  }

  void _maybeLoadOlder() {
    // reverse 列表：滚动到「视觉顶部」= pixels 接近 maxScrollExtent
    if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 200) {
      _controller?.loadOlder();
    }
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    _inputFocus.dispose();
    _session.removeListener(_onSession);
    _controller?.removeListener(_onUpdate);
    _approvals?.removeListener(_onUpdate);
    if (widget.controller == null) {
      _controller?.dispose();
      _session.release();
    }
    if (widget.artifactService == null) _artifacts.close();
    super.dispose();
  }

  // ---------------------------------------------------------------- 发送

  Future<void> _send() async {
    final controller = _controller;
    if (controller == null) return;
    final text = _input.text;
    final imgs = List<PickedImage>.of(_pendingImages);
    if (text.trim().isEmpty && imgs.isEmpty) return;
    _input.clear();
    setState(() => _pendingImages.clear());
    await controller.send(
      text,
      images: imgs.map((i) => i.dataUrl).toList(),
      localImages: imgs
          .map((i) => LocalImage(dataUrl: i.dataUrl, mime: i.mime))
          .toList(),
    );
  }

  Future<void> _pickImage(Future<PickedImage?> Function() pick) async {
    if (_pendingImages.length >= ChatImages.maxImages) {
      setState(() => _attachError = '图片最多 ${ChatImages.maxImages} 张');
      return;
    }
    setState(() => _attachError = null);
    final picked = await pick();
    if (!mounted || picked == null) return;
    if (picked.bytes.length > ChatImages.maxImageBytes) {
      setState(() => _attachError = '图片超过 2MB 且无法压缩到界内');
      return;
    }
    setState(() => _pendingImages.add(picked));
  }

  /// 文件上行：POST /api/artifacts（核心差距：core 未实现 → 404 特性探测降级，
  /// 显式提示「未实现」，不伪装失败重试）。
  Future<void> _pickAndUploadFile() async {
    setState(() => _attachError = null);
    final picked = await _picker.pickFile();
    if (!mounted || picked == null) return;
    try {
      await _artifacts.upload(
        bytes: picked.bytes,
        name: picked.filename,
        contentType: picked.mime,
      );
      if (!mounted) return;
      // 上行成功：暂以提示呈现（chat.send 的 artifact 引用为契约目标态，
      // 当前 core 面未实现上行，走不到这里）
      setState(() => _attachError = '文件已上传（artifact 引用发送为目标态，本期请直接发图片）');
    } on ArtifactException catch (e) {
      if (mounted) setState(() => _attachError = e.toString());
    }
  }

  void _openAttachmentPicker() {
    AttachmentPicker.show(
      context,
      onTakePhoto: () => _pickImage(_picker.takePhoto),
      onPickImage: () => _pickImage(_picker.pickImage),
      onPickFile: _pickAndUploadFile,
    );
  }

  void _openPersonaSettings() {
    final api = _session.api;
    if (api == null) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => PersonaSettingsPage(
          conversationId: widget.conversationId,
          api: api,
        ),
      ),
    );
  }

  void _openApprovals() {
    final approvals = _approvals;
    if (approvals == null) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ApprovalsPage(controller: approvals),
      ),
    );
  }

  /// artifact 引用点击：GET /api/artifacts/{id}（Bearer）预览/下载。
  Future<void> _openArtifact(String artifactId) async {
    final headers = _artifactHeaders;
    final url = '$_artifactBase$artifactId';
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => Scaffold(
          appBar: AppBar(title: Text(artifactId)),
          body: Center(
            child: InteractiveViewer(
              child: Image.network(url, headers: headers,
                  errorBuilder: (context, error, stack) =>
                      const Text('产物加载失败（非图片或网络不可达）')),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _resolveArtifactAuth() async {
    try {
      final headers = await _artifacts.authHeaders();
      final uri = await _artifacts.artifactUri('placeholder');
      if (!mounted) return;
      setState(() {
        _artifactHeaders = headers;
        _artifactBase = uri.toString().replaceAll('placeholder', '');
      });
    } catch (_) {/* 配置缺失时产物加载失败会显式占位 */}
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title.isNotEmpty
            ? widget.title
            : widget.conversationId.replaceFirst('mobile:', '')),
        actions: [
          // 审批入口（待决数角标）
          IconButton(
            tooltip: '审批',
            icon: Badge(
              isLabelVisible: (_approvals?.pending.length ?? 0) > 0,
              label: Text('${_approvals?.pending.length ?? 0}'),
              child: const Icon(Icons.gpp_maybe),
            ),
            onPressed: _openApprovals,
          ),
          IconButton(
            tooltip: '角色设置',
            icon: const Icon(Icons.person),
            onPressed: _openPersonaSettings,
          ),
        ],
      ),
      body: Column(
        children: [
          _connectionBanner(),
          if (controller?.error != null) _errorBanner(controller!.error!),
          _approvalBanner(),
          Expanded(child: _messageList()),
          if (_attachError != null) _attachBanner(),
          if (_pendingImages.isNotEmpty) _pendingBar(),
          _inputBar(),
          if (_stickerOpen)
            StickerPicker(onSelect: (key) {
              // 占位贴图：以 [贴纸:key] 文本插入输入框
              _input.text = '${_input.text}[贴纸:$key]';
              _input.selection =
                  TextSelection.collapsed(offset: _input.text.length);
            }),
        ],
      ),
    );
  }

  /// 连接状态横幅（断线显式可见；SSE 退路提示）。
  Widget _connectionBanner() {
    final session = _session;
    final state = session.state;
    if (session.error != null) {
      return _banner(
        '连接已断开：${session.error}（可在「我的 → Core 连接设置」核对后重进本页）',
        isError: true,
      );
    }
    switch (state) {
      case LinkState.connected:
        return const SizedBox.shrink();
      case LinkState.degraded:
        return _banner('连接中断，重连中…（发送将走一次性通道）', isError: false);
      case LinkState.connecting:
        return _banner('连接中…', isError: false);
      case LinkState.disconnected:
        return _banner('未连接：消息将走一次性通道（SSE）', isError: false);
    }
  }

  /// 待决审批横幅（手机可见可裁决，M1）。
  Widget _approvalBanner() {
    final pending = _approvals?.pending ?? const [];
    if (pending.isEmpty) return const SizedBox.shrink();
    final a = pending.first;
    return Material(
      color: Theme.of(context).colorScheme.errorContainer,
      child: InkWell(
        onTap: _openApprovals,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              const Icon(Icons.gpp_maybe, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '待审批：${a.displayTitle}（${pending.length}）',
                  style: const TextStyle(fontSize: 13),
                ),
              ),
              const Icon(Icons.chevron_right, size: 18),
            ],
          ),
        ),
      ),
    );
  }

  Widget _banner(String text, {required bool isError}) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: isError
          ? scheme.errorContainer
          : scheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            Icon(isError ? Icons.error_outline : Icons.cloud_off, size: 18),
            const SizedBox(width: 8),
            Expanded(child: Text(text, style: const TextStyle(fontSize: 12))),
            if (isError)
              TextButton(
                onPressed: _session.reconnect,
                child: const Text('重连'),
              ),
          ],
        ),
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
              onPressed: _controller?.refresh,
              child: const Text('刷新'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _messageList() {
    final controller = _controller;
    if (controller == null || (controller.loadingInitial && controller.messages.isEmpty)) {
      return const Center(child: CircularProgressIndicator());
    }
    if (controller.messages.isEmpty) {
      return const Center(child: Text('发一条消息开始对话吧'));
    }
    return ListView.builder(
      controller: _scroll,
      reverse: true, // index 0 = 最新（视觉底部）
      itemCount: controller.messages.length + (controller.loadingOlder ? 1 : 0),
      itemBuilder: (context, i) {
        if (i >= controller.messages.length) {
          return const Padding(
            padding: EdgeInsets.all(12),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        final m = controller.messages[i];
        return MessageBubble(
          message: m,
          statusLine:
              m.sendState == MessageSendState.streaming ? controller.statusLine : null,
          onArtifactTap: _openArtifact,
        );
      },
    );
  }

  Widget _attachBanner() {
    return Container(
      width: double.infinity,
      color: Theme.of(context).colorScheme.errorContainer,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Text(_attachError!, style: const TextStyle(fontSize: 12)),
    );
  }

  Widget _pendingBar() {
    return SizedBox(
      height: 56,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        children: [
          for (final img in _pendingImages)
            Padding(
              padding: const EdgeInsets.all(6),
              child: Chip(
                avatar: const Icon(Icons.image, size: 16),
                label: Text(
                  '图片 ${(img.bytes.length / 1024).toStringAsFixed(0)}KB',
                  style: const TextStyle(fontSize: 12),
                ),
                onDeleted: () => setState(() => _pendingImages.remove(img)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _inputBar() {
    final controller = _controller;
    final busy = controller?.sending ?? false;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            IconButton(
              tooltip: '图片/文件',
              icon: const Icon(Icons.attach_file),
              onPressed: busy ? null : _openAttachmentPicker,
            ),
            IconButton(
              tooltip: '表情包',
              icon: Icon(_stickerOpen
                  ? Icons.keyboard
                  : Icons.emoji_emotions_outlined),
              onPressed: () => setState(() => _stickerOpen = !_stickerOpen),
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
            if (busy && (controller?.canCancel ?? false))
              IconButton(
                tooltip: '打断',
                icon: const Icon(Icons.stop_circle_outlined),
                onPressed: controller?.cancelActive,
              )
            else
              IconButton(
                tooltip: '发送',
                icon: const Icon(Icons.send),
                onPressed: busy ? null : _send,
              ),
          ],
        ),
      ),
    );
  }
}
