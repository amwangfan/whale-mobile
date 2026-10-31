package com.wangfanlab.whale.transport

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 重连退避：指数退避 1s → 60s 封顶，每次 ±20% 抖动。
 *
 * 纯 JVM。时钟与随机源均可注入，便于确定性单元测试：
 * - [clock] 返回当前毫秒（用于计算下次重连的绝对时刻）；
 * - [random] 返回 [0,1) 均匀随机数（抖动来源）。
 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val jitterRatio: Double = 0.2,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: () -> Double = { Math.random() },
) {
    private var attempt = 0

    /** 已安排的重连次数（连接成功后应 reset）。 */
    val attempts: Int get() = attempt

    /** 连接建立并认证通过后调用，回到初始退避。 */
    fun reset() {
        attempt = 0
    }

    /** 第 attempt 次的未加抖动退避时长（指数：base * 2^n，封顶 maxMs）。 */
    fun baseDelayMs(attemptIndex: Int): Long {
        if (attemptIndex >= 62) return maxMs // 防移位溢出
        val raw = baseMs shl attemptIndex
        return if (raw <= 0 || raw > maxMs) maxMs else min(raw, maxMs)
    }

    /**
     * 计算下一次重连前的等待时长（含 ±jitterRatio 抖动），并递增 attempt。
     * 抖动范围 [cap*(1-r), cap*(1+r))；结果不保证单调，仅保证落在该区间。
     */
    fun nextDelayMs(): Long {
        val cap = baseDelayMs(attempt)
        attempt += 1
        val jitter = cap * jitterRatio * (2.0 * random() - 1.0)
        return (cap + jitter).roundToLong().coerceIn(0L, maxMs + (maxMs * jitterRatio).toLong())
    }

    /** 下一次重连的绝对时刻（注入时钟口径）。 */
    fun nextRetryAtMs(): Long = clock() + nextDelayMs()
}
