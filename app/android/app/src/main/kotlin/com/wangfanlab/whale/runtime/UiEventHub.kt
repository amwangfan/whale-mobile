package com.wangfanlab.whale.runtime

import java.util.concurrent.CopyOnWriteArrayList

/**
 * UI 事件扇出器（BG-01，纯逻辑可单测）：
 * - 多个 UI 监听器注册/注销；事件向所有在线监听器分发（单个监听器异常不影响其他）。
 * - 缓存最近一次 kind=state 的状态事件，新监听器注册时补发（UI 重建即得当前状态）。
 */
class UiEventHub {

    fun interface Listener {
        fun onEvent(event: Map<String, Any?>)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var lastStateEvent: Map<String, Any?>? = null

    fun add(listener: Listener) {
        listeners.addIfAbsent(listener)
        lastStateEvent?.let { state ->
            try {
                listener.onEvent(state)
            } catch (_: Throwable) {
                // 监听器异常不影响注册
            }
        }
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    val hasListeners: Boolean get() = listeners.isNotEmpty()

    val listenerCount: Int get() = listeners.size

    /** 分发事件；kind=state 的事件会缓存为最近状态。 */
    fun emit(event: Map<String, Any?>) {
        if (event["kind"] == "state") {
            lastStateEvent = event
        }
        for (l in listeners) {
            try {
                l.onEvent(event)
            } catch (_: Throwable) {
                // 单个监听器异常不影响其他监听器
            }
        }
    }

    fun peekLastState(): Map<String, Any?>? = lastStateEvent
}
