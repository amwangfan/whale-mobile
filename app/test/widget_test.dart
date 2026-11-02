import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:whale_mobile/main.dart';

/// A1 模块总览主页 widget 测试。
/// MethodChannel `whale/transport` 全程 mock，权限/开关/token 状态确定性可控。
/// 注意：ListView 离屏卡片会被卸载，所有卡片内断言均先滚动定位、再做卡片内 scoped find。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('whale/transport');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  /// 可调参数的原生侧 mock：[permission] 通知/VoIP 权限；
  /// [notifReadPermission] 通知监听特殊访问权限；[calls] 记录调用序列。
  void mockNative({
    String permission = 'granted',
    String? notifReadPermission,
    List<MethodCall>? calls,
  }) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls?.add(call);
      switch (call.method) {
        case 'getIdentity':
        case 'connect':
          return <String, dynamic>{
            'device_id': 'test-device',
            'public_key_hex': 'ab12',
          };
        case 'getState':
          return <String, dynamic>{
            'state': 'disconnected',
            'queue_pending': 0,
            'queue_sent': 0,
            'queue_acked': 0,
            'queue_dropped': 0,
          };
        case 'getModuleStates':
          return <String, dynamic>{'notification_post': true, 'voip': false};
        case 'getNotificationPermissionStatus':
          return permission;
        case 'requestNotificationPermission':
          return permission;
        case 'getVoipPermissionStatus':
          return permission;
        case 'requestVoipPermissions':
          return permission;
        case 'getVoipBridgeConfig':
          return <String, dynamic>{'host': '127.0.0.1', 'port': 18799};
        case 'getNotificationReadPermissionStatus':
          return notifReadPermission ?? 'granted';
        case 'getNotificationReadAllowedApps':
          return <String>['com.example.alpha'];
        case 'getListenableApps':
          return <Map<String, String>>[
            {'package': 'com.example.alpha', 'label': 'Alpha'},
            {'package': 'com.example.beta', 'label': 'Beta'},
          ];
        case 'isAllPaused':
          return false;
        case 'getMcpToken':
          return 'test-token'; // 已配置 token → screenmemo 可达 enabled
        default:
          return null; // setModuleEnabled/setAllPaused/disconnect/setSensorQueryDelegate...
      }
    });
  }

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    mockNative();
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  /// 滚动到指定文本并稳定。
  Future<void> scrollTo(WidgetTester tester, String text) async {
    await tester.scrollUntilVisible(
      find.text(text),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
  }

  /// 模块卡片（按模块名定位最近的 Card 祖先）。
  Finder cardOf(String moduleName) => find.ancestor(
        of: find.text(moduleName),
        matching: find.byType(Card),
      );

  /// 断言某模块卡片内存在指定文本（徽章/元信息/状态行）。
  Future<void> expectInCard(
      WidgetTester tester, String moduleName, String text) async {
    await scrollTo(tester, moduleName);
    expect(
      find.descendant(of: cardOf(moduleName), matching: find.text(text)),
      findsOneWidget,
      reason: '$moduleName 卡片内应显示「$text」',
    );
  }

  testWidgets('模块总览主页：状态条 + 六模块卡片 + 状态徽章', (tester) async {
    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    expect(find.text('Whale 模块总览'), findsOneWidget);
    // 顶部传输状态条（未连接过 → 已断开）
    expect(find.text('传输：已断开'), findsOneWidget);

    // 传输卡片：未连接过 → 已安装；无权限需求、常驻、公开级
    await expectInCard(tester, '传输通道', '已安装');
    await expectInCard(tester, '传输通道', '隐私: 公开');
    await expectInCard(tester, '传输通道', '无系统权限');

    // 通知卡片：权限授予 + 开关开 → 已启用；声明 POST_NOTIFICATIONS
    await expectInCard(tester, '通知推送', '已启用');
    await expectInCard(tester, '通知推送', '权限: POST_NOTIFICATIONS');
    await expectInCard(tester, '通知推送', '隐私: 机主隐私');

    // 通知监听卡片（B3）：权限授予 + 开关默认关 → 已停用；机主敏感、非常驻
    await expectInCard(tester, '通知监听', '已停用');
    await expectInCard(tester, '通知监听', '权限: 通知监听');
    await expectInCard(tester, '通知监听', '隐私: 机主敏感');
    await expectInCard(tester, '通知监听', '非常驻');

    // VoIP 卡片（B4）：权限授予 + 开关默认关 → 已停用；机主敏感、非常驻
    await expectInCard(tester, 'VoIP 通话', '已停用');
    await expectInCard(tester, 'VoIP 通话', '权限: RECORD_AUDIO/READ_PHONE_STATE');
    await expectInCard(tester, 'VoIP 通话', '隐私: 机主敏感');
    await expectInCard(tester, 'VoIP 通话', '非常驻');

    // 设备动作卡片（B3）：无特殊权限项 + 开关默认关 → 已停用；机主隐私
    await expectInCard(tester, '设备动作', '已停用');
    await expectInCard(tester, '设备动作', '无系统权限');
    await expectInCard(tester, '设备动作', '隐私: 机主隐私');

    // ScreenMemo 卡片：token 已配置未验证 → 已启用；无系统权限、非常驻、机主敏感
    await expectInCard(tester, 'ScreenMemo 屏幕记忆', '已启用');
    await expectInCard(tester, 'ScreenMemo 屏幕记忆', '无系统权限');
    await expectInCard(tester, 'ScreenMemo 屏幕记忆', '非常驻');
    await expectInCard(tester, 'ScreenMemo 屏幕记忆', '隐私: 机主敏感');
  });

  testWidgets('通知卡片：权限未授予显示「待授权」徽章与「去授权」按钮', (tester) async {
    final calls = <MethodCall>[];
    mockNative(permission: 'denied', calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    await expectInCard(tester, '通知推送', '待授权');
    await expectInCard(tester, '通知推送', '去授权');

    // 点击通知卡片内的「去授权」（VoIP 卡片也有同名按钮，需卡片内 scoped）
    await scrollTo(tester, '通知推送');
    await tester.tap(find.descendant(
      of: cardOf('通知推送'),
      matching: find.text('去授权'),
    ));
    await tester.pumpAndSettle(const Duration(seconds: 2));
    expect(
      calls.any((c) => c.method == 'requestNotificationPermission'),
      isTrue,
    );
  });

  testWidgets('通知监听卡片：权限未授予显示「待授权」与「去授权」（跳系统设置）',
      (tester) async {
    final calls = <MethodCall>[];
    mockNative(notifReadPermission: 'denied', calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    await expectInCard(tester, '通知监听', '待授权');
    await scrollTo(tester, '通知监听');
    await tester.tap(find.descendant(
      of: cardOf('通知监听'),
      matching: find.text('去授权'),
    ));
    // 冲掉「返回后延迟 3s 重查权限」的 timer（与通知权限申请测试同模式）
    await tester.pumpAndSettle(const Duration(seconds: 4));
    expect(
      calls.any((c) => c.method == 'openNotificationListenerSettings'),
      isTrue,
    );
  });

  testWidgets('设备动作卡片：开关调用 setModuleEnabled(safe_action)',
      (tester) async {
    final calls = <MethodCall>[];
    mockNative(calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    await expectInCard(tester, '设备动作', '已停用'); // 默认关
    await scrollTo(tester, '设备动作');
    await tester.tap(find.descendant(
      of: cardOf('设备动作'),
      matching: find.byType(Switch),
    ));
    await tester.pumpAndSettle();
    expect(
      calls.any((c) =>
          c.method == 'setModuleEnabled' &&
          c.arguments['module'] == 'safe_action' &&
          c.arguments['enabled'] == true),
      isTrue,
    );
    await expectInCard(tester, '设备动作', '已启用');
  });

  testWidgets('通知监听白名单页：勾选后保存写原生侧', (tester) async {
    final calls = <MethodCall>[];
    mockNative(calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    // 进入白名单多选页
    await scrollTo(tester, '通知监听');
    await tester.tap(find.descendant(
      of: cardOf('通知监听'),
      matching: find.text('监听白名单'),
    ));
    await tester.pumpAndSettle();

    expect(find.text('监听白名单'), findsWidgets); // AppBar 标题
    expect(find.text('Alpha'), findsOneWidget);
    expect(find.text('Beta'), findsOneWidget);

    // Alpha 已勾选（mock 白名单含 com.example.alpha），再勾 Beta
    final betaTile = find.ancestor(
      of: find.text('Beta'),
      matching: find.byType(CheckboxListTile),
    );
    expect(tester.widget<CheckboxListTile>(betaTile).value, isFalse);
    await tester.tap(find.text('Beta'));
    await tester.pumpAndSettle();
    expect(tester.widget<CheckboxListTile>(betaTile).value, isTrue);

    // 保存 → setNotificationReadAllowedApps{alpha,beta} 并返回
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    final saveCall = calls.firstWhere(
      (c) => c.method == 'setNotificationReadAllowedApps',
    );
    final apps =
        (saveCall.arguments['apps'] as List).cast<String>().toSet();
    expect(apps, {'com.example.alpha', 'com.example.beta'});
    // 已返回总览页（白名单页的 App 列表项不再存在）
    expect(find.text('Beta'), findsNothing);
    expect(find.text('com.example.alpha'), findsNothing);
  });

  testWidgets('模块卡片开关调用 setModuleEnabled 并更新徽章', (tester) async {
    final calls = <MethodCall>[];
    mockNative(calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    // 通知卡片开关：关闭 → setModuleEnabled(notification_post, false) + 徽章「已停用」
    await scrollTo(tester, '通知推送');
    final notifSwitch = find.descendant(
      of: cardOf('通知推送'),
      matching: find.byType(Switch),
    );
    await tester.tap(notifSwitch);
    await tester.pumpAndSettle();
    expect(
      calls.any((c) =>
          c.method == 'setModuleEnabled' &&
          c.arguments['module'] == 'notification_post' &&
          c.arguments['enabled'] == false),
      isTrue,
    );
    await expectInCard(tester, '通知推送', '已停用');
  });

  testWidgets('一键暂停：断开传输+关闭所有开关+落原生标志；恢复逆向', (tester) async {
    final calls = <MethodCall>[];
    mockNative(calls: calls);

    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    // 暂停（AppBar）
    await tester.tap(find.byIcon(Icons.pause));
    await tester.pumpAndSettle();

    // 六模块徽章收束 stopped，开关禁用且为关
    for (final name in [
      '传输通道',
      '通知推送',
      '通知监听',
      'VoIP 通话',
      '设备动作',
      'ScreenMemo 屏幕记忆',
    ]) {
      await expectInCard(tester, name, '已停止');
      await scrollTo(tester, name);
      // ScreenMemo 卡片内有第二个开关（事件导出）；首个是模块总开关。
      final swFinder =
          find.descendant(of: cardOf(name), matching: find.byType(Switch));
      final sw = tester.widget<Switch>(
        name == 'ScreenMemo 屏幕记忆' ? swFinder.first : swFinder,
      );
      expect(sw.onChanged, isNull, reason: '$name 开关应在暂停时禁用');
      expect(sw.value, isFalse, reason: '$name 开关应在暂停时关闭');
    }

    // 暂停横幅（在传输卡片上方）+ AppBar 变为恢复
    await tester.scrollUntilVisible(
      find.textContaining('已全局暂停'),
      -200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('已全局暂停'), findsOneWidget);
    expect(find.byIcon(Icons.play_arrow), findsOneWidget);

    // 原生侧：setAllPaused(true) + 两个模块开关关闭 + disconnect
    expect(
      calls.any(
          (c) => c.method == 'setAllPaused' && c.arguments['paused'] == true),
      isTrue,
    );
    expect(
      calls
          .where((c) =>
              c.method == 'setModuleEnabled' && c.arguments['enabled'] == false)
          .length,
      greaterThanOrEqualTo(2),
    );
    expect(calls.any((c) => c.method == 'disconnect'), isTrue);

    // 恢复：逆向（清标志 + 还原暂停前为开的模块开关）
    await tester.tap(find.text('恢复全部'));
    await tester.pumpAndSettle();
    expect(find.textContaining('已全局暂停'), findsNothing);
    expect(
      calls.any(
          (c) => c.method == 'setAllPaused' && c.arguments['paused'] == false),
      isTrue,
    );
    expect(
      calls
          .where((c) =>
              c.method == 'setModuleEnabled' && c.arguments['enabled'] == true)
          .length,
      greaterThanOrEqualTo(2),
    );
    // 暂停前传输未连接 → 恢复不回连
    expect(calls.any((c) => c.method == 'connect'), isFalse);
    // 徽章回到推导态
    await expectInCard(tester, '通知推送', '已启用');
  });

  testWidgets('可折叠连接测试区与日志区', (tester) async {
    await tester.pumpWidget(const WhaleApp());
    await tester.pumpAndSettle();

    // 连接测试区（折叠态）：展开后可见网关地址/连接/断开/测试事件
    await scrollTo(tester, '连接测试');
    await tester.tap(find.text('连接测试'));
    await tester.pumpAndSettle();
    await scrollTo(tester, '发送测试 sensor.event');
    expect(find.text('网关地址'), findsOneWidget);
    expect(find.text('连接'), findsOneWidget);
    expect(find.text('断开'), findsOneWidget);
    expect(find.text('发送测试 sensor.event'), findsOneWidget);

    // 日志区
    await scrollTo(tester, '日志');
    await tester.tap(find.text('日志'));
    await tester.pumpAndSettle();
    expect(find.textContaining('暂无日志'), findsOneWidget);
  });
}
