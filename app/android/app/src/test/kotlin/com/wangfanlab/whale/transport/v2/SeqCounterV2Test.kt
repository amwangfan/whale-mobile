package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * seq 双向单调（contracts/v2 §4）单元测试。
 * （自 v1 SeqAndIdsTest 迁入：Ids 部分随 v1 信封删除，SeqCounter 部分保留。）
 */
class SeqCounterV2Test {

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
}
