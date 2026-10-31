package com.wangfanlab.whale.modules.notificationread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phone.notification.read 纯逻辑单元测试（B3 前半）：
 * - App 白名单（默认空 = 一个都不读；仅勾选 App 放行）
 * - 类别过滤（ongoing / 进度条 / 媒体控制 transport）
 * - 标题脱敏（≤80 字符截断、换行折叠、空标题占位）
 * - 去重（同 app 同标题 60s 窗口；异标题/超窗放行）
 * - 校验链顺序（全局暂停 → 模块开关 → 白名单 → 过滤 → 去重）
 * 全部为 JVM 测试，不触碰 Android 框架。
 */
class NotificationReadLogicTest {

    private fun snap(
        pkg: String = "com.tencent.mm",
        label: String = "微信",
        category: String? = "msg",
        title: String = "新消息",
        ongoing: Boolean = false,
        hasProgress: Boolean = false,
        postedAt: Long = 1_000_000L,
    ) = NotifSnapshot(
        packageName = pkg,
        appLabel = label,
        postedTimeMs = postedAt,
        category = category,
        title = title,
        ongoing = ongoing,
        hasProgress = hasProgress,
    )

    private fun decider(
        enabled: Boolean = true,
        allowed: Set<String> = setOf("com.tencent.mm"),
        paused: Boolean = false,
        deduper: NotificationDeduper = NotificationDeduper(clockMs = { now }),
    ) = NotificationReadDecider(
        moduleEnabled = { enabled },
        allowedApps = { allowed },
        allPaused = { paused },
        deduper = deduper,
    )

    @Volatile
    private var now: Long = 10_000_000L

    // ------------------------------------------------------------ 白名单

    @Test
    fun `默认空白名单 - 一个都不读`() {
        val d = decider(allowed = emptySet())
        assertEquals("not_whitelisted", (d.onNotification(snap()) as NotifReadDecision.Drop).reason)
    }

    @Test
    fun `白名单内 App 放行并产出 summary - App 名冒号标题`() {
        val d = decider()
        val r = d.onNotification(snap())
        assertTrue(r is NotifReadDecision.Emit)
        assertEquals("微信: 新消息", (r as NotifReadDecision.Emit).summary)
    }

    @Test
    fun `未勾选 App 丢弃 - 即使同类目同标题`() {
        val d = decider(allowed = setOf("com.tencent.mm"))
        val r = d.onNotification(snap(pkg = "com.sina.weibo", label = "微博"))
        assertEquals("not_whitelisted", (r as NotifReadDecision.Drop).reason)
    }

    // ------------------------------------------------------------ 类别过滤

    @Test
    fun `ongoing 通知丢弃`() {
        val d = decider()
        val r = d.onNotification(snap(ongoing = true, category = "service"))
        assertEquals("filtered", (r as NotifReadDecision.Drop).reason)
    }

    @Test
    fun `进度条通知丢弃 - hasProgress 或 category=progress`() {
        val d = decider()
        assertEquals(
            "filtered",
            (d.onNotification(snap(hasProgress = true, category = null))
                    as NotifReadDecision.Drop).reason,
        )
        val d2 = decider()
        assertEquals(
            "filtered",
            (d2.onNotification(snap(category = "progress"))
                    as NotifReadDecision.Drop).reason,
        )
    }

    @Test
    fun `媒体控制类丢弃 - category=transport（大小写不敏感）`() {
        for (c in listOf("transport", "TRANSPORT", "Transport")) {
            val d = decider()
            assertEquals(
                "$c 应丢弃",
                "filtered",
                (d.onNotification(snap(category = c)) as NotifReadDecision.Drop).reason,
            )
        }
    }

    @Test
    fun `普通消息类放行 - msg social email reminder 与 null`() {
        for (c in listOf("msg", "social", "email", "reminder", null)) {
            val d = decider()
            assertTrue("$c 应放行", d.onNotification(snap(category = c)) is NotifReadDecision.Emit)
        }
    }

    // ------------------------------------------------------------ 标题脱敏

    @Test
    fun `标题超 80 字符截断不拒绝`() {
        val d = decider()
        val r = d.onNotification(snap(title = "t".repeat(200)))
        val summary = (r as NotifReadDecision.Emit).summary
        assertTrue(summary.startsWith("微信: "))
        assertEquals("微信: ".length + 80, summary.length)
    }

    @Test
    fun `标题换行折叠为空格 - 空标题回落占位`() {
        val d = decider()
        val r1 = d.onNotification(snap(title = "第一行\n第二行\r\n第三行"))
        assertEquals("微信: 第一行 第二行 第三行", (r1 as NotifReadDecision.Emit).summary)

        val d2 = decider()
        val r2 = d2.onNotification(snap(title = "  \n  "))
        assertEquals("微信: （无标题）", (r2 as NotifReadDecision.Emit).summary)
    }

    @Test
    fun `App 名超长截断 32 字符`() {
        val d = decider()
        val r = d.onNotification(snap(label = "长".repeat(60)))
        val summary = (r as NotifReadDecision.Emit).summary
        assertEquals(32 + 2 + 3, summary.length) // 32 字 label + ": " + "新消息"
    }

    // ------------------------------------------------------------ 去重

    @Test
    fun `同 app 同标题 60s 内不重复`() {
        val d = decider()
        assertTrue(d.onNotification(snap(title = "开会了")) is NotifReadDecision.Emit)
        now += 5_000 // +5s
        assertEquals(
            "duplicate",
            (d.onNotification(snap(title = "开会了")) as NotifReadDecision.Drop).reason,
        )
        now += 30_000 // 累计 +35s，仍在 60s 窗内
        assertEquals(
            "duplicate",
            (d.onNotification(snap(title = "开会了")) as NotifReadDecision.Drop).reason,
        )
    }

    @Test
    fun `超 60s 窗后同标题放行`() {
        val d = decider()
        assertTrue(d.onNotification(snap(title = "开会了")) is NotifReadDecision.Emit)
        now += 61_000
        assertTrue(d.onNotification(snap(title = "开会了")) is NotifReadDecision.Emit)
    }

    @Test
    fun `同 app 异标题不去重 - 异 app 同标题不去重`() {
        val d = decider(allowed = setOf("com.tencent.mm", "com.sina.weibo"))
        assertTrue(d.onNotification(snap(title = "A")) is NotifReadDecision.Emit)
        now += 1_000
        assertTrue(d.onNotification(snap(title = "B")) is NotifReadDecision.Emit)
        now += 1_000
        assertTrue(
            d.onNotification(snap(pkg = "com.sina.weibo", label = "微博", title = "A"))
                    is NotifReadDecision.Emit,
        )
    }

    // ------------------------------------------------------------ 校验链顺序

    @Test
    fun `全局暂停优先于一切 - 白名单内也丢弃`() {
        val d = decider(paused = true)
        assertEquals("paused", (d.onNotification(snap()) as NotifReadDecision.Drop).reason)
    }

    @Test
    fun `模块开关关 - 白名单内也丢弃`() {
        val d = decider(enabled = false)
        assertEquals("disabled", (d.onNotification(snap()) as NotifReadDecision.Drop).reason)
    }

    @Test
    fun `链序 - 暂停先于开关，开关先于白名单`() {
        // 暂停+关 → paused（而非 disabled）
        val d1 = decider(enabled = false, paused = true)
        assertEquals("paused", (d1.onNotification(snap()) as NotifReadDecision.Drop).reason)
        // 关+不在白名单 → disabled（而非 not_whitelisted）
        val d2 = decider(enabled = false, allowed = emptySet())
        assertEquals("disabled", (d2.onNotification(snap()) as NotifReadDecision.Drop).reason)
    }

    @Test
    fun `开关动态生效 - 关后不产生事件，重开恢复`() {
        var enabled = true
        val d = NotificationReadDecider(
            moduleEnabled = { enabled },
            allowedApps = { setOf("com.tencent.mm") },
            allPaused = { false },
            deduper = NotificationDeduper(clockMs = { now }),
        )
        assertTrue(d.onNotification(snap(title = "A")) is NotifReadDecision.Emit)
        enabled = false
        now += 61_000
        assertEquals("disabled", (d.onNotification(snap(title = "A")) as NotifReadDecision.Drop).reason)
        enabled = true
        now += 61_000
        assertTrue(d.onNotification(snap(title = "A")) is NotifReadDecision.Emit)
    }

    // ------------------------------------------------------------ Deduper 单元

    @Test
    fun `Deduper 边界 - 恰 60s 放行，59_999 丢弃`() {
        var t = 0L
        val dd = NotificationDeduper(clockMs = { t })
        assertTrue(dd.shouldEmit("p", "t"))
        t = 59_999
        assertFalse(dd.shouldEmit("p", "t"))
        t = 60_000
        assertTrue(dd.shouldEmit("p", "t"))
    }
}
