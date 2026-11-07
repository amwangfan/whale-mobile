import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../features/chat/pages/chat_page.dart';
import 'app_navigation.dart';

/// Resolve Android notification deep links to Flutter pages.
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
    final navigator = AppNavigation.navigatorKey.currentState;
    final context = AppNavigation.navigatorKey.currentContext;
    if (navigator == null) return false;

    if (route == '/' || route.isEmpty) return true; // 首页
    if (route == '/chat') {
      AppNavigation.switchTab(0);
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
      AppNavigation.switchTab(3);
      return true;
    }
    if (route == '/approval' || route.startsWith('/approval/')) {
      AppNavigation.switchTab(2);
      return true;
    }
    if (context != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('未知路由 $route，已回到首页')),
      );
    }
    AppNavigation.switchTab(0);
    return false;
  }
}

