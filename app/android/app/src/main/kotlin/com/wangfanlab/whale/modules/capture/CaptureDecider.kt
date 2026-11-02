package com.wangfanlab.whale.modules.capture

/**
 * 采集决策（SM-01 影子模式，纯逻辑可单测）。
 * 思路借鉴 ScreenMemo 的截图去重/过滤（署名见 README 致谢），
 * 在 Whale 的模块/隐私体系内重写。
 */
object CaptureDecider {

    /** 64 位 dHash（9x8 亮度梯度）+ 汉明距离去重判定。 */
    const val DHASH_HAMMING_THRESHOLD = 5

    /** 默认采集间隔（毫秒）。 */
    const val DEFAULT_INTERVAL_MS = 5_000L

    /** 判定是否应发起一次截屏。 */
    fun shouldCapture(
        moduleEnabled: Boolean,
        allPaused: Boolean,
        conflictWithStandalone: Boolean,
        deviceLocked: Boolean,
        foregroundPackage: String?,
        excludedPackages: Set<String>,
        nowMs: Long,
        lastCaptureMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Decision {
        if (!moduleEnabled) return Decision.Skip("module_disabled")
        if (allPaused) return Decision.Skip("all_paused")
        if (conflictWithStandalone) return Decision.Skip("conflict_standalone_screenmemo")
        if (deviceLocked) return Decision.Skip("device_locked")
        val pkg = foregroundPackage
        if (pkg.isNullOrBlank()) return Decision.Skip("no_foreground")
        if (pkg in excludedPackages) return Decision.Skip("excluded_package")
        if (isImePackage(pkg)) return Decision.Skip("ime")
        if (nowMs - lastCaptureMs < intervalMs) return Decision.Skip("interval")
        return Decision.Capture
    }

    /** 输入法包名特征（不读内容，仅识别输入法界面包名后缀/常见前缀）。 */
    fun isImePackage(pkg: String): Boolean =
        pkg.contains(".inputmethod") || pkg.contains("ime.") || pkg.endsWith(".ime") ||
            pkg.contains(".ime.") || pkg.contains("keyboard", ignoreCase = true)

    /** 去重：与上一帧 dHash 汉明距离 ≤ 阈值视为重复。 */
    fun isDuplicate(prevHash: Long?, newHash: Long, threshold: Int = DHASH_HAMMING_THRESHOLD): Boolean {
        if (prevHash == null) return false
        return hamming(prevHash, newHash) <= threshold
    }

    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

    sealed interface Decision {
        /** 发起截屏。 */
        data object Capture : Decision

        /** 跳过（原因码可观测、可计数）。 */
        data class Skip(val reason: String) : Decision
    }
}
