import 'package:flutter/material.dart';

import '../../chat/services/device_config.dart';

/// Core 连接设置页：base_url + Bearer token（聊天 REST 用）。
class CoreSettingsPage extends StatefulWidget {
  const CoreSettingsPage({super.key});

  @override
  State<CoreSettingsPage> createState() => _CoreSettingsPageState();
}

class _CoreSettingsPageState extends State<CoreSettingsPage> {
  final TextEditingController _baseUrl = TextEditingController();
  final TextEditingController _token = TextEditingController();
  bool _loading = true;
  bool _saved = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final base = await DeviceConfig.baseUrl();
    final token = await DeviceConfig.apiToken();
    if (!mounted) return;
    setState(() {
      _baseUrl.text = base;
      _token.text = token;
      _loading = false;
    });
  }

  Future<void> _save() async {
    await DeviceConfig.save(baseUrl: _baseUrl.text, token: _token.text);
    if (!mounted) return;
    setState(() => _saved = true);
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('已保存')));
  }

  @override
  void dispose() {
    _baseUrl.dispose();
    _token.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Core 连接设置')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                TextField(
                  controller: _baseUrl,
                  decoration: const InputDecoration(
                    labelText: 'Core 地址',
                    hintText: 'http://<core-host>:8080',
                    helperText: '聊天 REST（/api/v2/*）的基地址',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _token,
                  obscureText: true,
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: const InputDecoration(
                    labelText: '访问令牌（Bearer）',
                    helperText: 'Core 管理 API token；留空则请求会返回 401',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _save,
                  child: Text(_saved ? '已保存（可再次修改）' : '保存'),
                ),
              ],
            ),
    );
  }
}
