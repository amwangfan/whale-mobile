import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'features/chat/pages/chat_page.dart';
import 'features/chat/pages/conversation_list.dart';
import 'features/chat/services/device_config.dart';
import 'features/notifications/inbox_page.dart';
import 'features/screenmemo/pages/timeline_page.dart';
import 'modules/module_overview_page.dart';

void main() {
  runApp(const WhaleApp());
}

/// v2 应用入口：MaterialApp + 全局导航键（深链路由用）。
class WhaleApp extends StatelessWidget {
  const WhaleApp({super.key});

  /// 全局 NavigatorKey：深链（通知点击）在任意时刻路由到目标页。
  static final GlobalKey<NavigatorState> navigatorKey =
      GlobalKey<NavigatorState>();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Whale',
      navigatorKey: navigatorKey,
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: const HomeShell(),
    );
  }
}

/// 深链路由（修正方案 N2 §3.3 Flutter 侧）。
///
/// 原生 NotificationDeepLinkRouter 把目标路由写进启动 intent extras
/// （whale_notification_route 等）；Flutter 侧经 `whale/transport` 通道
/// `getLaunchRoute` 读取并消费（**新约定方法**，原生侧注册见开发报告）。
///
/// 路由表（Flutter 是路由事实源）：
/// - `/`                 首页（聊天 tab）
/// - `/chat`             聊天 tab
/// - `/conversation/<id>` 聊天页
/// - `/task/<id>`        任务占位详情（任务 UI 属后续阶段，先显式展示 id）
/// - `/device` / `/device/<id>`  「我的」tab
/// - `/approval/<id>`    通知收件箱（审批类通知在此处理）
/// - 未知路由            落首页（不静默吞掉：Snackbar 提示）
class DeepLinkService {
  DeepLinkService({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('whale/transport');

  final MethodChannel _channel;
  bool _consumed = false;

  /// 冷启动读取一次启动路由并消费（原生侧读后应清除，避免重复路由）。
  Future<String?> consumeLaunchRoute() async {
    if (_consumed) return null;
    _consumed = true;
    try {
      final res = await _channel
          .invokeMethod<Map<dynamic, dynamic>>('getLaunchRoute');
      return res?['route'] as String?;
    } on MissingPluginException {
      return null; // 原生侧未注册（开发期）
    } on PlatformException {
      return null;
    }
  }

  /// 解析路由并导航。未知路由返回 false（调用方落首页并提示）。
  static bool navigate(String route) {
    final navigator = WhaleApp.navigatorKey.currentState;
    final context = WhaleApp.navigatorKey.currentContext;
    if (navigator == null) return false;

    if (route == '/' || route.isEmpty) return true; // 首页
    if (route == '/chat') {
      HomeShell.switchTab(0);
      return true;
    }
    if (route.startsWith('/conversation/')) {
      final id = Uri.decodeComponent(route.substring('/conversation/'.length));
      if (id.isEmpty) return false;
      navigator.push(MaterialPageRoute<void>(
        builder: (_) => ChatPage(conversationId: id),
      ));
      return true;
    }
    if (route.startsWith('/task/')) {
      final id = Uri.decodeComponent(route.substring('/task/'.length));
      navigator.push(MaterialPageRoute<void>(
        builder: (_) => Scaffold(
          appBar: AppBar(title: const Text('任务')),
          body: Center(
            child: Text('任务 $id\n（任务详情页属后续阶段，当前仅展示路由目标）',
                textAlign: TextAlign.center),
          ),
        ),
      ));
      return true;
    }
    if (route == '/device' || route.startsWith('/device/')) {
      HomeShell.switchTab(3);
      return true;
    }
    if (route == '/approval' || route.startsWith('/approval/')) {
      HomeShell.switchTab(2);
      return true;
    }
    if (context != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('未知路由 $route，已回到首页')),
      );
    }
    HomeShell.switchTab(0);
    return false;
  }
}

/// 入口壳：底部导航（聊天 / 感知 / 通知 / 我的）。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  /// 当前激活 tab（DeepLinkService 经此切换）。
  static final ValueNotifier<int> tabIndex = ValueNotifier<int>(0);

  static void switchTab(int index) => tabIndex.value = index;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  final DeepLinkService _deepLink = DeepLinkService();

  @override
  void initState() {
    super.initState();
    HomeShell.tabIndex.addListener(_onTabChanged);
    unawaited(_consumeDeepLink());
  }

  void _onTabChanged() {
    if (mounted) setState(() {});
  }

  /// 冷启动深链：等首帧后再导航。
  Future<void> _consumeDeepLink() async {
    final route = await _deepLink.consumeLaunchRoute();
    if (route == null || route.isEmpty) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      DeepLinkService.navigate(route);
    });
  }

  @override
  void dispose() {
    HomeShell.tabIndex.removeListener(_onTabChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final index = HomeShell.tabIndex.value;
    return Scaffold(
      body: IndexedStack(
        index: index,
        children: const [
          ConversationListPage(),
          TimelinePage(),
          InboxPage(),
          ProfilePage(),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => HomeShell.switchTab(i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.chat_bubble_outline), label: '聊天'),
          NavigationDestination(icon: Icon(Icons.burst_mode), label: '感知'),
          NavigationDestination(icon: Icon(Icons.notifications_outlined), label: '通知'),
          NavigationDestination(icon: Icon(Icons.person_outline), label: '我的'),
        ],
      ),
    );
  }
}

/// 「我的」：模块开关（旧 v1 模块总览迁入）+ Core 连接设置。
class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          ListTile(
            leading: const Icon(Icons.dashboard_customize),
            title: const Text('模块总览与开关（旧版）'),
            subtitle: const Text('传输通道 / 通知 / VoIP / 采集等模块管理'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                  builder: (_) => const ModuleOverviewPage()),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.cloud),
            title: const Text('Core 连接设置'),
            subtitle: const Text('聊天 REST 地址与访问令牌'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                  builder: (_) => const CoreSettingsPage()),
            ),
          ),
        ],
      ),
    );
  }
}

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
