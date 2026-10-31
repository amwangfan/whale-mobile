import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/modules/registry.dart';
import 'package:whale_mobile/transport/transport_client.dart';

/// A1 模块注册表状态机单测：三模块推导逻辑全覆盖 + 9 状态迁移关键路径 + 暂停标志。
void main() {
  group('内置登记（ModuleSpec 元数据）', () {
    test('登记六个模块：transport / notification_post / screenmemo / voip / '
        'notification_read / safe_action', () {
      final r = ModuleRegistry();
      expect(
          r.modules.keys,
          containsAll([
            'transport',
            'notification_post',
            'screenmemo',
            'voip',
            'notification_read',
            'safe_action',
          ]));

      final t = r['transport'];
      expect(t.androidPermissions, isEmpty); // 无权限需求
      expect(t.resident, isTrue); // 常驻
      expect(t.rootOptional, isFalse);
      expect(t.status, ModuleStatus.installed); // 初始态

      final n = r['notification_post'];
      expect(n.androidPermissions, ['POST_NOTIFICATIONS']);
      expect(n.needsRuntimePermission, isTrue);
      expect(n.resident, isTrue);

      final s = r['screenmemo'];
      expect(s.androidPermissions, isEmpty); // 无系统权限（需 token + App 存活）
      expect(s.resident, isFalse);
      expect(s.privacyLevel, PrivacyLevel.ownerSensitive);

      final v = r['voip'];
      expect(v.androidPermissions, ['RECORD_AUDIO', 'READ_PHONE_STATE']);
      expect(v.needsRuntimePermission, isTrue);
      expect(v.resident, isFalse); // 事件驱动，非常驻
      expect(v.privacyLevel, PrivacyLevel.ownerSensitive); // 通话号码/状态
      expect(v.status, ModuleStatus.installed); // 权限未知 → installed

      final nr = r['notification_read'];
      expect(nr.androidPermissions, ['通知监听']); // 特殊访问权限（非运行时）
      expect(nr.resident, isFalse); // 事件驱动（ListenerService 回调），非常驻
      expect(nr.privacyLevel, PrivacyLevel.ownerSensitive); // 通知标题
      expect(nr.status, ModuleStatus.installed); // 权限未知 → installed

      final sa = r['safe_action'];
      expect(sa.androidPermissions, isEmpty); // 无特殊系统权限项
      expect(sa.resident, isFalse); // 事件驱动 action 执行器
      expect(sa.privacyLevel, PrivacyLevel.ownerPrivate);
      expect(sa.status, ModuleStatus.installed); // 初始态（开关输入未注入）

      expect(() => r['unknown_module'], throwsArgumentError);
    });
  });

  group('transport 推导（stateStream 映射）', () {
    test('从未连接 → installed（state null 或 disconnected）', () {
      expect(
        deriveTransportStatus(
            state: null, everConnected: false, enabled: true),
        ModuleStatus.installed,
      );
      expect(
        deriveTransportStatus(
            state: TransportState.disconnected,
            everConnected: false,
            enabled: true),
        ModuleStatus.installed,
      );
    });

    test('connecting/awaitingAuth → starting', () {
      expect(
        deriveTransportStatus(
            state: TransportState.connecting,
            everConnected: true,
            enabled: true),
        ModuleStatus.starting,
      );
      expect(
        deriveTransportStatus(
            state: TransportState.awaitingAuth,
            everConnected: true,
            enabled: true),
        ModuleStatus.starting,
      );
    });

    test('online → running', () {
      expect(
        deriveTransportStatus(
            state: TransportState.online, everConnected: true, enabled: true),
        ModuleStatus.running,
      );
    });

    test('degraded → degraded', () {
      expect(
        deriveTransportStatus(
            state: TransportState.degraded,
            everConnected: true,
            enabled: true),
        ModuleStatus.degraded,
      );
    });

    test('连接过后断开 → stopped', () {
      expect(
        deriveTransportStatus(
            state: TransportState.disconnected,
            everConnected: true,
            enabled: true),
        ModuleStatus.stopped,
      );
    });

    test('开关关闭 → disabled（优先于连接态）', () {
      for (final s in TransportState.values) {
        expect(
          deriveTransportStatus(state: s, everConnected: true, enabled: false),
          ModuleStatus.disabled,
        );
      }
    });
  });

  group('notification_post 推导（权限 + 开关）', () {
    test('权限状态未知 → installed', () {
      expect(
        deriveNotificationStatus(permissionGranted: null, enabled: true),
        ModuleStatus.installed,
      );
    });

    test('未授予 → permissionRequired（无论开关）', () {
      expect(
        deriveNotificationStatus(permissionGranted: false, enabled: true),
        ModuleStatus.permissionRequired,
      );
      expect(
        deriveNotificationStatus(permissionGranted: false, enabled: false),
        ModuleStatus.permissionRequired,
      );
    });

    test('授予 + 开 → enabled；授予 + 关 → disabled', () {
      expect(
        deriveNotificationStatus(permissionGranted: true, enabled: true),
        ModuleStatus.enabled,
      );
      expect(
        deriveNotificationStatus(permissionGranted: true, enabled: false),
        ModuleStatus.disabled,
      );
    });
  });

  group('voip 推导（权限 + 开关，默认关）', () {
    test('权限状态未知 → installed', () {
      expect(
        deriveVoipStatus(permissionGranted: null, enabled: false),
        ModuleStatus.installed,
      );
    });

    test('未授予 → permissionRequired（无论开关）', () {
      expect(
        deriveVoipStatus(permissionGranted: false, enabled: true),
        ModuleStatus.permissionRequired,
      );
      expect(
        deriveVoipStatus(permissionGranted: false, enabled: false),
        ModuleStatus.permissionRequired,
      );
    });

    test('授予 + 开 → enabled；授予 + 关 → disabled（默认关）', () {
      expect(
        deriveVoipStatus(permissionGranted: true, enabled: true),
        ModuleStatus.enabled,
      );
      expect(
        deriveVoipStatus(permissionGranted: true, enabled: false),
        ModuleStatus.disabled,
      );
    });
  });

  group('notification_read 推导（特殊访问权限 + 开关，默认关）', () {
    test('权限状态未知 → installed', () {
      expect(
        deriveNotificationReadStatus(permissionGranted: null, enabled: false),
        ModuleStatus.installed,
      );
    });

    test('未授予 → permissionRequired（无论开关）', () {
      expect(
        deriveNotificationReadStatus(permissionGranted: false, enabled: true),
        ModuleStatus.permissionRequired,
      );
      expect(
        deriveNotificationReadStatus(permissionGranted: false, enabled: false),
        ModuleStatus.permissionRequired,
      );
    });

    test('授予 + 开 → enabled；授予 + 关 → disabled（默认关）', () {
      expect(
        deriveNotificationReadStatus(permissionGranted: true, enabled: true),
        ModuleStatus.enabled,
      );
      expect(
        deriveNotificationReadStatus(permissionGranted: true, enabled: false),
        ModuleStatus.disabled,
      );
    });
  });

  group('safe_action 推导（纯开关，无特殊权限项，默认关）', () {
    test('关 → disabled（默认）；开 → enabled', () {
      expect(deriveSafeActionStatus(enabled: false), ModuleStatus.disabled);
      expect(deriveSafeActionStatus(enabled: true), ModuleStatus.enabled);
    });
  });

  group('screenmemo 推导（token + 开关 + ping）', () {
    test('token 未知/未配置 → installed', () {
      expect(
        deriveScreenMemoStatus(
            hasToken: null, enabled: true, health: ScreenMemoHealth.unknown),
        ModuleStatus.installed,
      );
      expect(
        deriveScreenMemoStatus(
            hasToken: false, enabled: true, health: ScreenMemoHealth.unknown),
        ModuleStatus.installed,
      );
    });

    test('有 token + 关 → disabled', () {
      expect(
        deriveScreenMemoStatus(
            hasToken: true, enabled: false, health: ScreenMemoHealth.ok),
        ModuleStatus.disabled,
      );
    });

    test('有 token + 开 + 未 ping → enabled（已配置待验证）', () {
      expect(
        deriveScreenMemoStatus(
            hasToken: true, enabled: true, health: ScreenMemoHealth.unknown),
        ModuleStatus.enabled,
      );
    });

    test('ping 在线且库可用 → running', () {
      expect(
        deriveScreenMemoStatus(
            hasToken: true, enabled: true, health: ScreenMemoHealth.ok),
        ModuleStatus.running,
      );
    });

    test('ping 在线但库不可用 → degraded', () {
      expect(
        deriveScreenMemoStatus(
            hasToken: true, enabled: true, health: ScreenMemoHealth.dbDown),
        ModuleStatus.degraded,
      );
    });

    test('ping 鉴权失败/不可达/其他失败 → error', () {
      for (final h in [
        ScreenMemoHealth.authFailed,
        ScreenMemoHealth.unreachable,
        ScreenMemoHealth.failure,
      ]) {
        expect(
          deriveScreenMemoStatus(hasToken: true, enabled: true, health: h),
          ModuleStatus.error,
          reason: '$h 应映射 error',
        );
      }
    });
  });

  group('ModuleRegistry 状态迁移关键路径', () {
    test('transport：installed → starting → running → degraded → stopped', () {
      final r = ModuleRegistry();
      final spec = r['transport'];
      expect(spec.status, ModuleStatus.installed);

      r.updateTransportState(TransportState.connecting);
      expect(spec.status, ModuleStatus.starting);

      r.updateTransportState(TransportState.online);
      expect(spec.status, ModuleStatus.running);

      r.updateTransportState(TransportState.degraded);
      expect(spec.status, ModuleStatus.degraded);

      // 连接过（everConnected 已锁定）后断开 → stopped 而非 installed
      r.updateTransportState(TransportState.disconnected);
      expect(spec.status, ModuleStatus.stopped);
    });

    test('transport：开关关闭 → disabled，重开且未连接 → stopped（连接过）', () {
      final r = ModuleRegistry();
      r.setTransportEverConnected(true);
      expect(r['transport'].status, ModuleStatus.stopped);

      r.setTransportEnabled(false);
      expect(r['transport'].status, ModuleStatus.disabled);

      r.setTransportEnabled(true);
      expect(r['transport'].status, ModuleStatus.stopped);
    });

    test('notification_post：installed → permissionRequired → enabled → disabled',
        () {
      final r = ModuleRegistry();
      final spec = r['notification_post'];
      expect(spec.status, ModuleStatus.installed); // 权限未知

      r.updateNotification(permissionGranted: false);
      expect(spec.status, ModuleStatus.permissionRequired);

      r.updateNotification(permissionGranted: true, enabled: true);
      expect(spec.status, ModuleStatus.enabled);

      r.updateNotification(enabled: false);
      expect(spec.status, ModuleStatus.disabled);
    });

    test('voip：installed → permissionRequired → disabled → enabled（默认关）',
        () {
      final r = ModuleRegistry();
      final spec = r['voip'];
      expect(spec.status, ModuleStatus.installed); // 权限未知

      r.updateVoip(permissionGranted: false);
      expect(spec.status, ModuleStatus.permissionRequired);

      // 权限授予但开关关（宿主默认关）→ disabled
      r.updateVoip(permissionGranted: true, enabled: false);
      expect(spec.status, ModuleStatus.disabled);

      r.updateVoip(enabled: true);
      expect(spec.status, ModuleStatus.enabled);
    });

    test('notification_read：installed → permissionRequired → disabled → enabled',
        () {
      final r = ModuleRegistry();
      final spec = r['notification_read'];
      expect(spec.status, ModuleStatus.installed); // 权限未知

      r.updateNotificationRead(permissionGranted: false);
      expect(spec.status, ModuleStatus.permissionRequired);

      // 权限授予但开关关（默认关）→ disabled
      r.updateNotificationRead(permissionGranted: true, enabled: false);
      expect(spec.status, ModuleStatus.disabled);

      r.updateNotificationRead(enabled: true);
      expect(spec.status, ModuleStatus.enabled);
    });

    test('safe_action：installed → enabled → disabled（纯开关）', () {
      final r = ModuleRegistry();
      final spec = r['safe_action'];
      expect(spec.status, ModuleStatus.installed); // 初始态

      r.updateSafeAction(enabled: true);
      expect(spec.status, ModuleStatus.enabled);

      r.updateSafeAction(enabled: false);
      expect(spec.status, ModuleStatus.disabled);
    });

    test('screenmemo：installed → disabled → enabled → running → degraded → error',
        () {
      final r = ModuleRegistry();
      final spec = r['screenmemo'];
      expect(spec.status, ModuleStatus.installed); // 无 token

      r.updateScreenMemo(hasToken: true, enabled: false);
      expect(spec.status, ModuleStatus.disabled);

      r.updateScreenMemo(enabled: true);
      expect(spec.status, ModuleStatus.enabled); // 待验证

      r.updateScreenMemo(health: ScreenMemoHealth.ok);
      expect(spec.status, ModuleStatus.running);

      r.updateScreenMemo(health: ScreenMemoHealth.dbDown);
      expect(spec.status, ModuleStatus.degraded);

      r.updateScreenMemo(health: ScreenMemoHealth.authFailed);
      expect(spec.status, ModuleStatus.error);
    });

    test('9 态均可达', () {
      final r = ModuleRegistry();
      final seen = <ModuleStatus>{r['transport'].status};
      void collect() => seen.addAll(r.modules.values.map((s) => s.status));

      r.updateTransportState(TransportState.connecting); // starting
      r.updateNotification(permissionGranted: false); // permissionRequired
      r.updateScreenMemo(hasToken: true, enabled: false); // disabled
      collect();
      expect(seen, containsAll([
        ModuleStatus.installed,
        ModuleStatus.starting,
        ModuleStatus.permissionRequired,
        ModuleStatus.disabled,
      ]));

      r.updateNotification(permissionGranted: true); // enabled
      r.updateScreenMemo(enabled: true); // enabled（未 ping）
      r.updateTransportState(TransportState.online); // running
      collect();
      expect(seen, containsAll([ModuleStatus.enabled, ModuleStatus.running]));

      r.updateTransportState(TransportState.degraded); // degraded
      r.updateScreenMemo(health: ScreenMemoHealth.authFailed); // error
      collect();
      expect(seen, containsAll([ModuleStatus.degraded, ModuleStatus.error]));

      r.updateTransportState(TransportState.disconnected); // stopped
      collect();
      expect(seen, contains(ModuleStatus.stopped));

      expect(seen.length, ModuleStatus.values.length); // 9 态全覆盖
    });
  });

  group('全局暂停标志（allPaused 覆盖）', () {
    test('暂停：所有模块收束 stopped（覆盖 running/permissionRequired/disabled）',
        () {
      final r = ModuleRegistry();
      // 各模块先推到不同状态
      r.updateTransportState(TransportState.online); // running
      r.updateNotification(permissionGranted: false); // permissionRequired
      r.updateScreenMemo(
          hasToken: true, enabled: true, health: ScreenMemoHealth.ok); // running
      expect(r['transport'].status, ModuleStatus.running);
      expect(r['notification_post'].status, ModuleStatus.permissionRequired);
      expect(r['screenmemo'].status, ModuleStatus.running);

      r.setAllPaused(true);
      expect(r.allPaused, isTrue);
      for (final spec in r.modules.values) {
        expect(spec.status, ModuleStatus.stopped,
            reason: '${spec.id} 暂停期间应为 stopped');
      }

      // 暂停期间输入变化不改变 stopped 收束
      r.updateTransportState(TransportState.online);
      r.updateNotification(permissionGranted: true, enabled: true);
      expect(r['transport'].status, ModuleStatus.stopped);
      expect(r['notification_post'].status, ModuleStatus.stopped);
    });

    test('恢复：解除暂停后回到最新输入推导的状态', () {
      final r = ModuleRegistry();
      r.updateTransportState(TransportState.online);
      r.setAllPaused(true);
      expect(r['transport'].status, ModuleStatus.stopped);

      // 暂停期间传输掉到 degraded
      r.updateTransportState(TransportState.degraded);
      r.updateNotification(permissionGranted: true, enabled: true);

      r.setAllPaused(false);
      expect(r.allPaused, isFalse);
      expect(r['transport'].status, ModuleStatus.degraded); // 按最新输入推导
      expect(r['notification_post'].status, ModuleStatus.enabled);
    });

    test('状态变更触发 notifyListeners', () {
      final r = ModuleRegistry();
      var notified = 0;
      r.addListener(() => notified++);

      r.updateTransportState(TransportState.connecting);
      r.setAllPaused(true);
      r.setAllPaused(false);
      r.setLastError('transport', 'auth_failed x');
      r.setLastReceipt('notification_post', 'executed id=12345678…');
      expect(notified, 5);
      expect(r['transport'].lastError, 'auth_failed x');
      expect(r['notification_post'].lastReceipt, contains('executed'));
    });
  });
}
