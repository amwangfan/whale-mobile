import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/modules/registry.dart';
import 'package:whale_mobile/modules/states.dart';
import 'package:whale_mobile/theme/whale_theme.dart';

/// 锚定层状态机单测：线名与原型 data.ts 逐一对齐，meta 与原型 meta 表一致，
/// registry 运行时枚举 → 展示线名映射全覆盖。
void main() {
  group('ModuleState（原型 ModuleState）', () {
    test('九值线名逐一对齐', () {
      expect(ModuleState.values.map((v) => v.wire).toList(), [
        'installed',
        'permission',
        'disabled',
        'enabled',
        'starting',
        'running',
        'degraded',
        'error',
        'stopped',
      ]);
    });

    test('label/tone 与原型 moduleStateMeta 一致', () {
      expect(ModuleState.installed.label, '已安装');
      expect(ModuleState.installed.tone, WhaleTone.slate);
      expect(ModuleState.permission.label, '待授权');
      expect(ModuleState.permission.tone, WhaleTone.amber);
      expect(ModuleState.running.label, '运行中');
      expect(ModuleState.running.tone, WhaleTone.aqua);
      expect(ModuleState.error.tone, WhaleTone.coral);
      expect(ModuleState.degraded.tone, WhaleTone.amber);
    });

    test('fromWire 往返', () {
      for (final v in ModuleState.values) {
        expect(ModuleState.fromWire(v.wire), v);
      }
      expect(ModuleState.fromWire('bogus'), isNull);
    });
  });

  group('registry ModuleStatus → ModuleState 锚定', () {
    test('九值全覆盖映射（permissionRequired→permission）', () {
      expect(ModuleStatus.values.length, ModuleState.values.length);
      expect(ModuleStatus.installed.wireState, ModuleState.installed);
      expect(ModuleStatus.permissionRequired.wireState, ModuleState.permission);
      expect(ModuleStatus.disabled.wireState, ModuleState.disabled);
      expect(ModuleStatus.enabled.wireState, ModuleState.enabled);
      expect(ModuleStatus.starting.wireState, ModuleState.starting);
      expect(ModuleStatus.running.wireState, ModuleState.running);
      expect(ModuleStatus.degraded.wireState, ModuleState.degraded);
      expect(ModuleStatus.error.wireState, ModuleState.error);
      expect(ModuleStatus.stopped.wireState, ModuleState.stopped);
    });
  });

  group('Privacy（原型 Privacy）', () {
    test('三值线名 + label/tone/note 对齐', () {
      expect(Privacy.public.wire, 'public');
      expect(Privacy.public.label, '公开');
      expect(Privacy.ownerPrivate.wire, 'owner_private');
      expect(Privacy.ownerPrivate.tone, WhaleTone.violet);
      expect(Privacy.ownerSensitive.wire, 'owner_sensitive');
      expect(Privacy.ownerSensitive.note, '默认不出设备');
      expect(Privacy.ownerSensitive.tone, WhaleTone.coral);
    });

    test('registry PrivacyLevel 三值 1:1', () {
      expect(PrivacyLevel.values.length, Privacy.values.length);
      expect(PrivacyLevel.public.wirePrivacy, Privacy.public);
      expect(PrivacyLevel.ownerPrivate.wirePrivacy, Privacy.ownerPrivate);
      expect(PrivacyLevel.ownerSensitive.wirePrivacy, Privacy.ownerSensitive);
    });
  });

  group('ReceiptState（原型 ReceiptState，五终态）', () {
    test('线名/label/tone 对齐；unknown 不伪装成功', () {
      expect(ReceiptState.values.map((v) => v.wire).toList(),
          ['succeeded', 'failed', 'timeout', 'cancelled', 'unknown']);
      expect(ReceiptState.succeeded.label, '已执行');
      expect(ReceiptState.failed.tone, WhaleTone.coral);
      expect(ReceiptState.unknown.label, '结果未知');
      expect(ReceiptState.unknown.tone, WhaleTone.amber); // 未知≠成功：amber 非 aqua
    });
  });

  group('LinkState（原型 LinkState）', () {
    test('五值线名 + hint 对齐', () {
      expect(LinkState.values.map((v) => v.wire).toList(),
          ['online', 'degraded', 'awaiting_auth', 'connecting', 'disconnected']);
      // 2026-11-23 clean break：配对改 owner-first 兑换码，awaiting_auth 不再是
      // 「等主人批准配对码」，而是 challenge/hello 认证握手中。
      expect(LinkState.awaitingAuth.label, '认证中');
      expect(LinkState.awaitingAuth.tone, WhaleTone.violet);
      expect(LinkState.connecting.hint, 'Ed25519 挑战验签中');
      expect(LinkState.degraded.hint, '离线队列已加密，恢复后重放');
    });
  });

  group('transportErrorHint（版本 floor 明示，清单 §5.6）', () {
    test('protocol_version 给出升级明示，不裸断连', () {
      expect(transportErrorHint('protocol_version'), contains('升级'));
      expect(transportErrorHint('protocol_version'), contains('协议版本'));
    });

    test('撤销/认证失败指向重新配对入口', () {
      expect(transportErrorHint('device_revoked'), contains('重新配对'));
      expect(transportErrorHint('auth_failed'), contains('重新配对'));
    });

    test('无提示的错误码返回 null（原样展示 code+message）', () {
      expect(transportErrorHint('internal'), isNull);
      expect(transportErrorHint('rate_limited'), isNull);
    });
  });
}
