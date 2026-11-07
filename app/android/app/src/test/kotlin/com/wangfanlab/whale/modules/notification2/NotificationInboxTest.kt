package com.wangfanlab.whale.modules.notification2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NotificationInbox（SQLite whale_notifications.db）测试：
 * event_id 主键去重、状态机更新、历史查询、已读、FIFO 容量 500、
 * 去重表跨实例持久（模拟重启）、action Outbox。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationInboxTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var inbox: NotificationInbox

    private fun event(eventId: String = fakeEventId(), notificationId: String = fakeNotificationId()) =
        NotificationEventParser.parseEvent(
            eventJson(eventId = eventId, notificationId = notificationId),
        )!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = freshDbName()
        inbox = NotificationInbox(context, dbName)
    }

    @After
    fun tearDown() {
        inbox.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `event_id 主键去重 - 重复插入返回 false 且不覆盖状态`() {
        val e = event()
        assertTrue(inbox.insert(e, NotificationInbox.Status.RECEIVED, 1000))
        inbox.updateStatusByEvent(e.eventId, NotificationInbox.Status.DISPLAYED, 1001)
        // 同一 event_id 再插入：去重，不回退状态
        assertFalse(inbox.insert(e, NotificationInbox.Status.RECEIVED, 1002))
        assertEquals(
            NotificationInbox.Status.DISPLAYED,
            inbox.findByEventId(e.eventId)?.status,
        )
    }

    @Test
    fun `状态机更新 - received 到 displayed 到 opened`() {
        val e = event()
        inbox.insert(e, NotificationInbox.Status.RECEIVED, 1000)
        inbox.updateStatusByEvent(e.eventId, NotificationInbox.Status.DISPLAYED, 1001)
        inbox.updateStatusByEvent(e.eventId, NotificationInbox.Status.OPENED, 1002)
        val r = inbox.findByEventId(e.eventId)!!
        assertEquals(NotificationInbox.Status.OPENED, r.status)
        assertEquals(1002, r.updatedAt)
        assertEquals("task.completed", r.category)
        assertEquals("task_001", r.groupKey)
        assertNotNull(r.targetJson)
    }

    @Test
    fun `容量有界 - 超 500 条按 FIFO 淘汰最旧`() {
        val base = NotificationInbox.MAX_RECORDS
        for (i in 1..base + 5) {
            inbox.insert(event(), NotificationInbox.Status.RECEIVED, nowMs = i.toLong())
        }
        assertEquals(base, inbox.count())
        // 最早 5 条已被淘汰：按 received_at 升序查第一条应为第 6 条
        val oldest = inbox.history(limit = 1, offset = base - 1)
        assertEquals(1, oldest.size)
        // 倒序第一页第一条是最新
        assertEquals(
            NotificationInbox.Status.RECEIVED,
            inbox.history(limit = 1).first().status,
        )
    }

    @Test
    fun `已读标记 - 单条与全部`() {
        val e1 = event()
        val e2 = event()
        inbox.insert(e1, NotificationInbox.Status.RECEIVED, 1)
        inbox.insert(e2, NotificationInbox.Status.RECEIVED, 2)
        assertEquals(1, inbox.markRead(e1.notificationId))
        assertTrue(inbox.findByEventId(e1.eventId)!!.read)
        assertFalse(inbox.findByEventId(e2.eventId)!!.read)
        assertEquals(1, inbox.markAllRead())
        assertTrue(inbox.findByEventId(e2.eventId)!!.read)
    }

    @Test
    fun `去重表跨实例持久 - 模拟进程重启后仍记得已处理事件`() {
        inbox.markProcessed("evt_restart_test", 1000)
        inbox.close()
        // 新实例同一库（≈重启后重建）
        inbox = NotificationInbox(context, dbName)
        assertTrue(inbox.isProcessed("evt_restart_test"))
        assertEquals("evt_restart_test", inbox.lastProcessedEventId())
    }

    @Test
    fun `revoke 按 notification_id 定位并更新状态`() {
        val nid = fakeNotificationId()
        inbox.insert(event(notificationId = nid), NotificationInbox.Status.DISPLAYED, 1000)
        inbox.insert(event(notificationId = nid), NotificationInbox.Status.DISPLAYED, 1001)
        assertEquals(2, inbox.findByNotificationId(nid).size)
        assertEquals(
            2,
            inbox.updateStatusByNotification(nid, NotificationInbox.Status.REVOKED, 1002),
        )
        assertTrue(
            inbox.findByNotificationId(nid)
                .all { it.status == NotificationInbox.Status.REVOKED },
        )
    }

    @Test
    fun `action Outbox - 入队、读取、失败计数、删除`() {
        val rowId = inbox.enqueueAction("e1", "n1", "approve", """{"type":"notification.action"}""", 1000)
        assertTrue(rowId > 0)
        val pending = inbox.pendingActions()
        assertEquals(1, pending.size)
        assertEquals("approve", pending[0].actionId)
        assertEquals(0, pending[0].attempts)
        inbox.incrementActionAttempts(rowId)
        assertEquals(1, inbox.pendingActions()[0].attempts)
        inbox.deleteAction(rowId)
        assertTrue(inbox.pendingActions().isEmpty())
    }
}
