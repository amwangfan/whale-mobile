package com.wangfanlab.whale.modules.notification2

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * 渠道注册表 + 渲染器 + 深链路由 + 去重器（collapse/group）测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationChannelRendererTest {

    private lateinit var context: Context
    private lateinit var channels: NotificationChannelRegistry
    private lateinit var renderer: NotificationRenderer

    private val dummyPi: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NotificationActionReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        channels = NotificationChannelRegistry(context)
        channels.ensureChannels()
        renderer = NotificationRenderer(context, channels)
    }

    // ------------------------------------------------------------  渠道

    @Test
    fun `六渠道创建且重要度符合修正方案（approval=high，system=low）`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        for (id in NotificationChannelRegistry.ALL_CHANNELS) {
            assertNotNull("渠道 $id 未创建", nm.getNotificationChannel(id))
        }
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            nm.getNotificationChannel(NotificationChannelRegistry.CHANNEL_APPROVAL).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            nm.getNotificationChannel(NotificationChannelRegistry.CHANNEL_SYSTEM).importance,
        )
        // 幂等：重复创建不抛异常、不覆盖
        channels.ensureChannels()
    }

    @Test
    fun `category 到渠道映射（未知归入 system）`() {
        assertEquals("whale_chat", channels.channelFor("chat.message"))
        assertEquals("whale_task", channels.channelFor("task.progress"))
        assertEquals("whale_approval", channels.channelFor("approval.requested"))
        assertEquals("whale_device", channels.channelFor("device.offline"))
        assertEquals("whale_reminder", channels.channelFor("reminder.triggered"))
        assertEquals("whale_system", channels.channelFor("memory.updated"))
        assertEquals("whale_system", channels.channelFor("future.unknown"))
    }

    // ------------------------------------------------------------  渲染

    private fun render(json: String, feedback: String? = null): Notification {
        val e = NotificationEventParser.parseEvent(json)!!
        return renderer.render(e, dummyPi, dummyPi, { dummyPi }, feedback)
    }

    @Test
    fun `审批类带两个操作按钮（批准、拒绝）`() {
        val actions = """[{"action_id":"approve","label":"批准"},{"action_id":"reject","label":"拒绝"}]"""
        val n = render(
            eventJson(category = "approval.requested", actions = actions),
        )
        assertEquals(2, n.actions.size)
        assertEquals("批准", n.actions[0].title)
        assertEquals("拒绝", n.actions[1].title)
    }

    @Test
    fun `任务进度 - body 尾部百分比解析为确定进度`() {
        val n = render(
            eventJson(category = "task.progress", body = "正在执行第 3 步，已完成 45%"),
        )
        assertEquals(45, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        // 合并更新不连弹
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `任务进度 - 无百分比时退化为不确定进度`() {
        val n = render(eventJson(category = "task.progress", body = "执行中"))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `聊天 - MessagingStyle 多条合并（同 group_key）`() {
        val g = "conv_001"
        render(eventJson(category = "chat.message", title = "小鲸", body = "第一条", groupKey = g))
        val n2 = render(
            eventJson(category = "chat.message", title = "小鲸", body = "第二条", groupKey = g),
        )
        // 框架 MessagingStyle 无静态提取器，用 androidx.core 的兼容提取（extras 键一致）
        val style = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n2)
        assertNotNull(style)
        assertEquals(2, style!!.messages.size)
        assertEquals("第一条", style.messages[0].text.toString())
        assertEquals("第二条", style.messages[1].text.toString())
    }

    @Test
    fun `处理中反馈态 - 隐藏操作按钮并带不确定进度`() {
        val actions = """[{"action_id":"approve","label":"批准"}]"""
        val n = render(
            eventJson(category = "approval.requested", actions = actions),
            feedback = NotificationActionReceiver.FEEDBACK_PENDING,
        )
        assertTrue(n.actions == null || n.actions.isEmpty())
        assertTrue(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `group_key 映射为通知分组`() {
        val n = render(eventJson(groupKey = "task_001"))
        assertEquals("task_001", n.group)
    }

    // ------------------------------------------------------------  去重器 collapse

    @Test
    fun `collapse_key 同键映射同一系统通知 id（替换更新），缺省回退 notification_id`() {
        val inbox = NotificationInbox(context, freshDbName())
        val dedup = NotificationDeduplicator(inbox)
        val a = NotificationEventParser.parseEvent(eventJson(collapseKey = "ck_1"))!!
        val b = NotificationEventParser.parseEvent(eventJson(collapseKey = "ck_1"))!!
        val c = NotificationEventParser.parseEvent(eventJson(collapseKey = "ck_2"))!!
        assertEquals(dedup.systemNotificationIdFor(a), dedup.systemNotificationIdFor(b))
        assertNotEquals(dedup.systemNotificationIdFor(a), dedup.systemNotificationIdFor(c))
        val noCollapse = NotificationEventParser.parseEvent(eventJson(collapseKey = null))!!
        assertEquals(
            NotificationDeduplicator.stableIdOf(noCollapse.notificationId),
            dedup.systemNotificationIdFor(noCollapse),
        )
        inbox.close()
    }

    // ------------------------------------------------------------  深链路由

    @Test
    fun `target 路由 - 五类已知类型与兜底首页`() {
        assertEquals("/task/t1", NotificationDeepLinkRouter.routeFor(NotificationTarget("task", "t1")))
        assertEquals("/conversation/c1", NotificationDeepLinkRouter.routeFor(NotificationTarget("conversation", "c1")))
        assertEquals("/device/d1", NotificationDeepLinkRouter.routeFor(NotificationTarget("device", "d1")))
        assertEquals("/approval/a1", NotificationDeepLinkRouter.routeFor(NotificationTarget("approval", "a1")))
        assertEquals("/chat/c1", NotificationDeepLinkRouter.routeFor(NotificationTarget("chat", "c1")))
        assertEquals("/task", NotificationDeepLinkRouter.routeFor(NotificationTarget("task", null)))
        assertEquals("/", NotificationDeepLinkRouter.routeFor(NotificationTarget("none", null)))
        assertEquals("/", NotificationDeepLinkRouter.routeFor(NotificationTarget("unknown_x", "y")))
        assertEquals("/", NotificationDeepLinkRouter.routeFor(null))
    }

    @Test
    fun `buildLaunchIntent 携带路由 extras 指向 MainActivity`() {
        val intent = NotificationDeepLinkRouter.buildLaunchIntent(
            context, NotificationTarget("task", "t1"), "notif_x", "evt_x",
        )
        assertEquals("/task/t1", intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_ROUTE))
        assertEquals("task", intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_TARGET_TYPE))
        assertEquals("t1", intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_TARGET_ID))
        assertEquals("evt_x", intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_EVENT_ID))
        assertEquals(
            "com.wangfanlab.whale.MainActivity",
            intent.component?.className,
        )
    }

    @Test
    fun `权限管理器 - 28 及以下看通知总开关`() {
        val pm = NotificationPermissionManager(context)
        // Robolectric 默认通知开启
        assertEquals("granted", pm.status())
        shadowOf(context.getSystemService(NotificationManager::class.java))
        // 引导 intent 构造不抛异常
        assertNotNull(pm.appNotificationSettingsIntent())
        assertNotNull(pm.channelSettingsIntent("whale_approval"))
    }
}
