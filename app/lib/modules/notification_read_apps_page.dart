import 'package:flutter/material.dart';

import '../transport/transport_client.dart';

/// 通知监听 App 白名单多选页（B3 前半，从简）：
/// 列出带 LAUNCHER 入口的已安装 App（checkbox 列表），勾选集落
/// 原生 whale_notif_read/allowed_apps（默认空 = 一个都不读）。
class NotificationReadAppsPage extends StatefulWidget {
  const NotificationReadAppsPage({super.key, required this.transport});

  final TransportClient transport;

  @override
  State<NotificationReadAppsPage> createState() =>
      _NotificationReadAppsPageState();
}

class _NotificationReadAppsPageState extends State<NotificationReadAppsPage> {
  List<({String package, String label})>? _apps; // null = 加载中
  Set<String> _selected = {};
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final apps = await widget.transport.getListenableApps();
      final allowed = await widget.transport.getNotificationReadAllowedApps();
      if (!mounted) return;
      setState(() {
        _apps = apps;
        _selected = allowed;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _apps = const []);
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await widget.transport.setNotificationReadAllowedApps(_selected);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('白名单已保存（${_selected.length} 个 App）')),
      );
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('保存失败: $e')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps;
    return Scaffold(
      appBar: AppBar(
        title: const Text('监听白名单'),
        actions: [
          TextButton(
            onPressed: _saving || apps == null ? null : _save,
            child: Text(_saving ? '保存中…' : '保存'),
          ),
        ],
      ),
      body: apps == null
          ? const Center(child: CircularProgressIndicator())
          : apps.isEmpty
              ? const Center(child: Text('未发现可监听 App'))
              : Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                      child: Text(
                        '仅勾选的 App 会产生通知事件（标题摘要，不含正文）；'
                        '已选 ${_selected.length} 个',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                    Expanded(
                      child: ListView.builder(
                        itemCount: apps.length,
                        itemBuilder: (context, i) {
                          final a = apps[i];
                          final checked = _selected.contains(a.package);
                          return CheckboxListTile(
                            dense: true,
                            title: Text(a.label),
                            subtitle: Text(a.package,
                                style:
                                    Theme.of(context).textTheme.bodySmall),
                            value: checked,
                            onChanged: (v) {
                              setState(() {
                                if (v == true) {
                                  _selected.add(a.package);
                                } else {
                                  _selected.remove(a.package);
                                }
                              });
                            },
                          );
                        },
                      ),
                    ),
                  ],
                ),
    );
  }
}
