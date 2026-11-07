package com.wangfanlab.whale.modules.notification2

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协议组包与解析测试（contracts/v2 §13 + messages/notification.*.schema.json）。
 * 覆盖：ULID 格式、receipt/action/sync 信封组包、event/revoke 解析、协议上限截断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationProtocolTest {

    // ------------------------------------------------------------  ULID

    @Test
    fun `ULID 26 字符 Crockford Base32，满足 v2 id 正则`() {
        val pattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
        repeat(100) {
            val id = Ulid.newUlid()
            assertTrue("非法 ULID: $id", pattern.matches(id))
        }
    }

    @Test
    fun `ULID 时间前缀单调（同一时间窗内有序）`() {
        val a = Ulid.newUlid(1_794_052_895_000)
        val b = Ulid.newUlid(1_794_052_896_000)
        assertTrue(a.substring(0, 10) <= b.substring(0, 10))
    }

    // ------------------------------------------------------------  组包

    @Test
    fun `receipt 组包 - v2 信封字段与 payload 完整`() {
        val json = NotificationProtocol.buildReceipt(
            eventId = "evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK",
            notificationId = "notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ",
            deviceId = TEST_DEVICE_ID,
            status = "displayed",
            atMs = 1_794_052_896_000,
        )
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("2.0", root.get("v").asString)
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(root.get("id").asString))
        assertEquals("notification.receipt", root.get("type").asString)
        assertEquals("uplink", root.get("dir").asString)
        assertEquals(1_794_052_896_000L, root.get("ts").asLong)
        val p = root.getAsJsonObject("payload")
        assertEquals("evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK", p.get("event_id").asString)
        assertEquals("notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ", p.get("notification_id").asString)
        assertEquals(TEST_DEVICE_ID, p.get("device_id").asString)
        assertEquals("displayed", p.get("status").asString)
        assertEquals(1_794_052_896_000L, p.get("received_at").asLong)
    }

    @Test
    fun `action 组包 - action_id 与 submitted_at`() {
        val json = NotificationProtocol.buildAction(
            eventId = "evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK",
            notificationId = "notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ",
            deviceId = TEST_DEVICE_ID,
            actionId = "approve",
            submittedAt = 1_794_052_900_000,
        )
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("notification.action", root.get("type").asString)
        val p = root.getAsJsonObject("payload")
        assertEquals("approve", p.get("action_id").asString)
        assertEquals(1_794_052_900_000L, p.get("submitted_at").asLong)
    }

    @Test
    fun `sync 组包 - last_event_id 缺省时不出现`() {
        val withLast = JsonParser.parseString(
            NotificationProtocol.buildSync(TEST_DEVICE_ID, "evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK", 1000),
        ).asJsonObject.getAsJsonObject("payload")
        assertEquals("evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK", withLast.get("last_event_id").asString)

        val without = JsonParser.parseString(
            NotificationProtocol.buildSync(TEST_DEVICE_ID, null, 1000),
        ).asJsonObject.getAsJsonObject("payload")
        assertNull(without.get("last_event_id"))
    }

    // ------------------------------------------------------------  解析

    @Test
    fun `解析完整信封（contracts v2 黄金示例字段值）`() {
        val envelope = """
        {
          "v": "2.0",
          "id": "01J9XK3EM8TQ2ZP0VN6W4RA722",
          "type": "notification.event",
          "ts": 1794052895000,
          "seq": 1,
          "dir": "downlink",
          "payload": {
            "event_id": "evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK",
            "notification_id": "notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ",
            "device_id": "ba4bb14c75917cfc0e69fb7a1caf8ba3",
            "category": "task.completed",
            "title": "任务已完成",
            "body": "Root 手机已完成应用检查。",
            "priority": "normal",
            "created_at": 1794052895000,
            "expires_at": 1794139200000,
            "target": {"type": "task", "id": "task_01J9XK3EM8TQ2ZP0VN6W4RA8BB"},
            "actions": [{"action_id": "open", "label": "查看"}],
            "group_key": "task_01J9XK3EM8TQ2ZP0VN6W4RA8BB",
            "collapse_key": "task_01J9XK3EM8TQ2ZP0VN6W4RA8BB"
          }
        }
        """.trimIndent()
        val e = NotificationEventParser.parseEvent(envelope)
        assertNotNull(e)
        e!!
        assertEquals("evt_01J9XK3EM8TQ2ZP0VN6W4RA8KK", e.eventId)
        assertEquals("task.completed", e.category)
        assertEquals("任务已完成", e.title)
        assertEquals("task", e.target?.type)
        assertEquals("task_01J9XK3EM8TQ2ZP0VN6W4RA8BB", e.target?.id)
        assertEquals(1, e.actions.size)
        assertEquals("open", e.actions[0].actionId)
        assertEquals("task_01J9XK3EM8TQ2ZP0VN6W4RA8BB", e.collapseKey)
    }

    @Test
    fun `title 超 200、body 超 2000 按协议上限截断不拒绝`() {
        val e = NotificationEventParser.parseEvent(
            eventJson(title = "t".repeat(250), body = "x".repeat(2100)),
        )!!
        assertEquals(200, e.title.length)
        assertEquals(2000, e.body.length)
    }

    @Test
    fun `actions 超 4 个截断（协议 maxItems=4）`() {
        val actions = (1..6).joinToString(",") { """{"action_id":"a$it","label":"L$it"}""" }
        val e = NotificationEventParser.parseEvent(eventJson(actions = "[$actions]"))!!
        assertEquals(4, e.actions.size)
    }

    @Test
    fun `缺关键字段或非法 JSON 返回 null（不产生回执）`() {
        assertNull(NotificationEventParser.parseEvent("not json"))
        assertNull(NotificationEventParser.parseEvent("{}"))
        assertNull(NotificationEventParser.parseEvent("""{"event_id":"e1"}"""))
        assertNull(NotificationEventParser.parseEvent(eventJson(title = ""))) // 空 title 拒绝
    }

    @Test
    fun `revoke 解析`() {
        val r = NotificationEventParser.parseRevoke(
            revokeJson(
                eventId = "evt_01J9XK3EM8TQ2ZP0VN6W4RA8MM",
                notificationId = "notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ",
                reason = "任务结果已更新",
            ),
        )!!
        assertEquals("evt_01J9XK3EM8TQ2ZP0VN6W4RA8MM", r.eventId)
        assertEquals("notif_01J9XK3EM8TQ2ZP0VN6W4RA8JJ", r.notificationId)
        assertEquals("任务结果已更新", r.reason)
    }
}
