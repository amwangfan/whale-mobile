package com.wangfanlab.whale.modules.notificationread

/**
 * 通知事件有界缓冲（SEC-02，纯逻辑可单测）。
 *
 * 运行时尚未就绪（静态钩子未注册）时，NotificationListenerService 不丢事件：
 * 进入本缓冲（容量上限 [capacity]，溢出丢最旧并计数）；钩子注册后统一回放。
 * 缓冲只存脱敏字段（package/标题≤80/category/time），与模块脱敏纪律一致。
 */
class NotificationEventBuffer<T>(private val capacity: Int = DEFAULT_CAPACITY) {

    private val deque = ArrayDeque<T>()

    /** 因溢出被丢弃的事件总数（可观测）。 */
    var droppedOverflow: Long = 0
        private set

    val size: Int get() = deque.size

    /** 入队；溢出丢最旧。 */
    @Synchronized
    fun offer(item: T) {
        if (deque.size >= capacity) {
            deque.removeFirst()
            droppedOverflow++
        }
        deque.addLast(item)
    }

    /** 取出全部（清空），按入队顺序。 */
    @Synchronized
    fun drain(): List<T> {
        val out = deque.toList()
        deque.clear()
        return out
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
    }
}
