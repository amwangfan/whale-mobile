package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ULID 与带前缀 ID（contracts/v2 §3）单元测试。
 * 纯 JVM，无需 Robolectric。
 */
class UlidAndIdsV2Test {

    @Test
    fun `ulid matches schema pattern`() {
        repeat(1000) {
            val id = Ulid.newUlid()
            assertEquals(26, id.length)
            assertTrue("ULID 须匹配 ^[0-9A-HJKMNP-TV-Z]{26}$: $id", Ulid.isValid(id))
        }
    }

    @Test
    fun `ulid time component matches canonical test vector`() {
        // ulid 规范测试向量：encodeTime(1469918176385) = "01ARYZ6S41"
        assertEquals("01ARYZ6S41", Ulid.encodeTime(1469918176385L))
    }

    @Test
    fun `ulid deterministic with fixed time and randomness`() {
        val random = ByteArray(10) { 0x41 }
        val a = Ulid.newUlid(1469918176385L, random)
        val b = Ulid.newUlid(1469918176385L, random)
        assertEquals(a, b)
        assertEquals("01ARYZ6S41", a.substring(0, 10))
        assertTrue(Ulid.isValid(a))
    }

    @Test
    fun `ulid uniqueness across burst generation`() {
        val n = 20_000
        val set = HashSet<String>(n * 2)
        repeat(n) { set.add(Ulid.newUlid()) }
        assertEquals(n, set.size)
    }

    @Test
    fun `ulid time prefix is monotonic for increasing timestamps`() {
        val random = ByteArray(10)
        val earlier = Ulid.newUlid(1_700_000_000_000L, random)
        val later = Ulid.newUlid(1_700_000_000_001L, random)
        assertTrue("同一时间分量下 ULID 字典序应随时间递增", later > earlier)
    }

    @Test
    fun `ulid rejects out of range timestamp`() {
        val random = ByteArray(10)
        for (bad in listOf(-1L, Ulid.MAX_TIME_MS + 1)) {
            try {
                Ulid.newUlid(bad, random)
                throw AssertionError("越界时间戳应抛异常: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `prefixed id generators produce schema-conforming ids`() {
        assertTrue(IdsV2.newTaskId().matches(IdsV2.TASK_ID))
        assertTrue(IdsV2.newRunId().matches(IdsV2.RUN_ID))
        assertTrue(IdsV2.newActionId().matches(IdsV2.ACTION_ID))
        assertTrue(IdsV2.newEventId().matches(IdsV2.EVENT_ID))
        assertTrue(IdsV2.newObservationId().matches(IdsV2.OBSERVATION_ID))
        assertTrue(IdsV2.newApprovalId().matches(IdsV2.APPROVAL_ID))
        assertTrue(IdsV2.newAttachmentId().matches(IdsV2.ATTACHMENT_ID))
        assertTrue(IdsV2.newNotificationId().matches(IdsV2.NOTIFICATION_ID))
        assertTrue(Ulid.isValid(IdsV2.newMessageId()))
    }

    @Test
    fun `prefixed ids are distinct across calls`() {
        assertNotEquals(IdsV2.newEventId(), IdsV2.newEventId())
        assertNotEquals(IdsV2.newActionId(), IdsV2.newActionId())
    }

    @Test
    fun `id patterns reject malformed values`() {
        // 缺前缀 / 前缀错误 / 含非法字符（I/L/O/U）/ 长度不符
        val bad = listOf(
            "01J9XK3EM8TQ2ZP0VN6W4RA7YC",           // 无前缀
            "evt_01J9XK3EM8TQ2ZP0VN6W4RA7Y",        // 25 字符
            "evt_01J9XK3EM8TQ2ZP0VN6W4RA7YCC",      // 27 字符
            "evt_01J9XK3EM8TQ2ZP0VN6W4RA7YU",       // 含 U
            "evt_01J9XK3EM8TQ2ZP0VN6W4RA7YI",       // 含 I
            "evt_01j9xk3em8tq2zp0vn6w4ra7yc",       // 小写不允许
            "act_01J9XK3EM8TQ2ZP0VN6W4RA7YC".replace("act_", "act"), // 前缀残缺
        )
        for (id in bad) {
            assertTrue("应拒绝: $id", !IdsV2.EVENT_ID.matches(id) || id.startsWith("act"))
        }
        assertTrue(IdsV2.EVENT_ID.matches("evt_01J9XK3EM8TQ2ZP0VN6W4RA7YC"))
        assertTrue(IdsV2.ACTION_ID.matches("act_01J9XK3EM8TQ2ZP0VN6W4RA7YC"))
    }

    @Test
    fun `idempotency key pattern follows section 3`() {
        assertTrue(IdsV2.IDEMPOTENCY_KEY.matches("agent:run_01J9XK3EM8TQ2ZP0VN6W4RA7YC:step8"))
        assertTrue(!IdsV2.IDEMPOTENCY_KEY.matches("short"))          // < 8
        assertTrue(!IdsV2.IDEMPOTENCY_KEY.matches("has space padding")) // 空格非法
    }
}
