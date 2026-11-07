package com.wangfanlab.whale.modules.capture

import android.content.Context

/**
 * 隐私遮罩基础（Phase 3）：用户排除包名集合的持久化读写。
 *
 * 存于 SharedPreferences `whale_capture/excluded_packages`（StringSet）；
 * [CaptureDecider.shouldCapture] 命中即 skip（`excluded_package`）——
 * 被排除应用**不截屏、不 OCR、不留存任何痕迹**。
 * 读写均做规范化（trim、去空白、去重）。
 */
class ExcludedPackagesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前排除集合（只读快照）。 */
    fun get(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    /** 整体覆盖（规范化后落盘）。 */
    fun set(packages: Set<String>) {
        prefs.edit().putStringSet(KEY, normalize(packages)).apply()
    }

    /** 添加单个包名；已在集合中返回 false。 */
    fun add(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        val cur = get()
        if (pkg in cur) return false
        prefs.edit().putStringSet(KEY, cur + pkg).apply()
        return true
    }

    /** 移除单个包名；不存在返回 false。 */
    fun remove(packageName: String): Boolean {
        val pkg = packageName.trim()
        val cur = get()
        if (pkg !in cur) return false
        prefs.edit().putStringSet(KEY, cur - pkg).apply()
        return true
    }

    /** 规范化：去空白、去重（包名大小写敏感，Android 包名均小写，不做大小写折叠）。 */
    private fun normalize(packages: Set<String>): Set<String> =
        packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        const val PREFS_NAME = "whale_capture"
        const val KEY = "excluded_packages"
    }
}

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
