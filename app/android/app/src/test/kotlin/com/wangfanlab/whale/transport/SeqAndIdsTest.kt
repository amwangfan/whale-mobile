package com.wangfanlab.whale.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seq 双向单调（§2）与幂等键生成（§6）单元测试。 */
class SeqAndIdsTest {

    @Test
    fun `uplink seq increments monotonically from 1 per connection`() {
        val seq = SeqCounter()
        assertEquals(0L, seq.current())
        assertEquals(1L, seq.next())
        assertEquals(2L, seq.next())
        assertEquals(3L, seq.next())
        assertEquals(3L, seq.current())
    }

    @Test
    fun `downlink observer accepts in-order and flags gaps and duplicates`() {
        val down = SeqCounter()
        assertTrue(down.observeIncoming(1)) // 首帧
        assertTrue(down.observeIncoming(2))
        assertFalse(down.observeIncoming(2)) // 重复
        assertFalse(down.observeIncoming(5)) // 跳变（缺 3、4）
        assertTrue(down.observeIncoming(6))  // 接续新基线
    }

    @Test
    fun `message id format matches schema pattern`() {
        val pattern = Regex("^[0-9A-Za-z-]{16,64}$")
        repeat(1000) {
            val id = Ids.newId()
            assertTrue("id=$id 应匹配 schema 模式", pattern.matches(id))
            assertEquals(32, id.length)
        }
    }

    @Test
    fun `event id is unique enough for idempotency key`() {
        val ids = (1..10_000).map { Ids.newEventId() }.toSet()
        assertEquals(10_000, ids.size)
    }

    @Test
    fun `isValidId accepts gateway-style ids`() {
        assertTrue(Ids.isValidId("01J000000000000000000000001")) // ULID 风格
        assertTrue(Ids.isValidId("0".repeat(32)))                  // uuid4().hex 风格
        assertFalse(Ids.isValidId("too_short"))
        assertFalse(Ids.isValidId("非法字符!!" + "0".repeat(16)))
    }
}
