import 'package:flutter/material.dart';

import '../../../transport/link/link_client.dart';
import '../models/persona.dart';
import '../services/link_chat_api.dart';

/// 角色设置页（v3）：切换会话角色。
///
/// 列表走 `personas.list`（Link 白名单内）；绑定走
/// `conversations.update {id, persona_id}`（mobile 白名单含 conversations.*）。
/// 记忆空间/工具能力由 Core 按角色授权，端侧只展示，不越权设置。
class PersonaSettingsPage extends StatefulWidget {
  const PersonaSettingsPage({
    super.key,
    required this.conversationId,
    this.currentPersonaId,
    required this.api,
  });

  final String conversationId;

  /// 当前绑定（会话列表带过来的 persona_id；null = 默认角色）。
  final String? currentPersonaId;

  /// 聊天面后端（生产=LinkChatApi；测试注入假实现）。
  final ChatBackend api;

  @override
  State<PersonaSettingsPage> createState() => _PersonaSettingsPageState();
}

class _PersonaSettingsPageState extends State<PersonaSettingsPage> {
  List<Persona>? _personas;
  String? _selected; // null = 默认角色
  bool _loading = true;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _selected = widget.currentPersonaId;
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final personas = await widget.api.listPersonas();
      if (!mounted) return;
      setState(() {
        _personas = personas;
        _loading = false;
      });
    } on LinkError catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = e.toString();
        });
      }
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await widget.api.updateConversation(widget.conversationId,
          personaId: _selected ?? '');
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('角色已更新')));
      Navigator.of(context).pop(_selected);
    } on LinkError catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('角色设置'),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: Text(_saving ? '保存中…' : '保存'),
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('加载角色失败：$_error', textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    final personas = _personas ?? const <Persona>[];
    return RadioGroup<String?>(
      groupValue: _selected,
      onChanged: (v) => setState(() => _selected = v),
      child: ListView(
        children: [
          const RadioListTile<String?>(
            title: Text('默认角色'),
            subtitle: Text('使用 Core 默认人格与记忆空间'),
            value: null,
          ),
          const Divider(),
          for (final p in personas)
            RadioListTile<String?>(
              title: Text(p.displayName),
              subtitle: Text([
                if (p.tagline.isNotEmpty) p.tagline,
                if (p.personality.isNotEmpty) '性格：${p.personality}',
                if (p.builtin) '内置角色',
              ].join('\n')),
              secondary:
                  p.avatar.isNotEmpty ? Text(p.avatar, style: const TextStyle(fontSize: 20)) : null,
              value: p.id,
            ),
          if (personas.isEmpty)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('Core 暂无自定义角色；可在 Core 工作台创建人物卡。'),
            ),
        ],
      ),
    );
  }
}
