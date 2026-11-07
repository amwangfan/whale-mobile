import 'dart:async';

import 'package:flutter/material.dart';

import 'features/chat/pages/conversation_list.dart';
import 'features/notifications/inbox_page.dart';
import 'features/settings/pages/core_settings_page.dart';
import 'navigation/app_navigation.dart';
import 'navigation/deep_link_service.dart';
import 'features/screenmemo/pages/timeline_page.dart';
import 'modules/module_overview_page.dart';

void main() {
  runApp(const WhaleApp());
}

/// v2 应用入口：MaterialApp + 全局导航键（深链路由用）。
class WhaleApp extends StatelessWidget {
  const WhaleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Whale',
      navigatorKey: AppNavigation.navigatorKey,
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: const HomeShell(),
    );
  }
}

/// 入口壳：底部导航（聊天 / 感知 / 通知 / 我的）。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  final DeepLinkService _deepLink = DeepLinkService();

  @override
  void initState() {
    super.initState();
    AppNavigation.tabIndex.addListener(_onTabChanged);
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
    AppNavigation.tabIndex.removeListener(_onTabChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final index = AppNavigation.tabIndex.value;
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
        onDestinationSelected: (i) => AppNavigation.switchTab(i),
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
