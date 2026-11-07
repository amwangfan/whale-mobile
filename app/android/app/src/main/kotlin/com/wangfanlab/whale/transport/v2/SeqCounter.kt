package com.wangfanlab.whale.transport.v2

import java.util.concurrent.atomic.AtomicLong

/**
 * 每方向各自单调递增的序号（自 v1 transport/Envelope.kt 迁入 v2 包；
 * contracts/v2 §4：每条 WS 连接上行从 1 重新开始，下行观察记录）。
 */
class SeqCounter(start: Long = 0) {
    private val value = AtomicLong(start)

    /** 返回下一个序号（首次调用返回 start+1）。 */
    fun next(): Long = value.incrementAndGet()

    /** 当前已发出的最大序号（尚未发送时为 start）。 */
    fun current(): Long = value.get()

    /** 记录对端序号并校验单调性；返回是否为预期值（乱序/跳变返回 false，仅提示不拒绝）。 */
    fun observeIncoming(seq: Long): Boolean {
        while (true) {
            val cur = value.get()
            if (seq <= cur) return false // 重复或乱序
            if (value.compareAndSet(cur, seq)) return seq == cur + 1
        }
    }
}
