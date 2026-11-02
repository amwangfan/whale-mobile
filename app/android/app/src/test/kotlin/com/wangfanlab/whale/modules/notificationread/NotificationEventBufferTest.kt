package com.wangfanlab.whale.modules.notificationread

import org.junit.Assert.assertEquals
import org.junit.Test

/** SEC-02 通知事件缓冲单测：有界、溢出丢最旧计数、drain 顺序与清空。 */
class NotificationEventBufferTest {

    @Test
    fun `容量内按序缓冲并回放`() {
        val buf = NotificationEventBuffer<Int>(capacity = 3)
        buf.offer(1); buf.offer(2)
        assertEquals(2, buf.size)
        assertEquals(listOf(1, 2), buf.drain())
        assertEquals(0, buf.size)
        assertEquals(0, buf.droppedOverflow)
    }

    @Test
    fun `溢出丢最旧并计数`() {
        val buf = NotificationEventBuffer<Int>(capacity = 2)
        buf.offer(1); buf.offer(2); buf.offer(3)
        assertEquals(listOf(2, 3), buf.drain())
        assertEquals(1, buf.droppedOverflow)
    }

    @Test
    fun `drain 清空后可继续缓冲`() {
        val buf = NotificationEventBuffer<String>(capacity = 2)
        buf.offer("a")
        buf.drain()
        buf.offer("b")
        assertEquals(listOf("b"), buf.drain())
    }

    @Test
    fun `大量溢出计数准确`() {
        val buf = NotificationEventBuffer<Int>(capacity = 1)
        repeat(10) { buf.offer(it) }
        assertEquals(9, buf.droppedOverflow)
        assertEquals(listOf(9), buf.drain())
    }
}
