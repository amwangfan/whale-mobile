package com.wangfanlab.whale.modules.safeaction

import com.wangfanlab.whale.modules.notifications.SlidingWindowRateLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phone.device.safe_action 纯逻辑单元测试（B3 前半）：
 * - action 枚举白名单（launch_app / open_deeplink；未知 → invalid_message）
 * - launch_app：包名格式、敏感包拒绝（scope_denied）、launchable 校验
 *   （PM 查询以注入谓词 mock，不触碰 Android 框架）
 * - open_deeplink：仅 http/https；intent://、file:// 等 scheme 拒绝
 * - 校验链顺序（暂停 → 开关 → 白名单 → 共享频控 6/min）与精确 error_code
 * 全部为 JVM 测试，不触碰 Android 框架。
 */
class SafeActionLogicTest {

    /** mock PM：集合内包名视为「已安装且有 LAUNCHER 入口」。 */
    private val launchable = mutableSetOf("com.tencent.mm", "com.example.demo")

    private fun decider(
        enabled: Boolean = true,
        paused: Boolean = false,
        limiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(6, 60_000),
    ) = SafeActionDecider(
        moduleEnabled = { enabled },
        allPaused = { paused },
        isLaunchable = { it in launchable },
        rateLimiter = limiter,
    )

    // ------------------------------------------------------------ launch_app 白名单

    @Test
    fun `launch_app 合法 - 已安装有 LAUNCHER 入口放行`() {
        val d = decider()
        val r = d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
        val spec = (r as SafeActionDecision.Execute).spec
        assertEquals(SafeActionOp.LAUNCH_APP, spec.op)
        assertEquals("com.tencent.mm", spec.packageName)
        assertNull(spec.url)
    }

    @Test
    fun `launch_app 包名畸形 - 缺字段 单段 非法字符 超长 → invalid_message`() {
        val d = decider()
        for (args in listOf(
            mapOf("action" to "launch_app"), // 缺 package
            mapOf("action" to "launch_app", "package" to ""),
            mapOf("action" to "launch_app", "package" to "  "),
            mapOf("action" to "launch_app", "package" to "nodots"),
            mapOf("action" to "launch_app", "package" to "com..double"),
            mapOf("action" to "launch_app", "package" to "com.-bad"),
            mapOf("action" to "launch_app", "package" to "com.example.app;rm -rf"),
            mapOf("action" to "launch_app", "package" to "a.".repeat(70)), // 超长
        )) {
            val r = d.onRequest(args)
            assertEquals(
                "$args 应拒绝",
                "invalid_message",
                (r as SafeActionDecision.Reject).errorCode,
            )
        }
    }

    @Test
    fun `launch_app 敏感包拒绝 - 设置与安全中心类 → scope_denied`() {
        val d = decider()
        for (pkg in listOf(
            "com.android.settings",
            "com.android.settings.SubSettings",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
        )) {
            // 即使「已安装且 launchable」也拒绝（策略优先于存在性）
            launchable.add(pkg)
            val r = d.onRequest(mapOf("action" to "launch_app", "package" to pkg))
            assertEquals(
                "$pkg 应 scope_denied",
                "scope_denied",
                (r as SafeActionDecision.Reject).errorCode,
            )
        }
    }

    @Test
    fun `launch_app 未安装或无 LAUNCHER 入口 → unavailable`() {
        val d = decider()
        val r = d.onRequest(mapOf("action" to "launch_app", "package" to "com.ghost.app"))
        assertEquals("unavailable", (r as SafeActionDecision.Reject).errorCode)
    }

    // ------------------------------------------------------------ open_deeplink 白名单

    @Test
    fun `open_deeplink 合法 - http 与 https 放行（大小写不敏感）`() {
        val d = decider()
        for (u in listOf(
            "https://whale.example.internal/x?a=1",
            "http://10.0.2.2:8080/path",
            "HTTPS://Example.COM/Upper",
        )) {
            val r = d.onRequest(mapOf("action" to "open_deeplink", "url" to u))
            assertTrue("$u 应放行", r is SafeActionDecision.Execute)
            assertEquals(SafeActionOp.OPEN_DEEPLINK, (r as SafeActionDecision.Execute).spec.op)
        }
    }

    @Test
    fun `open_deeplink 危险 scheme 拒绝 - intent file javascript content tel → scope_denied`() {
        val d = decider()
        for (u in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing;end",
            "file:///sdcard/secrets.txt",
            "javascript:alert(1)",
            "content://com.android.contacts/contacts/1",
            "tel:10086",
            "whale://internal/page",
        )) {
            val r = d.onRequest(mapOf("action" to "open_deeplink", "url" to u))
            assertEquals(
                "$u 应 scope_denied",
                "scope_denied",
                (r as SafeActionDecision.Reject).errorCode,
            )
        }
    }

    @Test
    fun `open_deeplink 畸形 URL - 空 缺 scheme 裸 scheme → invalid_message`() {
        val d = decider()
        for (args in listOf(
            mapOf("action" to "open_deeplink"), // 缺 url
            mapOf("action" to "open_deeplink", "url" to ""),
            mapOf("action" to "open_deeplink", "url" to "  "),
            mapOf("action" to "open_deeplink", "url" to "example.com/noscheme"),
            mapOf("action" to "open_deeplink", "url" to "https:"), // 裸 scheme 无 authority
            mapOf("action" to "open_deeplink", "url" to "https://"), // 空 host
            mapOf("action" to "open_deeplink", "url" to ":foo"),
        )) {
            val r = d.onRequest(args)
            assertEquals(
                "$args 应 invalid_message",
                "invalid_message",
                (r as SafeActionDecision.Reject).errorCode,
            )
        }
    }

    @Test
    fun `未知 action 拒绝 → invalid_message`() {
        val d = decider()
        for (a in listOf("reboot", "install", "LAUNCH", "", " open_deeplink ")) {
            val r = d.onRequest(mapOf("action" to a, "url" to "https://x.y"))
            if (a == " open_deeplink ") {
                // trim+lowercase 后合法（与通知模块 op 处理一致）
                assertTrue(r is SafeActionDecision.Execute)
            } else {
                assertEquals(
                    "$a 应拒绝",
                    "invalid_message",
                    (r as SafeActionDecision.Reject).errorCode,
                )
            }
        }
    }

    // ------------------------------------------------------------ 校验链顺序

    @Test
    fun `全局暂停 → unavailable（优先于开关与白名单）`() {
        val d = decider(enabled = true, paused = true)
        val r = d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
        assertEquals("unavailable", (r as SafeActionDecision.Reject).errorCode)
        // 暂停先于白名单：畸形参数也回 unavailable
        val r2 = d.onRequest(mapOf("action" to "explode"))
        assertEquals("unavailable", (r2 as SafeActionDecision.Reject).errorCode)
    }

    @Test
    fun `模块开关默认关 → unavailable（宿主默认 false）`() {
        // 与 SafeActionModule.isEnabled 默认 false 对齐；此处显式模拟
        val d = decider(enabled = false)
        val r = d.onRequest(mapOf("action" to "open_deeplink", "url" to "https://x.y"))
        assertEquals("unavailable", (r as SafeActionDecision.Reject).errorCode)
    }

    @Test
    fun `白名单先于频控 - 非法请求不消耗频控配额`() {
        val limiter = SlidingWindowRateLimiter(6, 60_000)
        val d = decider(limiter = limiter)
        // 10 次非法请求
        repeat(10) {
            val r = d.onRequest(mapOf("action" to "explode"))
            assertEquals("invalid_message", (r as SafeActionDecision.Reject).errorCode)
        }
        assertEquals(0, limiter.countInWindow())
        // 配额仍满：合法请求 6 次全过，第 7 次限流
        repeat(6) {
            val r = d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
            assertTrue(r is SafeActionDecision.Execute)
        }
        val r7 = d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
        assertEquals("rate_limited", (r7 as SafeActionDecision.Reject).errorCode)
    }

    @Test
    fun `频控共享 - launch_app 与 open_deeplink 合计 6 次每分钟`() {
        var t = 1_000_000L
        val limiter = SlidingWindowRateLimiter(6, 60_000, clockMs = { t })
        val d = decider(limiter = limiter)
        // 3 launch + 3 deeplink = 6 全过
        repeat(3) {
            assertTrue(
                d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
                        is SafeActionDecision.Execute,
            )
            assertTrue(
                d.onRequest(mapOf("action" to "open_deeplink", "url" to "https://x.y/$it"))
                        is SafeActionDecision.Execute,
            )
        }
        // 第 7 次（无论哪种动作）限流
        val r7 = d.onRequest(mapOf("action" to "open_deeplink", "url" to "https://x.y/7"))
        assertEquals("rate_limited", (r7 as SafeActionDecision.Reject).errorCode)
        // 窗口滑过后恢复
        t += 61_000
        assertTrue(
            d.onRequest(mapOf("action" to "launch_app", "package" to "com.tencent.mm"))
                    is SafeActionDecision.Execute,
        )
    }

    @Test
    fun `scope_denied 先于频控 - 敏感包不消耗配额`() {
        val limiter = SlidingWindowRateLimiter(6, 60_000)
        val d = decider(limiter = limiter)
        launchable.add("com.android.settings")
        repeat(8) {
            val r = d.onRequest(
                mapOf("action" to "launch_app", "package" to "com.android.settings"),
            )
            assertEquals("scope_denied", (r as SafeActionDecision.Reject).errorCode)
        }
        assertEquals(0, limiter.countInWindow())
    }
}
