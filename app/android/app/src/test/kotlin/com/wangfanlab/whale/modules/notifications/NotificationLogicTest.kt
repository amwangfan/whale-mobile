package com.wangfanlab.whale.modules.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phone.notification.post 纯逻辑单元测试（DESIGN.md §5）：
 * 参数白名单（超长截断/op 语义/渠道白名单/click_action 降级）、
 * 滑动窗口频控、校验链顺序与 error_code。
 * 全部为 JVM 测试，不触碰 Android 框架。
 */
class NotificationLogicTest {

    // ------------------------------------------------------------ 参数白名单

    @Test
    fun `defaults - op 省略为 post，channel 省略为 whale_reminder`() {
        val d = NotificationValidator.validate(emptyMap<String, Any?>())
        val spec = (d as NotificationDecision.Execute).spec
        assertEquals(NotificationOp.POST, spec.op)
        assertEquals("whale_reminder", spec.channelId)
        assertNull(spec.notificationTag)
        assertFalse(spec.openAppOnClick)
    }

    @Test
    fun `title 超 100 字符截断、text 超 500 字符截断，不拒绝`() {
        val d = NotificationValidator.validate(
            mapOf("title" to "t".repeat(150), "text" to "x".repeat(600)),
        )
        val spec = (d as NotificationDecision.Execute).spec
        assertEquals(100, spec.title.length)
        assertEquals(500, spec.text.length)
    }

    @Test
    fun `非法 op 拒绝 invalid_message`() {
        val d = NotificationValidator.validate(mapOf("op" to "explode"))
        assertEquals("invalid_message", (d as NotificationDecision.Reject).errorCode)
    }

    @Test
    fun `渠道白名单 - 注册表内放行，表外拒绝`() {
        for (ch in listOf("whale_reminder", "whale_call", "whale_status")) {
            val d = NotificationValidator.validate(mapOf("channel_id" to ch))
            assertTrue("$ch 应放行", d is NotificationDecision.Execute)
        }
        val bad = NotificationValidator.validate(mapOf("channel_id" to "evil_channel"))
        assertEquals("invalid_message", (bad as NotificationDecision.Reject).errorCode)
    }

    @Test
    fun `op 语义 - update cancel 必带 notification_tag`() {
        val updNoTag = NotificationValidator.validate(mapOf("op" to "update"))
        assertEquals("invalid_message", (updNoTag as NotificationDecision.Reject).errorCode)
        val cancelNoTag = NotificationValidator.validate(mapOf("op" to "cancel"))
        assertEquals("invalid_message", (cancelNoTag as NotificationDecision.Reject).errorCode)
        val cancelBlankTag =
            NotificationValidator.validate(mapOf("op" to "cancel", "notification_tag" to "  "))
        assertEquals("invalid_message", (cancelBlankTag as NotificationDecision.Reject).errorCode)

        val upd = NotificationValidator.validate(
            mapOf("op" to "update", "notification_tag" to "daily-reminder"),
        )
        val spec = (upd as NotificationDecision.Execute).spec
        assertEquals(NotificationOp.UPDATE, spec.op)
        assertEquals("daily-reminder", spec.notificationTag)

        val cancel = NotificationValidator.validate(
            mapOf("op" to "cancel", "notification_tag" to "daily-reminder"),
        )
        assertEquals(NotificationOp.CANCEL, (cancel as NotificationDecision.Execute).spec.op)
    }

    @Test
    fun `click_action 白名单 - 仅 open_app 生效，其余降级不 fail`() {
        val none = NotificationValidator.validate(emptyMap<String, Any?>())
        assertFalse((none as NotificationDecision.Execute).spec.openAppOnClick)

        val open = NotificationValidator.validate(mapOf("click_action" to "open_app"))
        assertTrue((open as NotificationDecision.Execute).spec.openAppOnClick)

        // 任意深链/第三方跳转：降级为无点击动作，请求本身仍执行
        val evil = NotificationValidator.validate(
            mapOf("click_action" to "intent://evil.example/hijack"),
        )
        assertFalse((evil as NotificationDecision.Execute).spec.openAppOnClick)
    }

    // ------------------------------------------------------------ 频控（滑动窗口 6/分钟）

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `频控 - 窗口内第 7 条被拒，窗口滑动后放行`() {
        val clock = FakeClock()
        val rl = SlidingWindowRateLimiter(maxPermits = 6, windowMs = 60_000, clockMs = { clock.now })
        repeat(6) { assertTrue("第 ${it + 1} 条应放行", rl.tryAcquire()) }
        assertFalse("第 7 条超限", rl.tryAcquire())
        assertEquals(6, rl.countInWindow()) // 超限不计入

        clock.advance(59_999)
        assertFalse("窗口未滑出仍超限", rl.tryAcquire())
        clock.advance(1) // 正好 60s，最早一条滑出窗口
        assertTrue("窗口滑出后放行", rl.tryAcquire())
    }

    @Test
    fun `校验链 - 频控只约束 post update，cancel 豁免`() {
        val clock = FakeClock()
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
            rateLimiter = SlidingWindowRateLimiter(6, 60_000) { clock.now },
        )
        repeat(6) {
            assertTrue(decider.onRequest(mapOf("op" to "post")) is NotificationDecision.Execute)
        }
        val seventh = decider.onRequest(mapOf("op" to "post"))
        assertEquals("rate_limited", (seventh as NotificationDecision.Reject).errorCode)
        // cancel 豁免频控（撤销只降噪声）
        val cancel = decider.onRequest(mapOf("op" to "cancel", "notification_tag" to "t"))
        assertTrue(cancel is NotificationDecision.Execute)
    }

    // ------------------------------------------------------------ 校验链顺序（§2）

    @Test
    fun `校验链 - 模块关闭回 unavailable 且先于权限检查`() {
        val decider = NotificationDecider(
            moduleEnabled = { false },
            permissionGranted = { false }, // 即便权限缺失，模块开关优先判定
        )
        val d = decider.onRequest(mapOf("op" to "post"))
        assertEquals("unavailable", (d as NotificationDecision.Reject).errorCode)
    }

    @Test
    fun `校验链 - 权限缺失回 permission_missing`() {
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { false },
        )
        val d = decider.onRequest(mapOf("op" to "post"))
        assertEquals("permission_missing", (d as NotificationDecision.Reject).errorCode)
    }

    // ------------------------------------------------------------ 全局暂停（A1）

    @Test
    fun `全局暂停 - 暂停时回 unavailable，优先于模块开关与权限`() {
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
            allPaused = { true },
        )
        val d = decider.onRequest(mapOf("op" to "post"))
        assertEquals("unavailable", (d as NotificationDecision.Reject).errorCode)
    }

    @Test
    fun `全局暂停 - 暂停优先于模块开关判定（同为 unavailable，但不消耗频控配额）`() {
        val rl = SlidingWindowRateLimiter(1, 60_000)
        val decider = NotificationDecider(
            moduleEnabled = { false },
            permissionGranted = { true },
            allPaused = { true },
            rateLimiter = rl,
        )
        val d = decider.onRequest(mapOf("op" to "post"))
        assertEquals("unavailable", (d as NotificationDecision.Reject).errorCode)
        assertEquals("暂停拒绝不计频控", 0, rl.countInWindow())
    }

    @Test
    fun `全局暂停 - 恢复后正常执行（运行时读取标志）`() {
        var paused = true
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
            allPaused = { paused },
        )
        assertTrue(
            decider.onRequest(mapOf("op" to "post")) is NotificationDecision.Reject,
        )
        paused = false
        assertTrue(
            decider.onRequest(mapOf("op" to "post")) is NotificationDecision.Execute,
        )
    }

    @Test
    fun `全局暂停 - 默认不暂停（兼容既有行为）`() {
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
        )
        assertTrue(
            decider.onRequest(mapOf("op" to "post")) is NotificationDecision.Execute,
        )
    }

    @Test
    fun `校验链 - 全部通过回 Execute`() {
        val decider = NotificationDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
        )
        val d = decider.onRequest(
            mapOf(
                "op" to "update",
                "notification_tag" to "t1",
                "channel_id" to "whale_call",
                "title" to "来电",
                "text" to "老板",
                "click_action" to "open_app",
            ),
        )
        val spec = (d as NotificationDecision.Execute).spec
        assertEquals(NotificationOp.UPDATE, spec.op)
        assertEquals("whale_call", spec.channelId)
        assertEquals("来电", spec.title)
        assertEquals("老板", spec.text)
        assertEquals("t1", spec.notificationTag)
        assertTrue(spec.openAppOnClick)
    }
}
