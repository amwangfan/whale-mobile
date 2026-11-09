package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 信封编解码（contracts/v2 §2）单元测试。
 * 黄金示例对照：contracts/v2/examples 目录下的示例 JSON。
 */
class EnvelopeCodecV2Test {
    @Test fun integerFieldsRejectFractionalAndOverflowValues() {
        val raw = EnvelopeV2.uplink(MsgTypeV2.DEVICE_HEARTBEAT, 1, emptyMap(), ts = 1L).toJson()
        for (number in listOf("1.9", "9223372036854775808", "1e1000")) {
            assertThrowsV2 { EnvelopeCodecV2.decode(raw.replace("\"seq\":1", "\"seq\":$number")) }
        }
        assertEquals(1L, EnvelopeCodecV2.decode(raw.replace("\"seq\":1", "\"seq\":1.0")).seq)
    }

    @Test fun payloadLongNeverTruncatesOrClamps() {
        assertEquals(Long.MAX_VALUE, payloadLongV2(mapOf("n" to Long.MAX_VALUE), "n"))
        assertEquals(1000L, payloadLongV2(mapOf("n" to 1000.0), "n"))
        for (number in listOf(1.9, Double.NaN, Double.POSITIVE_INFINITY, 9.223372036854776E18)) {
            assertEquals(null, payloadLongV2(mapOf("n" to number), "n"))
        }
        assertEquals(null, payloadLongV2(mapOf("n" to "1"), "n"))
    }

    @Test fun nonstandardJsonAndNonfinitePayloadNumbersAreRejected() {
        for (raw in listOf("{n:1}", "{'n':1}", "{\"n\":NaN}", "{\"n\":Infinity}", "{\"n\":1e1000}", "{}{}")) {
            assertThrowsV2 { EnvelopeCodecV2.payloadFromJson(raw) }
        }
    }

    @Test fun directEncodeAndDecodeApplyFrameBudget() {
        val raw = EnvelopeV2.uplink(MsgTypeV2.DEVICE_HEARTBEAT, 1, emptyMap(), ts = 1L).toJson()
        assertThrowsV2 { EnvelopeCodecV2.decode(raw + " ".repeat(MAX_FRAME_BYTES_V2)) }
        assertThrowsV2 { EnvelopeV2.uplink(MsgTypeV2.DEVICE_HEARTBEAT, 1,
            mapOf("text" to "鲸".repeat(MAX_FRAME_BYTES_V2 / 3)), ts = 1L).toJson() }
    }

    private fun samplePayload(): Map<String, Any?> = linkedMapOf(
        "event_id" to "evt_01J9XK3EM8TQ2ZP0VN6W4RA8EE",
        "kind" to "screen.segment_ready",
        "occurred_at" to 1794052839000L,
        "device_id" to "ba4bb14c75917cfc0e69fb7a1caf8ba3",
        "source" to "screen_memo",
        "summary" to "浏览了一个关于 AI 机器人的视频",
        "privacy_class" to "owner_private",
        "schema_version" to "2.0",
    )

    @Test
    fun `encode decode roundtrip preserves all fields`() {
        val env = EnvelopeV2.uplink(MsgTypeV2.EXPERIENCE_EVENT, 412, samplePayload(), ts = 1794052840000L)
        val json = env.toJson()
        val decoded = EnvelopeCodecV2.decode(json)
        assertEquals("2.0", decoded.v)
        assertEquals(env.id, decoded.id)
        assertTrue(Ulid.isValid(decoded.id))
        assertEquals(MsgTypeV2.EXPERIENCE_EVENT, decoded.type)
        assertEquals(1794052840000L, decoded.ts)
        assertEquals(412L, decoded.seq)
        assertEquals(DIR_UPLINK, decoded.dir)
        assertEquals(samplePayload(), decoded.payload)
    }

    @Test
    fun `encode emits fields in protocol order`() {
        val json = EnvelopeV2.uplink(MsgTypeV2.DEVICE_HEARTBEAT, 1, emptyMap(), ts = 1L).toJson()
        val order = listOf("v", "id", "type", "ts", "seq", "dir", "payload")
            .map { json.indexOf("\"$it\"") }
        assertTrue("字段顺序应与 §2 一致: $json", order.zipWithNext().all { (a, b) -> a in 0 until b })
    }

    @Test
    fun `decode matches golden experience event example`() {
        // 逐字取自 contracts/v2/examples/experience.event.json
        val json = """
        {
          "v": "2.0",
          "id": "01J9XK3EM8TQ2ZP0VN6W4RA705",
          "type": "experience.event",
          "ts": 1794052840000,
          "seq": 1,
          "dir": "uplink",
          "payload": {
            "event_id": "evt_01J9XK3EM8TQ2ZP0VN6W4RA8EE",
            "kind": "screen.segment_ready",
            "occurred_at": 1794052839000,
            "device_id": "ba4bb14c75917cfc0e69fb7a1caf8ba3",
            "source": "screen_memo",
            "summary": "浏览了一个关于 AI 机器人的视频",
            "privacy_class": "owner_private",
            "evidence_ref": "capture:segment_01J9XK3EM8TQ2ZP0VN6W4RA8II",
            "schema_version": "2.0",
            "extras": {"package": "com.example.video"}
          }
        }
        """.trimIndent()
        val env = EnvelopeCodecV2.decode(json)
        assertEquals(1L, env.seq)
        assertEquals(1794052839000L, env.payload["occurred_at"]) // Long，不是 Double
        assertEquals("screen_memo", env.payload["source"])
        @Suppress("UNCHECKED_CAST")
        val extras = env.payload["extras"] as Map<String, Any?>
        assertEquals("com.example.video", extras["package"])
    }

    @Test
    fun `integer fields survive payload json roundtrip without scientific notation`() {
        val payload = mapOf<String, Any?>(
            "occurred_at" to 1794052839000L,
            "expires_at" to 4102444800000L,
        )
        val stored = EnvelopeCodecV2.payloadToJson(payload)
        val restored = EnvelopeCodecV2.payloadFromJson(stored)
        assertTrue(restored["occurred_at"] is Long)
        val out = EnvelopeV2.uplink(MsgTypeV2.EXPERIENCE_EVENT, 1, restored, ts = 1794052840000L).toJson()
        assertTrue(out.contains("1794052839000"))
        assertFalse(out.contains("E12")) // 不允许科学计数法
    }

    @Test
    fun `decode rejects non ulid message id`() {
        val json = """
        {"v":"2.0","id":"not-a-ulid","type":"device.heartbeat","ts":1,"seq":1,"dir":"uplink","payload":{}}
        """.trimIndent()
        assertThrowsV2 { EnvelopeCodecV2.decode(json) }
    }

    @Test
    fun `decode rejects bad version shape`() {
        val json = """
        {"v":"v2","id":"01J9XK3EM8TQ2ZP0VN6W4RA7YC","type":"device.heartbeat","ts":1,"seq":1,"dir":"uplink","payload":{}}
        """.trimIndent()
        assertThrowsV2 { EnvelopeCodecV2.decode(json) }
    }

    @Test
    fun `decode rejects out of range ts and seq`() {
        val tooBigTs = """
        {"v":"2.0","id":"01J9XK3EM8TQ2ZP0VN6W4RA7YC","type":"device.heartbeat","ts":4102444800001,"seq":1,"dir":"uplink","payload":{}}
        """.trimIndent()
        assertThrowsV2 { EnvelopeCodecV2.decode(tooBigTs) }
        val negativeSeq = """
        {"v":"2.0","id":"01J9XK3EM8TQ2ZP0VN6W4RA7YC","type":"device.heartbeat","ts":1,"seq":-1,"dir":"uplink","payload":{}}
        """.trimIndent()
        assertThrowsV2 { EnvelopeCodecV2.decode(negativeSeq) }
    }

    @Test
    fun `decode tolerates unknown message type for forward compatibility`() {
        // §4：未知 type 允许通过信封校验（上层静默忽略）。
        val json = """
        {"v":"2.0","id":"01J9XK3EM8TQ2ZP0VN6W4RA7YC","type":"future.message","ts":1,"seq":1,"dir":"downlink","payload":{}}
        """.trimIndent()
        assertEquals("future.message", EnvelopeCodecV2.decode(json).type)
    }

    @Test
    fun `version major gate distinguishes supported and foreign major`() {
        assertTrue(EnvelopeCodecV2.isSupportedVersion("2.0"))
        assertTrue(EnvelopeCodecV2.isSupportedVersion("2.15"))
        assertFalse(EnvelopeCodecV2.isSupportedVersion("1.0"))
        assertFalse(EnvelopeCodecV2.isSupportedVersion("3.0"))
        assertEquals(2, EnvelopeCodecV2.majorOf("2.0"))
        assertEquals(3, EnvelopeCodecV2.majorOf("3.1"))
    }

    @Test
    fun `frame size check enforces 256KiB budget`() {
        val big = "x".repeat(MAX_FRAME_BYTES_V2 + 1)
        assertThrowsV2 { EnvelopeCodecV2.checkFrameSize(big) }
        EnvelopeCodecV2.checkFrameSize("x".repeat(1024)) // 不抛
    }

    @Test
    fun `capability and error code tables match protocol section 5 and 15`() {
        // §5.1 共 32 项；与文档表格逐项核对（变更协议时本测试须同步失败）。
        assertEquals(32, CapabilitiesV2.ALL.size)
        assertTrue(CapabilitiesV2.ALL.contains("device.shell"))
        assertTrue(CapabilitiesV2.ALL.contains("device.status"))
        assertTrue(CapabilitiesV2.ALL.contains("glasses.card.dismiss"))
        // §5.3：action 子集 = 全部除 device.output.read（31 项）
        assertEquals(31, CapabilitiesV2.ACTION_SUBSET.size)
        assertFalse(CapabilitiesV2.ACTION_SUBSET.contains("device.output.read"))
        // §5.3：sensor.query 子集恰好 7 项
        assertEquals(7, CapabilitiesV2.SENSOR_QUERY_SUBSET.size)
        assertTrue(CapabilitiesV2.SENSOR_QUERY_SUBSET.contains("device.output.read"))
        // §15：错误码 19 值
        assertEquals(19, ErrorCodeV2.ALL.size)
        assertTrue(ErrorCodeV2.ALL.contains("estop_active"))
    }

    @Test
    fun `receipt status machine has one non terminal and five terminals`() {
        assertEquals(6, ReceiptStatus.entries.size)
        assertFalse(ReceiptStatus.RECEIVED.terminal)
        assertEquals(
            setOf("succeeded", "failed", "timeout", "cancelled", "unknown"),
            ReceiptStatus.entries.filter { it.terminal }.map { it.wire }.toSet(),
        )
        assertEquals(ReceiptStatus.SUCCEEDED, ReceiptStatus.of("succeeded"))
        assertEquals(null, ReceiptStatus.of("executed")) // v1 状态在 v2 不存在
    }

    private fun assertThrowsV2(block: () -> Unit) {
        try {
            block()
            throw AssertionError("应抛 EnvelopeExceptionV2")
        } catch (_: EnvelopeExceptionV2) {
        }
    }
}
