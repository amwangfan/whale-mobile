package com.wangfanlab.whale.transport.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * experience.event 上行队列（contracts/v2 §9/§16）单元测试。
 * 覆盖：v2 全字段组装、加密往返（DB 无明文）、event_id 幂等、
 * sent/ack 状态机、断线回退、drop_on_pause、毒丸 event_id 防护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventOutboxV2Test {

    private lateinit var context: Context
    private val dbName = "test_outbox_v2.db"
    private lateinit var outbox: EventOutboxV2
    private val deviceId = "ba4bb14c75917cfc0e69fb7a1caf8ba3"

    /** 可逆假加密（测试用）：Base64(反转明文)。 */
    private val fakeCipher = object : QueueCipher {
        override fun encrypt(plain: String): String =
            android.util.Base64.encodeToString(plain.reversed().toByteArray(), android.util.Base64.NO_WRAP)

        override fun decrypt(blob: String): String {
            val bytes = try {
                android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                throw SecurityException("bad blob", e)
            }
            return String(bytes).reversed()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        outbox = EventOutboxV2(context, fakeCipher, deviceIdProvider = { deviceId }, dbName = dbName)
    }

    @After
    fun tearDown() {
        outbox.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `enqueue assembles full v2 payload`() {
        val eventId = outbox.enqueueExperienceEvent(
            kind = "screen.segment_ready",
            occurredAt = 1794052839000L,
            source = "screen_memo",
            summary = "浏览了一个关于 AI 机器人的视频",
            privacyClass = "owner_private",
            evidenceRef = "capture:segment_01J9XK3EM8TQ2ZP0VN6W4RA8II",
            taskId = "task_01J9XK3EM8TQ2ZP0VN6W4RA8BB",
            runId = "run_01J9XK3EM8TQ2ZP0VN6W4RA8AA",
            extras = mapOf("package" to "com.example.video"),
        )
        assertTrue(IdsV2.EVENT_ID.matches(eventId))
        val batch = outbox.pendingBatch()
        assertEquals(1, batch.size)
        val entry = batch[0]
        assertEquals(MsgTypeV2.EXPERIENCE_EVENT, entry.type)
        val payload = EnvelopeCodecV2.payloadFromJson(entry.payloadJson)
        assertEquals(eventId, payload["event_id"])
        assertEquals("screen.segment_ready", payload["kind"])
        assertEquals(1794052839000L, payload["occurred_at"]) // 数字保型
        assertEquals(deviceId, payload["device_id"])
        assertEquals("screen_memo", payload["source"])
        assertEquals("owner_private", payload["privacy_class"])
        assertEquals("2.0", payload["schema_version"])
        assertEquals("task_01J9XK3EM8TQ2ZP0VN6W4RA8BB", payload["task_id"])
        @Suppress("UNCHECKED_CAST")
        val extras = payload["extras"] as Map<String, Any?>
        assertEquals("com.example.video", extras["package"])
    }

    @Test
    fun `optional fields are omitted when null`() {
        outbox.enqueueExperienceEvent(
            kind = "notification.posted", occurredAt = 1L, source = "notification",
            summary = "s", privacyClass = "public",
        )
        val payload = EnvelopeCodecV2.payloadFromJson(outbox.pendingBatch()[0].payloadJson)
        assertFalse(payload.containsKey("evidence_ref"))
        assertFalse(payload.containsKey("task_id"))
        assertFalse(payload.containsKey("run_id"))
        assertFalse(payload.containsKey("extras"))
    }

    @Test
    fun `payload is encrypted at rest - no plaintext in db file`() {
        val secret = "用户私密摘要不应落盘明文"
        outbox.enqueueExperienceEvent(
            kind = "screen.segment_ready", occurredAt = 1L, source = "screen_memo",
            summary = secret, privacyClass = "owner_private",
        )
        // 直接读 DB 原始列：应带密文前缀且不含明文摘要
        val path = context.getDatabasePath(dbName).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT payload_json FROM uplink_queue", null).use { c ->
                assertTrue(c.moveToFirst())
                val stored = c.getString(0)
                assertTrue("应存密文: $stored", stored.startsWith("v1."))
                assertFalse(stored.contains(secret))
            }
        }
        // 出库自动解密还原（加密往返）
        val payload = EnvelopeCodecV2.payloadFromJson(outbox.pendingBatch()[0].payloadJson)
        assertEquals(secret, payload["summary"])
    }

    @Test
    fun `event id dedup - duplicate enqueue is ignored`() {
        val id = IdsV2.newEventId()
        val first = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 1L, source = "user", summary = "一",
            privacyClass = "public", eventId = id,
        )
        val second = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 2L, source = "user", summary = "二",
            privacyClass = "public", eventId = id,
        )
        assertEquals(id, first)
        assertEquals(id, second)
        assertEquals(1, outbox.stats().pending)
        // 保留先入队内容
        val payload = EnvelopeCodecV2.payloadFromJson(outbox.pendingBatch()[0].payloadJson)
        assertEquals("一", payload["summary"])
    }

    @Test
    fun `poison event id is replaced with fresh conforming id`() {
        val id = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 1L, source = "user", summary = "s",
            privacyClass = "public", eventId = "not-conforming",
        )
        assertNotEquals("not-conforming", id)
        assertTrue(IdsV2.EVENT_ID.matches(id))
    }

    @Test
    fun `sent ack lifecycle and reconnect rollback`() {
        val a = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 1L, source = "user", summary = "a", privacyClass = "public",
        )
        val b = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 2L, source = "user", summary = "b", privacyClass = "public",
        )
        assertEquals(2, outbox.stats().pending)
        assertTrue(outbox.markSent(a))
        assertTrue(outbox.markSent(b))
        assertEquals(2, outbox.stats().sent)
        // event.ack 确认 a（含去重命中语义），b 留 SENT
        assertEquals(1, outbox.markAcked(listOf(a)))
        assertEquals(1, outbox.stats().acked)
        assertEquals(1, outbox.stats().sent)
        // 断线重连：SENT 回退 PENDING 重发；ACKED 不再出现
        assertEquals(1, outbox.resetSentToPending())
        val batch = outbox.pendingBatch()
        assertEquals(1, batch.size)
        assertEquals(b, batch[0].eventId)
    }

    @Test
    fun `drop on pause purges only flagged undelivered events`() {
        val private1 = outbox.enqueueExperienceEvent(
            kind = "screen.segment_ready", occurredAt = 1L, source = "screen_memo",
            summary = "私人历史", privacyClass = "owner_private", dropOnPause = true,
        )
        val public1 = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 2L, source = "system",
            summary = "系统事件", privacyClass = "public", dropOnPause = false,
        )
        val ackedFlagged = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 3L, source = "system",
            summary = "已确认", privacyClass = "public", dropOnPause = true,
        )
        outbox.markSent(ackedFlagged)
        outbox.markAcked(listOf(ackedFlagged))
        assertEquals(1, outbox.purgeDropOnPause()) // 仅未送达且标记的被清
        val remaining = outbox.pendingBatch().map { it.eventId }
        assertFalse(remaining.contains(private1))
        assertTrue(remaining.contains(public1))
        assertEquals(1, outbox.stats().dropped)
    }
}
