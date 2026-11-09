package com.wangfanlab.whale.transport.v2

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** A native action can finish after its owning client has already shut down. */
internal fun ScheduledExecutorService.executeIfOpen(task: () -> Unit) {
    try {
        execute(task)
    } catch (error: RejectedExecutionException) {
        if (!isShutdown) throw error
    }
}

/** Serialize the final cleanup after accepted tasks; later submissions are harmless. */
class SerialLifecycle(private val executor: ScheduledExecutorService) {
    private var closing = false
    @Volatile private var worker: Thread? = null

    @Synchronized fun execute(task: () -> Unit) {
        if (!closing) executor.execute {
            worker = Thread.currentThread()
            task()
        }
    }

    fun close(cleanup: () -> Unit) {
        synchronized(this) {
            if (closing) return
            closing = true
            executor.execute {
                try {
                    cleanup()
                } finally {
                    executor.shutdown()
                }
            }
        }
        // Cleanup is queued behind this task; waiting here would block it.
        if (Thread.currentThread() === worker) return
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
