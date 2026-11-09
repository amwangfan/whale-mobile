package com.wangfanlab.whale.transport.v2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SerialLifecycleTest {
    @Test fun closeFromWorkerDoesNotBlockItsOwnCleanup() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SerialLifecycle(executor)
        val cleaned = CountDownLatch(1)
        lifecycle.execute { lifecycle.close { cleaned.countDown() } }
        assertTrue(cleaned.await(1, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test fun nativeCompletionAfterShutdownDoesNotThrowOrRun() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.shutdown()
        executor.executeIfOpen { fail("late completion must not run") }
        assertTrue(executor.isTerminated)
    }

    @Test fun nativeCompletionBeforeShutdownStillRuns() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val done = CountDownLatch(1)
        executor.executeIfOpen { done.countDown() }
        executor.shutdown()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test fun acceptedWorkFinishesBeforeCleanupAndLateCallsAreIgnored() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SerialLifecycle(executor)
        val effects = mutableListOf<String>()
        lifecycle.execute { effects.add("accepted") }
        lifecycle.close { effects.add("cleanup") }
        lifecycle.execute { effects.add("late") }
        lifecycle.close { effects.add("duplicate cleanup") }
        assertEquals(listOf("accepted", "cleanup"), effects)
        assertTrue(executor.isTerminated)
    }

    @Test fun concurrentCloseRunsCleanupOnceAndAllowsAlreadyQueuedWorkToSchedule() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val lifecycle = SerialLifecycle(executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val scheduled = CountDownLatch(1)
        lifecycle.execute {
            entered.countDown()
            assertTrue(release.await(1, TimeUnit.SECONDS))
            executor.schedule({ scheduled.countDown() }, 0, TimeUnit.MILLISECONDS)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val first = Thread { lifecycle.close { cleanupCount.incrementAndGet() }; closed.countDown() }
        first.start()
        val second = Thread { lifecycle.close { cleanupCount.incrementAndGet() } }
        second.start()
        release.countDown()
        first.join(3000)
        second.join(3000)
        assertEquals(1, cleanupCount.get())
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertTrue(scheduled.await(1, TimeUnit.SECONDS))
        assertTrue(executor.isTerminated)
    }
}
