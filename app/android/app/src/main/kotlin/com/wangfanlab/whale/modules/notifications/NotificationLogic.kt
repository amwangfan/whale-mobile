package com.wangfanlab.whale.modules.notifications

/**
 * phone.notification.post 纯逻辑层（DESIGN.md §2/§3）：不依赖任何 Android 类，
 * 可在本地 JUnit（JVM）下直接测试。Android 胶水见 NotificationModule.kt。
 *
 * 校验链顺序（全部通过才执行）：
 *   0. 全局暂停（whale_modules/all_paused，A1 一键暂停；暂停 → unavailable）
 *   1. 模块开关（whale_modules/notification_post_enabled，默认 true）
 *   2. 通知权限（Android 13+ POST_NOTIFICATIONS；未授予 → permission_missing）
 *   3. 参数白名单（op/channel_id/title/text/notification_tag/click_action）
 *   4. 频控（滑动窗口 6 条/分钟；cancel 豁免——撤销只降噪声，不应被风暴堵死）
 */

/** 动作语义（§3）。 */
enum class NotificationOp { POST, UPDATE, CANCEL }

/** 校验通过后的执行规格（title/text 已按白名单截断）。 */
data class NotificationSpec(
    val op: NotificationOp,
    val channelId: String,
    val title: String,
    val text: String,
    val notificationTag: String?,
    /** click_action 白名单收敛后的结果：true=点击打开本 App；false=无点击动作。 */
    val openAppOnClick: Boolean,
)

/** 校验/决策结果：要么执行，要么带 error_code 拒绝（终态 failed）。 */
sealed interface NotificationDecision {
    data class Execute(val spec: NotificationSpec) : NotificationDecision

    /** errorCode 对应 action.receipt payload.error_code。 */
    data class Reject(val errorCode: String) : NotificationDecision
}

/** 参数白名单 + 动作语义（§2.4 / §3）。 */
object NotificationValidator {
    const val MAX_TITLE_LEN = 100
    const val MAX_TEXT_LEN = 500
    const val DEFAULT_CHANNEL_ID = "whale_reminder"
    const val CLICK_ACTION_OPEN_APP = "open_app"

    /** 已注册渠道白名单（与 NotificationModule 渠道注册一一对应）。 */
    val CHANNEL_IDS: Set<String> = setOf("whale_reminder", "whale_call", "whale_status")

    fun validate(args: Map<*, *>?): NotificationDecision {
        val opRaw = (args?.get("op") as? String)?.trim()?.lowercase() ?: "post"
        val op = when (opRaw) {
            "post" -> NotificationOp.POST
            "update" -> NotificationOp.UPDATE
            "cancel" -> NotificationOp.CANCEL
            else -> return NotificationDecision.Reject("invalid_message")
        }

        val channelId = (args?.get("channel_id") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHANNEL_ID
        if (channelId !in CHANNEL_IDS) return NotificationDecision.Reject("invalid_message")

        val tag = (args?.get("notification_tag") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
        // §3：update/cancel 必须带 notification_tag（幂等定位）
        if (op != NotificationOp.POST && tag == null) {
            return NotificationDecision.Reject("invalid_message")
        }

        // click_action 白名单：仅 "open_app" 或省略；其余降级为无点击动作（不 fail）。
        val openAppOnClick = when (val ca = (args?.get("click_action") as? String)?.trim()) {
            null, "" -> false
            CLICK_ACTION_OPEN_APP -> true
            else -> false // 拒绝任意深链/第三方跳转：静默降级
        }

        // 超长截断（§2.4），不拒绝。
        val title = ((args?.get("title") as? String) ?: DEFAULT_TITLE).take(MAX_TITLE_LEN)
        val text = ((args?.get("text") as? String) ?: "").take(MAX_TEXT_LEN)

        return NotificationDecision.Execute(
            NotificationSpec(
                op = op,
                channelId = channelId,
                title = title,
                text = text,
                notificationTag = tag,
                openAppOnClick = openAppOnClick,
            )
        )
    }

    private const val DEFAULT_TITLE = "Whale"
}

/** 滑动窗口频控（§2.5）：windowMs 内最多 maxPermits 次。线程安全。 */
class SlidingWindowRateLimiter(
    private val maxPermits: Int,
    private val windowMs: Long,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val hits = ArrayDeque<Long>()

    /** @return true=放行（已计一次）；false=超限（不计入）。 */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clockMs()
        while (hits.isNotEmpty() && now - hits.first() >= windowMs) hits.removeFirst()
        return if (hits.size < maxPermits) {
            hits.addLast(now)
            true
        } else {
            false
        }
    }

    /** 当前窗口内已计次数（测试用）。 */
    @Synchronized
    fun countInWindow(): Int {
        val now = clockMs()
        while (hits.isNotEmpty() && now - hits.first() >= windowMs) hits.removeFirst()
        return hits.size
    }
}

/**
 * 校验链编排（§2）：全局暂停 → 模块开关 → 权限 → 参数白名单 → 频控。
 * 全部注入为纯函数/纯对象，JVM 可测；Android 取值在 NotificationModule 注入。
 */
class NotificationDecider(
    private val moduleEnabled: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val allPaused: () -> Boolean = { false },
    private val rateLimiter: SlidingWindowRateLimiter =
        SlidingWindowRateLimiter(RATE_LIMIT_MAX, RATE_LIMIT_WINDOW_MS),
) {
    fun onRequest(args: Map<*, *>?): NotificationDecision {
        // 0. 全局暂停（A1 一键暂停）：暂停期间 action 一律回 unavailable
        if (allPaused()) return NotificationDecision.Reject("unavailable")
        // 1. 模块开关（用户一键停）
        if (!moduleEnabled()) return NotificationDecision.Reject("unavailable")
        // 2. 运行时权限（§2.3）
        if (!permissionGranted()) return NotificationDecision.Reject("permission_missing")
        // 3. 参数白名单（§2.4）
        val decision = NotificationValidator.validate(args)
        if (decision !is NotificationDecision.Execute) return decision
        // 4. 频控（§2.5）：只约束会上屏的 post/update；cancel 豁免
        if (decision.spec.op != NotificationOp.CANCEL && !rateLimiter.tryAcquire()) {
            return NotificationDecision.Reject("rate_limited")
        }
        return decision
    }

    companion object {
        const val RATE_LIMIT_MAX = 6
        const val RATE_LIMIT_WINDOW_MS = 60_000L
    }
}
