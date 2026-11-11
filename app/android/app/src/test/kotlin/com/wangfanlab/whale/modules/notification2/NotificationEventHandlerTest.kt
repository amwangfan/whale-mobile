package com.wangfanlab.whale.modules.notification2

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NotificationEventHandler 全链测试（§13 状态机 + 回执诚实性）：
 * 过期/去重/权限缺失/渠道关闭/展示成功/revoke/Outbox 冲刷/sync 组包。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationEventHandlerTest {

    private lateinit var context: Application
    private lateinit var dbName: String
    private lateinit var inbox: NotificationInbox
    private lateinit var dedup: NotificationDeduplicator
    private lateinit var channels: NotificationChannelRegistry
    private lateinit var facade: FakeFacade
    private lateinit var uplink: FakeUplink
    private lateinit var permissions: NotificationPermissionManager
    private var now = 1_794_052_895_000L

    private lateinit var handler: NotificationEventHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = freshDbName()
        inbox = NotificationInbox(context, dbName)
        dedup = NotificationDeduplicator(inbox)
        channels = NotificationChannelRegistry(context)
        facade = FakeFacade(context)
        uplink = FakeUplink()
        permissions = NotificationPermissionManager(context)
        // sdk33：授予运行时通知权限（拒绝场景在用例内显式 deny）
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        handler = NotificationEventHandler(
            context = context,
            inbox = inbox,
            dedup = dedup,
            renderer = NotificationRenderer(context, channels),
            facade = facade,
            channels = channels,
            permissions = permissions,
            uplink = uplink,
            deviceIdProvider = { TEST_DEVICE_ID },
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        inbox.close()
        context.deleteDatabase(dbName)
    }

    // ------------------------------------------------------------  正常展示

    @Test
    fun `展示成功 - 落库、提交系统、回执 displayed`() = runBlocking {
        val json = eventJson()
        val result = handler.handleEvent(json)
        assertTrue(result is NotificationEventHandler.HandleResult.Displayed)
        assertEquals(1, facade.posts.size)
        val e = NotificationEventParser.parseEvent(json)!!
        assertEquals(
            NotificationInbox.Status.DISPLAYED,
            inbox.findByEventId(e.eventId)?.status,
        )
        assertEquals(listOf("displayed"), uplink.statuses())
    }

    @Test
    fun `过期事件 - 不弹出，落库 expired，回执 expired`() = runBlocking {
        val json = eventJson(expiresAt = now - 1)
        val result = handler.handleEvent(json)
        assertTrue(result is NotificationEventHandler.HandleResult.Expired)
        assertEquals(0, facade.posts.size)
        val e = NotificationEventParser.parseEvent(json)!!
        assertEquals(
            NotificationInbox.Status.EXPIRED,
            inbox.findByEventId(e.eventId)?.status,
        )
        assertEquals(listOf("expired"), uplink.statuses())
    }

    // ------------------------------------------------------------  去重

    @Test
    fun `重复 event_id - 不重复弹出，按当前状态重发回执让 core 收敛`() = runBlocking {
        val json = eventJson()
        handler.handleEvent(json)
        val result = handler.handleEvent(json) // WSS/FCM 双通道或补传重发
        assertTrue(result is NotificationEventHandler.HandleResult.Duplicate)
        assertEquals("只展示一次", 1, facade.posts.size)
        assertEquals(listOf("displayed", "displayed"), uplink.statuses())
    }

    @Test
    fun `重复 event_id - failed 终态同样按落库重放，且不再提交系统`() = runBlocking {
        // 终态回执（failed）丢失后 core 补传：重复事件返回 Duplicate，
        // 按持久 Inbox 的 failed 终态重放回执（收敛），posts 保持 0（从未弹出）。
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val json = eventJson()
        val first = handler.handleEvent(json)
        assertTrue(first is NotificationEventHandler.HandleResult.Failed)
        val dup = handler.handleEvent(json)
        assertTrue(dup is NotificationEventHandler.HandleResult.Duplicate)
        assertEquals("failed 事件任何一次都不得提交系统", 0, facade.posts.size)
        assertEquals(listOf("failed", "failed"), uplink.statuses())
        val e = NotificationEventParser.parseEvent(json)!!
        assertEquals(
            NotificationInbox.Status.FAILED,
            inbox.findByEventId(e.eventId)?.status,
        )
    }

    @Test
    fun `collapse_key 合并 - 同键两条事件覆盖同一系统通知 id`() = runBlocking {
        handler.handleEvent(eventJson(eventId = fakeEventId(), collapseKey = "ck"))
        handler.handleEvent(eventJson(eventId = fakeEventId(), collapseKey = "ck"))
        assertEquals(2, facade.posts.size)
        assertEquals(facade.posts[0].id, facade.posts[1].id) // 同 id 覆盖 → 不连弹
    }

    // ------------------------------------------------------------  诚实性

    @Test
    fun `权限缺失 - 回执 failed，不伪报 displayed，不提交系统`() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val result = handler.handleEvent(eventJson())
        assertTrue(result is NotificationEventHandler.HandleResult.Failed)
        assertEquals(
            "permission_missing",
            (result as NotificationEventHandler.HandleResult.Failed).reason,
        )
        assertEquals(0, facade.posts.size)
        assertEquals(listOf("failed"), uplink.statuses())
    }

    @Test
    fun `渠道被用户关闭 - 回执 failed（channel_disabled）`() = runBlocking {
        // 用户关闭任务渠道（等价系统态：渠道被删除/IMPORTANCE_NONE）
        context.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(NotificationChannelRegistry.CHANNEL_TASK)
        val result = handler.handleEvent(eventJson(category = "task.completed"))
        assertTrue(result is NotificationEventHandler.HandleResult.Failed)
        assertEquals(
            "channel_disabled",
            (result as NotificationEventHandler.HandleResult.Failed).reason,
        )
        assertEquals(0, facade.posts.size)
        assertEquals(listOf("failed"), uplink.statuses())
    }

    @Test
    fun `device_id 不匹配 - 忽略且无任何回执`() = runBlocking {
        val result = handler.handleEvent(eventJson(deviceId = "0".repeat(32)))
        assertTrue(result is NotificationEventHandler.HandleResult.DeviceMismatch)
        assertEquals(0, facade.posts.size)
        assertTrue(uplink.sent.isEmpty())
    }

    // ------------------------------------------------------------  revoke

    @Test
    fun `revoke - 撤下通知、Inbox 置 revoked、回执 dismissed`() = runBlocking {
        val event = eventJson()
        handler.handleEvent(event)
        val e = NotificationEventParser.parseEvent(event)!!
        val result = handler.handleRevoke(revokeJson(notificationId = e.notificationId))
        assertTrue(result is NotificationEventHandler.HandleResult.Revoked)
        assertEquals(1, facade.cancels.size)
        assertEquals(
            NotificationInbox.Status.REVOKED,
            inbox.findByEventId(e.eventId)?.status,
        )
        assertEquals(listOf("displayed", "dismissed"), uplink.statuses())
    }

    @Test
    fun `revoke 重放幂等 - 同一撤回指令第二次不再产生回执`() = runBlocking {
        val event = eventJson()
        handler.handleEvent(event)
        val e = NotificationEventParser.parseEvent(event)!!
        val revoke = revokeJson(notificationId = e.notificationId)
        handler.handleRevoke(revoke)
        val again = handler.handleRevoke(revoke)
        assertTrue(again is NotificationEventHandler.HandleResult.Duplicate)
        assertEquals(listOf("displayed", "dismissed"), uplink.statuses())
    }

    @Test
    fun `revoke 本机无记录 - no-op 不回执`() = runBlocking {
        val result = handler.handleRevoke(revokeJson(notificationId = fakeNotificationId()))
        assertTrue(result is NotificationEventHandler.HandleResult.Revoked)
        assertEquals(false, (result as NotificationEventHandler.HandleResult.Revoked).found)
        assertTrue(uplink.sent.isEmpty())
    }

    // ------------------------------------------------------------  outbox / sync

    @Test
    fun `flushActionOutbox - 成功清空，失败保序留队`() = runBlocking {
        inbox.enqueueAction("e1", "n1", "approve", """{"k":1}""", 1)
        inbox.enqueueAction("e2", "n2", "reject", """{"k":2}""", 2)
        uplink.failAlways = true
        assertEquals(0, handler.flushActionOutbox())
        assertEquals(2, inbox.pendingActions().size)
        uplink.failAlways = false
        assertEquals(2, handler.flushActionOutbox())
        assertTrue(inbox.pendingActions().isEmpty())
        assertEquals("""{"k":1}""", uplink.sent[0]) // 保序
    }

    @Test
    fun `buildSyncMessage - 携带最大已处理 event_id`() = runBlocking {
        handler.handleEvent(eventJson())
        val sync = com.google.gson.JsonParser.parseString(handler.buildSyncMessage())
            .asJsonObject
        assertEquals("notification.sync", sync.get("type").asString)
        val payload = sync.getAsJsonObject("payload")
        assertTrue(payload.get("last_event_id").asString.startsWith("evt_"))
        assertEquals(TEST_DEVICE_ID, payload.get("device_id").asString)
    }
}
