import 'package:flutter/material.dart';

import '../../../transport/transport_client_v2.dart';
import '../../chat/services/device_config.dart';
import '../services/pairing_service.dart';

/// Core 连接设置页：base_url + Bearer token（聊天 REST 用）+ owner-first 设备配对。
///
/// 配对（2026-11-23 clean break）：主人在 core 控制台「设备 → 配对新设备」生成
/// 6 位码 → 本页输入 → POST {base}/api/devices/pair {code, pubkey_hex, label}
/// → 立即激活并以应答 ws_path 建立 WSS 长连。旧「设备上屏出码等批准」入口
/// （device-first）已随 core 删除。
class CoreSettingsPage extends StatefulWidget {
  const CoreSettingsPage({super.key, this.pairingService, this.transport});

  /// 测试注入点（生产为 null → 内部自建）。
  final PairingService? pairingService;
  final TransportClientV2? transport;

  @override
  State<CoreSettingsPage> createState() => _CoreSettingsPageState();
}

class _CoreSettingsPageState extends State<CoreSettingsPage> {
  final TextEditingController _baseUrl = TextEditingController();
  final TextEditingController _token = TextEditingController();
  final TextEditingController _pairCode = TextEditingController();
  final TextEditingController _pairLabel = TextEditingController();
  late final PairingService _pairingService =
      widget.pairingService ?? PairingService();
  late final TransportClientV2 _transport =
      widget.transport ?? TransportClientV2();
  bool _loading = true;
  bool _saved = false;

  // ---- 配对状态 ----
  bool _pairing = false;
  String? _pairError;
  PairingResult? _pairResult;

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

  /// owner-first 配对：先落库 base_url（配对 POST 与后续 WS 共用它），再兑换
  /// 配对码，成功后以应答 ws_path 拼出的 WS 全路径发起连接（原生侧持久化
  /// gateway_url 并清撤销标记）。
  Future<void> _pairAndConnect() async {
    setState(() {
      _pairing = true;
      _pairError = null;
      _pairResult = null;
    });
    try {
      await DeviceConfig.save(baseUrl: _baseUrl.text);
      final result = await _pairingService.pair(
        code: _pairCode.text,
        label: _pairLabel.text,
      );
      await _transport.connect(result.wsUrl);
      if (!mounted) return;
      setState(() {
        _pairResult = result;
        _pairing = false;
      });
      _pairCode.clear();
    } on PairingException catch (e) {
      if (!mounted) return;
      setState(() {
        _pairError = _pairErrorText(e);
        _pairing = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _pairError = '配对后连接发起失败: $e';
        _pairing = false;
      });
    }
  }

  String _pairErrorText(PairingException e) => switch (e.code) {
        'invalid_code' => e.message,
        'not_found' => '配对码无效或已使用：请主人在控制台重新生成',
        'conflict' => '配对码已过期：请主人在控制台重新生成',
        'rate_limited' => '尝试次数过多：请稍后用新码重试',
        'identity_unavailable' => '设备身份不可用（原生桥未就绪）',
        'identity_mismatch' => 'core 返回身份与本机不一致，已中止',
        'network_error' => '网络不可达：请核对 Core 地址与本机网络',
        _ => '配对失败：$e',
      };

  @override
  void dispose() {
    _baseUrl.dispose();
    _token.dispose();
    _pairCode.dispose();
    _pairLabel.dispose();
    if (widget.pairingService == null) _pairingService.close();
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
                    hintText: 'http://<core-host>:8193',
                    helperText: '聊天 REST 与设备配对（POST /api/devices/pair）的基地址',
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
                const SizedBox(height: 24),
                const Divider(),
                const SizedBox(height: 8),
                _buildPairingCard(),
              ],
            ),
    );
  }

  /// owner-first 配对卡：输入主人控制台出示的 6 位码 → 兑换 → 连接。
  Widget _buildPairingCard() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('设备配对',
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        Text(
          '主人在 Core 控制台「设备 → 配对新设备」生成 6 位码后，在此输入完成配对'
          '（免令牌，码本身即授权）。配对成功立即以上方 Core 地址建立长连。',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.outline,
              ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _pairCode,
          keyboardType: TextInputType.number,
          maxLength: 6,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            labelText: '配对码（6 位数字）',
            hintText: '主人控制台出示的 6 位码',
            border: OutlineInputBorder(),
            counterText: '',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _pairLabel,
          decoration: const InputDecoration(
            labelText: '设备备注（可选）',
            hintText: '如：magic8 日常机；留空用控制台命名',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: _pairing ? null : _pairAndConnect,
          icon: _pairing
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.link),
          label: Text(_pairing ? '配对中…' : '配对并连接'),
        ),
        if (_pairError != null) ...[
          const SizedBox(height: 8),
          Text(
            _pairError!,
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.error),
          ),
        ],
        if (_pairResult != null) ...[
          const SizedBox(height: 8),
          Text(
            '配对成功并已发起连接\n'
            '设备名称：${_pairResult!.name.isEmpty ? '（未命名）' : _pairResult!.name}\n'
            'device_id：${_pairResult!.deviceId}\n'
            '能力范围：${_pairResult!.scopes.length} 项\n'
            'WS 路径：${_pairResult!.wsPath}',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                ),
          ),
        ],
      ],
    );
  }
}
