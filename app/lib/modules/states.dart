/// whale-mobile 设计语言锚定层 · 状态机（草案 docs/whale-mobile-redesign-flutter-mapping.md §2）。
///
/// 枚举值名（wire name）与原型 data.ts 逐一对齐（序列化/日志/跨端对照用它）；
/// label/tone 与原型 meta 表一致。registry.dart 的 ModuleStatus 是运行时事实源，
/// 本层是它对外展示/上报的稳定线名映射，不复制状态机逻辑。
library;

import '../theme/whale_theme.dart';
import 'registry.dart';

/// 模块状态线名（原型 `ModuleState`）。`permission` = registry `permissionRequired`。
enum ModuleState {
  installed('installed', '已安装', WhaleTone.slate),
  permission('permission', '待授权', WhaleTone.amber),
  disabled('disabled', '已停用', WhaleTone.slate),
  enabled('enabled', '已启用', WhaleTone.blue),
  starting('starting', '启动中', WhaleTone.blue),
  running('running', '运行中', WhaleTone.aqua),
  degraded('degraded', '降级运行', WhaleTone.amber),
  error('error', '错误', WhaleTone.coral),
  stopped('stopped', '已停止', WhaleTone.slate);

  const ModuleState(this.wire, this.label, this.tone);

  /// 原型 data.ts 的枚举字面量（跨端对照/日志用）。
  final String wire;
  final String label;
  final WhaleTone tone;

  static ModuleState? fromWire(String wire) {
    for (final v in values) {
      if (v.wire == wire) return v;
    }
    return null;
  }
}

/// registry ModuleStatus（运行时事实源）→ ModuleState（展示线名）。仅改名 permissionRequired→permission。
extension ModuleStatusWire on ModuleStatus {
  ModuleState get wireState => switch (this) {
        ModuleStatus.permissionRequired => ModuleState.permission,
        ModuleStatus.installed => ModuleState.installed,
        ModuleStatus.disabled => ModuleState.disabled,
        ModuleStatus.enabled => ModuleState.enabled,
        ModuleStatus.starting => ModuleState.starting,
        ModuleStatus.running => ModuleState.running,
        ModuleStatus.degraded => ModuleState.degraded,
        ModuleStatus.error => ModuleState.error,
        ModuleStatus.stopped => ModuleState.stopped,
      };
}

/// 隐私级（原型 `Privacy`；registry `PrivacyLevel` 同义锚定）。
enum Privacy {
  public('public', '公开', WhaleTone.blue, '内容可上报 Core'),
  ownerPrivate('owner_private', '机主隐私', WhaleTone.violet, '仅主人会话可见'),
  ownerSensitive('owner_sensitive', '机主敏感', WhaleTone.coral, '默认不出设备');

  const Privacy(this.wire, this.label, this.tone, this.note);

  final String wire;
  final String label;
  final WhaleTone tone;
  final String note;

  static Privacy? fromWire(String wire) {
    for (final v in values) {
      if (v.wire == wire) return v;
    }
    return null;
  }
}

/// registry PrivacyLevel → Privacy 线名（值域 1:1）。
extension PrivacyLevelWire on PrivacyLevel {
  Privacy get wirePrivacy => switch (this) {
        PrivacyLevel.public => Privacy.public,
        PrivacyLevel.ownerPrivate => Privacy.ownerPrivate,
        PrivacyLevel.ownerSensitive => Privacy.ownerSensitive,
      };
}

/// 回执五终态（原型 `ReceiptState`）。结果未知不伪装成功（与闸门纪律同源）。
enum ReceiptState {
  succeeded('succeeded', '已执行', WhaleTone.aqua),
  failed('failed', '失败', WhaleTone.coral),
  timeout('timeout', '超时', WhaleTone.amber),
  cancelled('cancelled', '已取消', WhaleTone.slate),
  unknown('unknown', '结果未知', WhaleTone.amber);

  const ReceiptState(this.wire, this.label, this.tone);

  final String wire;
  final String label;
  final WhaleTone tone;

  static ReceiptState? fromWire(String wire) {
    for (final v in values) {
      if (v.wire == wire) return v;
    }
    return null;
  }
}

/// 链路状态（原型 `LinkState`）。
enum LinkState {
  online('online', '已连接', WhaleTone.aqua, '心跳正常，动作可下发'),
  degraded('degraded', '降级重连', WhaleTone.amber, '离线队列已加密，恢复后重放'),
  awaitingAuth('awaiting_auth', '待配对', WhaleTone.violet, '等待 Core 审批配对码'),
  connecting('connecting', '连接中', WhaleTone.blue, 'Ed25519 挑战验签中'),
  disconnected('disconnected', '未连接', WhaleTone.slate, '采集继续，上行暂存本地');

  const LinkState(this.wire, this.label, this.tone, this.hint);

  final String wire;
  final String label;
  final WhaleTone tone;
  final String hint;

  static LinkState? fromWire(String wire) {
    for (final v in values) {
      if (v.wire == wire) return v;
    }
    return null;
  }
}
