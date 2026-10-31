package com.wangfanlab.whale.modules.notificationread

/**
 * phone.notification.read 纯逻辑层（阶段二 B3 前半，独立可选模块）：
 * 不依赖任何 Android 类，可在本地 JUnit（JVM）下直接测试。
 * Android 胶水见 NotificationReadModule.kt（NotificationListenerService）。
 *
 * 事件链校验顺序（全部通过才产生 sensor.event）：
 *   0. 全局暂停（whale_modules/all_paused，A1 一键暂停 → 不产生事件）
 *   1. 模块开关（whale_modules/notification_read_enabled，默认 false）
 *   2. App 白名单（whale_notif_read/allowed_apps；默认空 = 一个都不读）
 *   3. 通知类别过滤（ongoing / 进度条 / 媒体控制类丢弃）
 *   4. 去重（同 app 同标题 60s 内不重复）
 *
 * 脱敏纪律（owner_sensitive 输入 → owner_private 事件）：
 *   只取 package / postedTime / category / 标题（≤80 字符）；
 *   绝不读取/回传正文 text / tickerText / 消息行（EXTRA_TEXT*）。
 */

/** 通知快照（胶水层从 StatusBarNotification 脱敏提取；appLabel 由 PM 解析注入）。 */
data class NotifSnapshot(
    val packageName: String,
    val appLabel: String,
    val postedTimeMs: Long,
    /** android.app.Notification.category 原样字符串；null = 未设置。 */
    val category: String?,
    /** 原始标题（截断在逻辑层做）；胶水层已保证不含正文。 */
    val title: String,
    val ongoing: Boolean,
    val hasProgress: Boolean,
)

/** 事件链决策：Emit=产出 sensor.event；Drop=丢弃（reason 仅供日志/测试）。 */
sealed interface NotifReadDecision {
    data class Emit(val summary: String) : NotifReadDecision
    data class Drop(val reason: String) : NotifReadDecision
}

/** 过滤与脱敏规则（§白名单/类别过滤）。 */
object NotificationReadFilter {
    const val MAX_TITLE_LEN = 80
    const val MAX_SUMMARY_LEN = 200 // summary schema 上限 2000，留足余量
    const val MAX_APP_LABEL_LEN = 32

    /**
     * 过滤类别（对应 Notification.CATEGORY_* 字符串常量，纯 JVM 不引 Android）：
     * progress=进度条；transport=媒体控制；service/status/ongoing 由 ongoing 位覆盖。
     */
    val FILTERED_CATEGORIES: Set<String> = setOf(
        "progress", // Notification.CATEGORY_PROGRESS
        "transport", // Notification.CATEGORY_TRANSPORT（媒体控制）
    )

    /** 标题脱敏：截断 ≤80 字符（不拒绝）；空标题回落占位。 */
    fun sanitizeTitle(raw: String): String {
        val t = raw.replace(Regex("[\\r\\n]+"), " ").trim()
        return t.take(MAX_TITLE_LEN).ifEmpty { "（无标题）" }
    }

    fun sanitizeAppLabel(raw: String): String =
        raw.trim().take(MAX_APP_LABEL_LEN).ifEmpty { "?" }

    /** 类别过滤：true = 应丢弃（ongoing/进度条/媒体控制）。 */
    fun isFiltered(s: NotifSnapshot): Boolean =
        s.ongoing || s.hasProgress || (s.category?.lowercase() in FILTERED_CATEGORIES)

    /** 上行摘要：「App 名: 标题截断」（不含正文，schema summary ≤2000）。 */
    fun buildSummary(appLabel: String, title: String): String =
        "${sanitizeAppLabel(appLabel)}: ${sanitizeTitle(title)}".take(MAX_SUMMARY_LEN)
}

/** 同 app 同标题 60s 去重（滑动窗口；线程安全）。 */
class NotificationDeduper(
    private val windowMs: Long = DEDUP_WINDOW_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastEmitMs = HashMap<String, Long>()

    /** @return true=首次或超窗（放行并记一次）；false=窗口内重复（丢弃）。 */
    @Synchronized
    fun shouldEmit(packageName: String, title: String): Boolean {
        val now = clockMs()
        val key = "$packageName|$title"
        val last = lastEmitMs[key]
        if (last != null && now - last < windowMs) return false
        lastEmitMs[key] = now
        // 防膨胀：超窗条目随手清一轮
        if (lastEmitMs.size > 512) {
            val it = lastEmitMs.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value >= windowMs) it.remove()
            }
        }
        return true
    }

    companion object {
        const val DEDUP_WINDOW_MS = 60_000L
    }
}

/**
 * 事件链编排（与 RingDecider/NotificationDecider 同构）：
 * 全局暂停 → 模块开关 → App 白名单 → 类别过滤 → 去重。
 * 通知监听为特殊访问权限（非运行时权限），其授予状态是 service 能否收到回调的
 * 前置（未授权系统根本不会回调），故不在此链上重复检查；UI 卡片负责状态检测。
 */
class NotificationReadDecider(
    private val moduleEnabled: () -> Boolean,
    private val allowedApps: () -> Set<String>,
    private val allPaused: () -> Boolean = { false },
    private val deduper: NotificationDeduper = NotificationDeduper(),
) {
    fun onNotification(s: NotifSnapshot): NotifReadDecision {
        // 0. 全局暂停（A1）：暂停期间不产生任何事件
        if (allPaused()) return NotifReadDecision.Drop("paused")
        // 1. 模块开关（默认 false；关 = 一个事件都不产生）
        if (!moduleEnabled()) return NotifReadDecision.Drop("disabled")
        // 2. App 白名单（默认空 = 一个都不读）
        if (s.packageName !in allowedApps()) return NotifReadDecision.Drop("not_whitelisted")
        // 3. 类别过滤（ongoing/进度条/媒体控制）
        if (NotificationReadFilter.isFiltered(s)) return NotifReadDecision.Drop("filtered")
        // 标题先脱敏再进 dedup/summary（正文永不进入）
        val title = NotificationReadFilter.sanitizeTitle(s.title)
        // 4. 去重（同 app 同标题 60s）
        if (!deduper.shouldEmit(s.packageName, title)) return NotifReadDecision.Drop("duplicate")
        return NotifReadDecision.Emit(
            NotificationReadFilter.buildSummary(s.appLabel, title),
        )
    }
}
