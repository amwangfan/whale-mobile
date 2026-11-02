package com.wangfanlab.whale.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重连退避（1s→60s 封顶，±20% 抖动）单元测试，时钟与随机源均注入。 */
class BackoffTest {

    /** 零抖动（random=0.5）下的纯指数序列。 */
    @Test
    fun `exponential growth without jitter doubles until cap`() {
        val backoff = ReconnectBackoff(random = { 0.5 })
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        for (e in expected) {
            assertEquals(e, backoff.nextDelayMs())
        }
        // 第 7 次起封顶 60s
        repeat(5) { assertEquals(60_000L, backoff.nextDelayMs()) }
    }

    @Test
    fun `positive jitter adds up to 20 percent`() {
        val backoff = ReconnectBackoff(random = { 1.0 })
        assertEquals(1_200L, backoff.nextDelayMs())  // 1000 * 1.2
        assertEquals(2_400L, backoff.nextDelayMs())  // 2000 * 1.2
    }

    @Test
    fun `negative jitter subtracts up to 20 percent`() {
        val backoff = ReconnectBackoff(random = { 0.0 })
        assertEquals(800L, backoff.nextDelayMs())    // 1000 * 0.8
        assertEquals(1_600L, backoff.nextDelayMs())  // 2000 * 0.8
    }

    @Test
    fun `jitter stays within plus minus 20 percent band`() {
        var i = 0
        val samples = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.999)
        val backoff = ReconnectBackoff(random = { samples[i++ % samples.size] })
        repeat(20) {
            val attempt = backoff.attempts
            val cap = backoff.baseDelayMs(attempt)
            val delay = backoff.nextDelayMs()
            assertTrue(
                "delay=$delay 应在 [${cap * 0.8}, ${cap * 1.2}] 内",
                delay >= (cap * 0.8).toLong() - 1 && delay <= (cap * 1.2).toLong() + 1,
            )
        }
    }

    @Test
    fun `reset returns to base delay`() {
        val backoff = ReconnectBackoff(random = { 0.5 })
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(3, backoff.attempts)
        backoff.reset()
        assertEquals(0, backoff.attempts)
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `nextRetryAt uses injected clock`() {
        var now = 100_000L
        val backoff = ReconnectBackoff(clock = { now }, random = { 0.5 })
        assertEquals(101_000L, backoff.nextRetryAtMs())
        now = 200_000L
        assertEquals(202_000L, backoff.nextRetryAtMs())
    }
}
