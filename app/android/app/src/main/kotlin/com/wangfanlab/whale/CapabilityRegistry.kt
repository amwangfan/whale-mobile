package com.wangfanlab.whale

/**
 * 能力注册表（contracts/v2 §5.2）：按构建变体声明本 APK 实际支持的能力。
 *
 * Root 版独有能力（device.shell / device.file.* / device.app.install /
 * uninstall / device.logcat / device.screen.record 等）只在 root 变体注册，
 * 不靠 UI 隐藏——daily 构建的 hello 里根本不会出现这些能力。
 *
 * 能力名与 contracts/v2 §5.1 枚举逐字一致；协议变更须同步。
 */
object CapabilityRegistry {

    /** 两版共有能力。 */
    private val COMMON = linkedSetOf(
        "screen.context.read",
        "screen.history.search",
        "screen.evidence.read",
        "screen.live.subscribe",
        "phone.notification.post",
        "phone.notification.read",
        "phone.call.ring",
        "phone.call.audio",
        "phone.device.safe_action",
        "glasses.card.show",
        "glasses.card.update",
        "glasses.card.dismiss",
        "device.observe",
        "device.tap",
        "device.long_press",
        "device.swipe",
        "device.input_text",
        "device.key",
        "device.wait",
        "device.app.launch",
        "device.app.list",
        "device.status",
        "device.stop",
        "device.output.read",
    )

    /** Root 版独有能力（daily 构建不注册）。 */
    private val ROOT_ONLY = linkedSetOf(
        "device.shell",
        "device.file.read",
        "device.file.write",
        "device.file.list",
        "device.logcat",
        "device.app.install",
        "device.app.uninstall",
        "device.screen.record",
    )

    /** 当前构建变体实际声明的能力集合。 */
    val current: Set<String>
        get() = if (BuildConfig.FLAVOR == "root") COMMON + ROOT_ONLY else COMMON

    val flavor: String get() = BuildConfig.FLAVOR.ifEmpty { "daily" }

    fun isRootEdition(): Boolean = BuildConfig.FLAVOR == "root"
}
