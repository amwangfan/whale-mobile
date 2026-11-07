package com.wangfanlab.whale.modules.notification2

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NotificationActionReceiver 测试：按钮 → Outbox → notification.action 上行组包、
 * action_submitted 状态机、处理中视觉反馈、上行失败保留 Outbox、
 * 点击正文 → opened 回执 + 深链拉起、划掉 → dismissed 回执。
 *
 * 依赖经 Companion 静态注入；scope 用 Unconfined 使 goAsync 体同步执行便于断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationActionReceiverTest {

    private lateinit var context: Application
    private lateinit var dbName: String
    private lateinit var inbox: NotificationInbox
    private lateinit var facade: FakeFacade
    private lateinit var uplink: FakeUplink
    private lateinit var dedup: NotificationDeduplicator

    private val receiver = NotificationActionReceiver()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = freshDbName()
        inbox = NotificationInbox(context, dbName)
        dedup = NotificationDeduplicator(inbox)
        facade = FakeFacade(context)
        uplink = FakeUplink()
        val channels = NotificationChannelRegistry(context)
        channels.ensureChannels()
        NotificationActionReceiver.wire(
            NotificationActionReceiver.Dependencies(
                inbox = inbox,
                dedup = dedup,
                renderer = NotificationRenderer(context, channels),
                facade = facade,
                uplink = uplink,
                deviceIdProvider = { TEST_DEVICE_ID },
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        )
    }

    @After
    fun tearDown() {
        NotificationActionReceiver.unwire()
        inbox.close()
        context.deleteDatabase(dbName)
    }

    private fun seedDisplayed(eventJsonStr: String): NotificationEvent {
        val e = NotificationEventParser.parseEvent(eventJsonStr)!!
        inbox.insert(e, NotificationInbox.Status.DISPLAYED, 1000)
        return e
    }

    private fun intentOf(action: String, e: NotificationEvent, actionId: String? = null): Intent =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, e.eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, e.notificationId)
            if (actionId != null) putExtra(NotificationActionReceiver.EXTRA_ACTION_ID, actionId)
        }

    // ------------------------------------------------------------  按钮

    @Test
    fun `按钮点击 - 状态 action_submitted、组包上行、Outbox 清空、处理中重渲染`() {
        val e = seedDisplayed(
            eventJson(
                category = "approval.requested",
                actions = """[{"action_id":"approve","label":"批准"},{"action_id":"reject","label":"拒绝"}]""",
            ),
        )
        receiver.onReceive(
            context, intentOf(NotificationActionReceiver.ACTION_BUTTON, e, "approve"),
        )

        // 状态机
        assertEquals(
            NotificationInbox.Status.ACTION_SUBMITTED,
            inbox.findByEventId(e.eventId)?.status,
        )
        // 上行组包：notification.action 完整字段
        assertEquals(1, uplink.sent.size)
        val root = JsonParser.parseString(uplink.sent[0]).asJsonObject
        assertEquals("notification.action", root.get("type").asString)
        assertEquals("uplink", root.get("dir").asString)
        val p = root.getAsJsonObject("payload")
        assertEquals(e.eventId, p.get("event_id").asString)
        assertEquals(e.notificationId, p.get("notification_id").asString)
        assertEquals(TEST_DEVICE_ID, p.get("device_id").asString)
        assertEquals("approve", p.get("action_id").asString)
        assertTrue(p.has("submitted_at"))
        // Outbox 已冲刷
        assertTrue(inbox.pendingActions().isEmpty())
        // 处理中视觉反馈：同系统 id 重渲染
        assertEquals(1, facade.posts.size)
        assertEquals(dedup.systemNotificationIdFor(e), facade.posts[0].id)
    }

    @Test
    fun `按钮点击但上行失败 - 保留 Outbox 并计数，状态仍 action_submitted`() {
        val e = seedDisplayed(eventJson())
        uplink.failAlways = true
        receiver.onReceive(
            context, intentOf(NotificationActionReceiver.ACTION_BUTTON, e, "open"),
        )
        val pending = inbox.pendingActions()
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].attempts)
        assertEquals("open", pending[0].actionId)
        assertEquals(
            NotificationInbox.Status.ACTION_SUBMITTED,
            inbox.findByEventId(e.eventId)?.status,
        )
    }

    // ------------------------------------------------------------  点击 / 划掉

    @Test
    fun `点击正文 - opened 回执并携带深链拉起 MainActivity`() {
        val e = seedDisplayed(eventJson())
        receiver.onReceive(context, intentOf(NotificationActionReceiver.ACTION_OPEN, e))

        assertEquals(
            NotificationInbox.Status.OPENED,
            inbox.findByEventId(e.eventId)?.status,
        )
        assertEquals(listOf("opened"), uplink.statuses())
        assertTrue(inbox.findByEventId(e.eventId)!!.read) // 点击即已读
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals("/task/task_001", started.getStringExtra(NotificationDeepLinkRouter.EXTRA_ROUTE))
        assertEquals(
            "com.wangfanlab.whale.MainActivity",
            started.component?.className,
        )
    }

    @Test
    fun `用户划掉 - dismissed 回执`() {
        val e = seedDisplayed(eventJson())
        receiver.onReceive(context, intentOf(NotificationActionReceiver.ACTION_DISMISSED, e))
        assertEquals(
            NotificationInbox.Status.DISMISSED,
            inbox.findByEventId(e.eventId)?.status,
        )
        assertEquals(listOf("dismissed"), uplink.statuses())
    }

    @Test
    fun `依赖未装配 - 忽略不崩溃`() {
        NotificationActionReceiver.unwire()
        val e = seedDisplayed(eventJson())
        receiver.onReceive(
            context, intentOf(NotificationActionReceiver.ACTION_BUTTON, e, "approve"),
        )
        // 状态不变、不崩溃
        assertEquals(
            NotificationInbox.Status.DISPLAYED,
            inbox.findByEventId(e.eventId)?.status,
        )
    }
}
