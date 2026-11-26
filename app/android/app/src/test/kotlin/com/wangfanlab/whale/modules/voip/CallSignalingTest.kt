package com.wangfanlab.whale.modules.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 call.* 上行信令纯逻辑测试（呼出加回主线）：
 * - CallSignalBuilder 载荷形状（answer 回显固定媒体参数 pcm_s16le/16k/mono/20ms、
 *   expires_at 回显、accepted；state/hangup 枚举校验；session_id ULID 26 严格模式）
 * - CallSessionFlow 状态机：ringing→active→ended 序列、拒接、远端挂断不回声、
 *   振铃窗超时、媒体错误、幂等、呼出会话（autoAnswer）抑制 ringing 上行
 * - 振铃窗计算：min(60s, expires_at 剩余)，下限 1s
 *
 * 全部 JVM 测试，不触碰 Android 框架。
 */
class CallSignalingTest {

    private val sid = "01J9XK3EM8TQ2ZP0VN6W4RA8GG" // 26 字符 ULID（契约示例）
    private val expiresAt = 1_794_052_940_000L

    // ------------------------------------------------------------ CallSignalBuilder

    @Test
    fun `answer 载荷 - 回显固定媒体参数与 expires_at accepted true`() {
        val s = CallSignalBuilder.answer(sid, expiresAt, accepted = true)
        assertEquals("call.answer", s.type)
        assertEquals(sid, s.payload["session_id"])
        assertEquals(expiresAt, s.payload["expires_at"])
        assertEquals(true, s.payload["accepted"])
        @Suppress("UNCHECKED_CAST")
        val media = s.payload["media_params"] as Map<String, Any?>
        assertEquals("pcm_s16le", media["codec"])
        assertEquals(16000, media["sample_rate"])
        assertEquals(1, media["channels"])
        assertEquals(20, media["frame_ms"]) // 契约钦定恒 20ms
    }

    @Test
    fun `answer 载荷 - 拒接 accepted false 仍携带媒体参数`() {
        val s = CallSignalBuilder.answer(sid, expiresAt, accepted = false)
        assertEquals(false, s.payload["accepted"])
        assertTrue(s.payload.containsKey("media_params"))
        assertTrue(s.payload.containsKey("expires_at"))
    }

    @Test
    fun `state 载荷 - 合法枚举放行 非法抛异常`() {
        for (st in listOf("ringing", "active", "held", "ended", "failed")) {
            val s = CallSignalBuilder.state(sid, st)
            assertEquals("call.state", s.type)
            assertEquals(st, s.payload["state"])
        }
        for (bad in listOf("", "answered", "ACTIVE", "ringing ")) {
            try {
                CallSignalBuilder.state(sid, bad)
                org.junit.Assert.fail("state=$bad 应抛异常")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `hangup 载荷 - 合法 reason 放行 非法抛异常`() {
        for (r in listOf("normal", "timeout", "rejected", "error")) {
            val s = CallSignalBuilder.hangup(sid, r)
            assertEquals("call.hangup", s.type)
            assertEquals(r, s.payload["reason"])
        }
        for (bad in listOf("", "local", "remote", "NORMAL")) {
            try {
                CallSignalBuilder.hangup(sid, bad)
                org.junit.Assert.fail("reason=$bad 应抛异常")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `session_id ULID 严格模式 - 非 Crockford 字符与长度偏差拒绝`() {
        // 合法：26 字符 Crockford（数字+大写无 I/L/O/U）
        CallSignalBuilder.state("01J9XK3EM8TQ2ZP0VN6W4RA8GG", "ringing")
        // 含 L（Crockford 排除）
        for (bad in listOf(
            "01J0000000000000000000CALL", // 含 L
            "01J9XK3EM8TQ2ZP0VN6W4RA8G",  // 25 字符
            "01J9XK3EM8TQ2ZP0VN6W4RA8GGX", // 27 字符
            "01j9xk3em8tq2zp0vn6w4ra8gg",  // 小写
            "",
        )) {
            try {
                CallSignalBuilder.state(bad, "ringing")
                org.junit.Assert.fail("session_id=$bad 应抛异常")
            } catch (_: IllegalArgumentException) {}
        }
        // invite 校验同步收紧
        assertTrue(
            com.wangfanlab.whale.modules.voip.CallInviteLogic.SESSION_ID_REGEX
                .matches("01J9XK3EM8TQ2ZP0VN6W4RA8GG"),
        )
        assertFalse(
            com.wangfanlab.whale.modules.voip.CallInviteLogic.SESSION_ID_REGEX
                .matches("01J0000000000000000000CALL"),
        )
    }

    // ------------------------------------------------------------ CallSessionFlow

    @Test
    fun `来电全流程 - ringing answer active 本地挂断 normal ended`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        var out = f.onRinging()
        assertEquals(listOf("call.state"), out.map { it.type })
        assertEquals("ringing", out[0].payload["state"])

        out = f.onAnswered()
        assertEquals(listOf("call.answer", "call.state"), out.map { it.type })
        assertEquals(true, out[0].payload["accepted"])
        assertEquals("active", out[1].payload["state"])

        out = f.onLocalEnded()
        assertEquals(listOf("call.hangup", "call.state"), out.map { it.type })
        assertEquals("normal", out[0].payload["reason"])
        assertEquals("ended", out[1].payload["state"])

        // 幂等：结束后任何回调不再出信令
        assertTrue(f.onLocalEnded().isEmpty())
        assertTrue(f.onAnswered().isEmpty())
        assertTrue(f.onRingTimeout().isEmpty())
        assertTrue(f.onError().isEmpty())
    }

    @Test
    fun `拒接 - answer false + state ended 且不发 hangup`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        f.onRinging()
        val out = f.onRejected()
        assertEquals(listOf("call.answer", "call.state"), out.map { it.type })
        assertEquals(false, out[0].payload["accepted"])
        assertEquals("ended", out[1].payload["state"])
        assertTrue(f.onLocalEnded().isEmpty())
    }

    @Test
    fun `远端挂断 - 只回 state ended 不回声 hangup（防回环）`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        f.onRinging()
        f.onAnswered()
        f.onRemoteHangup()
        val out = f.onLocalEnded()
        assertEquals(listOf("call.state"), out.map { it.type })
        assertEquals("ended", out[0].payload["state"])
    }

    @Test
    fun `振铃窗超时 - hangup timeout + state failed`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        f.onRinging()
        val out = f.onRingTimeout()
        assertEquals(listOf("call.hangup", "call.state"), out.map { it.type })
        assertEquals("timeout", out[0].payload["reason"])
        assertEquals("failed", out[1].payload["state"])
        // 随后 Telecom 本地清理回调不再出信令
        assertTrue(f.onLocalEnded().isEmpty())
    }

    @Test
    fun `媒体失败 - hangup error + state failed（通话中与响铃中同形）`() {
        val f1 = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        f1.onRinging()
        f1.onAnswered()
        val out1 = f1.onError()
        assertEquals("error", out1[0].payload["reason"])
        assertEquals("failed", out1[1].payload["state"])

        val f2 = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        val out2 = f2.onError()
        assertEquals("error", out2[0].payload["reason"])
        assertEquals("failed", out2[1].payload["state"])
    }

    @Test
    fun `呼出会话 autoAnswer - 抑制 ringing 上行 接听正常出 answer+active`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = true)
        assertTrue("呼出会话不上行 ringing", f.onRinging().isEmpty())
        val out = f.onAnswered()
        assertEquals(listOf("call.answer", "call.state"), out.map { it.type })
        assertEquals(true, out[0].payload["accepted"])
    }

    @Test
    fun `响铃中本地结束按拒接语义 - answer false + ended`() {
        val f = CallSessionFlow(sid, expiresAt, autoAnswer = false)
        f.onRinging()
        val out = f.onLocalEnded()
        assertEquals(listOf("call.answer", "call.state"), out.map { it.type })
        assertEquals(false, out[0].payload["accepted"])
    }

    // ------------------------------------------------------------ 振铃窗计算

    @Test
    fun `振铃窗 - min 60s 与 expires 剩余 下限 1s`() {
        assertEquals(60_000L, CallSessionFlow.ringWindowMs(expiresAt = 1_061_000L, nowMs = 1_000_000L))
        assertEquals(30_000L, CallSessionFlow.ringWindowMs(expiresAt = 1_030_000L, nowMs = 1_000_000L))
        assertEquals(1_000L, CallSessionFlow.ringWindowMs(expiresAt = 1_000_050L, nowMs = 1_000_000L))
        assertEquals(60_000L, CallSessionFlow.RING_WINDOW_MS)
        assertEquals(60_000L, CallSessionFlow.OUTGOING_PENDING_WINDOW_MS)
    }
}
