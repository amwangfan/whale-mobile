package com.wangfanlab.whale.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信封编解码（contracts/v1 §2）单元测试。
 * 黄金示例对照：contracts/v1/examples 目录下的示例 JSON。
 */
class EnvelopeTest {

    private fun samplePayload(): Map<String, Any?> = linkedMapOf(
        "event_id" to "01J000000000000000000000001",
        "kind" to "screen.memo.created",
        "occurred_at" to 1730000000001L,
        "privacy_class" to "owner_private",
        "summary" to "脱敏摘要",
        "schema_version" to "1.0",
    )

    @Test
    fun `encode decode roundtrip preserves all fields`() {
        val env = Envelope.uplink("sensor.event", 412, samplePayload(), ts = 1730000000002L)
        val json = env.toJson()
        val decoded = EnvelopeCodec.decode(json)
        assertEquals("1.0", decoded.v)
        assertEquals(env.id, decoded.id)
        assertEquals("sensor.event", decoded.type)
        assertEquals(1730000000002L, decoded.ts)
        assertEquals(412L, decoded.seq)
        assertEquals("uplink", decoded.dir)
        assertEquals(samplePayload(), decoded.payload)
    }

    @Test
    fun `encode emits fields in protocol order`() {
        val json = Envelope.uplink("device.heartbeat", 1, emptyMap(), ts = 1L).toJson()
        val order = listOf("v", "id", "type", "ts", "seq", "dir", "payload")
            .map { json.indexOf("\"$it\"") }
        assertTrue("字段顺序应与 §2 一致: $json", order.zipWithNext().all { (a, b) -> a in 0 until b })
    }

    @Test
    fun `decode matches golden example structure`() {
        // 取自 contracts/v1/examples/sensor.event.json 的关键字段
        val json = """
        {
          "v": "1.0",
          "id": "01J000000000000000000000002",
          "type": "sensor.event",
          "ts": 1730000000100,
          "seq": 41,
          "dir": "uplink",
          "payload": {
            "event_id": "01J0000000000000000000000AA",
            "kind": "screen.memo.created",
            "occurred_at": 1730000000001,
            "privacy_class": "owner_private",
            "summary": "用户在阅读《活着》第3章",
            "schema_version": "1.0"
          }
        }
        """.trimIndent()
        val env = EnvelopeCodec.decode(json)
        assertEquals(41L, env.seq)
        assertEquals(1730000000001L, env.payload["occurred_at"]) // Long，不是 Double
        assertEquals("screen.memo.created", env.payload["kind"])
    }

    @Test
    fun `integer fields survive payload json roundtrip without scientific notation`() {
        val payload = mapOf<String, Any?>("occurred_at" to 1730000000001L, "expires_at" to 4102444800000L)
        val stored = EnvelopeCodec.payloadToJson(payload)
        val restored = EnvelopeCodec.payloadFromJson(stored)
        assertTrue(restored["occurred_at"] is Long)
        val out = Envelope.uplink("sensor.event", 1, restored, ts = 1730000000100L).toJson()
        assertTrue(out.contains("1730000000001"))
        assertFalse(out.contains("E12"))
    }

    @Test
    fun `decode rejects malformed frames`() {
        assertThrows(EnvelopeException::class.java) { EnvelopeCodec.decode("not json") }
        assertThrows(EnvelopeException::class.java) { EnvelopeCodec.decode("[]") }
        // 缺 payload
        assertThrows(EnvelopeException::class.java) {
            EnvelopeCodec.decode(
                """{"v":"1.0","id":"01J000000000000000000000001","type":"error",""" +
                    """"ts":1,"seq":1,"dir":"uplink"}""",
            )
        }
        // dir 非法
        assertThrows(EnvelopeException::class.java) {
            EnvelopeCodec.decode(
                """{"v":"1.0","id":"01J000000000000000000000001","type":"error","ts":1,""" +
                    """"seq":1,"dir":"sideways","payload":{}}""",
            )
        }
        // id 非法（过短）
        assertThrows(EnvelopeException::class.java) {
            EnvelopeCodec.decode(
                """{"v":"1.0","id":"short","type":"error","ts":1,"seq":1,"dir":"uplink","payload":{}}""",
            )
        }
    }

    @Test
    fun `frame size limit is 256 KiB`() {
        // 略低于上限：通过
        val small = "x".repeat(MAX_FRAME_BYTES - 8)
        EnvelopeCodec.checkFrameSize(small)
        // 超上限：拒绝
        assertThrows(EnvelopeException::class.java) {
            EnvelopeCodec.checkFrameSize("x".repeat(MAX_FRAME_BYTES + 1))
        }
    }

    @Test
    fun `majorOf parses protocol major`() {
        assertEquals(1, EnvelopeCodec.majorOf("1.0"))
        assertEquals(12, EnvelopeCodec.majorOf("12.3"))
        assertEquals(null, EnvelopeCodec.majorOf("x.y"))
    }
}
