package com.wangfanlab.whale.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BG-01 运行时纯逻辑单测：
 * - [SensorQueryDispatch]：sensor.query 处理器链（暂停/委托/无处理器 fail-closed）。
 * - [UiEventHub]：UI 监听扇出、状态补发、异常隔离。
 */
class SensorQueryDispatchTest {

    @Test
    fun `全局暂停优先于 Dart 委托`() {
        assertEquals(
            SensorQueryDispatch.Decision.PAUSED,
            SensorQueryDispatch.decide(allPaused = true, hasDelegate = true),
        )
        assertEquals(
            SensorQueryDispatch.Decision.PAUSED,
            SensorQueryDispatch.decide(allPaused = true, hasDelegate = false),
        )
    }

    @Test
    fun `有委托时转发 Dart`() {
        assertEquals(
            SensorQueryDispatch.Decision.DELEGATE,
            SensorQueryDispatch.decide(allPaused = false, hasDelegate = true),
        )
    }

    @Test
    fun `无委托且未暂停时 fail-closed unavailable`() {
        assertEquals(
            SensorQueryDispatch.Decision.UNAVAILABLE,
            SensorQueryDispatch.decide(allPaused = false, hasDelegate = false),
        )
    }
}

class UiEventHubTest {

    @Test
    fun `事件扇出到全部监听器`() {
        val hub = UiEventHub()
        val got1 = mutableListOf<Map<String, Any?>>()
        val got2 = mutableListOf<Map<String, Any?>>()
        hub.add(UiEventHub.Listener { got1.add(it) })
        hub.add(UiEventHub.Listener { got2.add(it) })
        hub.emit(mapOf("kind" to "action_request", "action_id" to "a1"))
        assertEquals(1, got1.size)
        assertEquals(1, got2.size)
        assertEquals("a1", got1[0]["action_id"])
    }

    @Test
    fun `状态事件缓存并在新监听器注册时补发`() {
        val hub = UiEventHub()
        hub.emit(mapOf("kind" to "state", "state" to "online"))
        val got = mutableListOf<Map<String, Any?>>()
        hub.add(UiEventHub.Listener { got.add(it) })
        assertEquals(1, got.size)
        assertEquals("online", got[0]["state"])
    }

    @Test
    fun `非状态事件不覆盖状态缓存`() {
        val hub = UiEventHub()
        hub.emit(mapOf("kind" to "state", "state" to "degraded"))
        hub.emit(mapOf("kind" to "action_receipt", "action_id" to "x"))
        assertEquals("degraded", hub.peekLastState()?.get("state"))
    }

    @Test
    fun `注销后不再接收事件`() {
        val hub = UiEventHub()
        val got = mutableListOf<Map<String, Any?>>()
        val listener = UiEventHub.Listener { got.add(it) }
        hub.add(listener)
        hub.emit(mapOf("kind" to "state", "state" to "online"))
        val before = got.size
        hub.remove(listener)
        hub.emit(mapOf("kind" to "state", "state" to "disconnected"))
        assertEquals(before, got.size)
        assertFalse(hub.hasListeners)
    }

    @Test
    fun `单个监听器异常不影响其他监听器`() {
        val hub = UiEventHub()
        val got = mutableListOf<Map<String, Any?>>()
        hub.add(UiEventHub.Listener { throw RuntimeException("boom") })
        hub.add(UiEventHub.Listener { got.add(it) })
        hub.emit(mapOf("kind" to "error", "code" to "x"))
        assertEquals(1, got.size)
    }

    @Test
    fun `无监听器时 emit 不抛异常且无缓存污染`() {
        val hub = UiEventHub()
        hub.emit(mapOf("kind" to "action_request", "action_id" to "a2"))
        assertNull(hub.peekLastState())
    }
}
