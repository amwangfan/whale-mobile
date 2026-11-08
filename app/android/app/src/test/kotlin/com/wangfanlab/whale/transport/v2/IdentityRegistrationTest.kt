package com.wangfanlab.whale.transport.v2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdentityRegistrationTest {
    @Test fun sameDispatcherIsIdempotentAndRebuiltDispatcherIsRegistered() {
        val registration = IdentityRegistration<Any>()
        val first = Any()
        var calls = 0
        registration.ensure(first) { calls++ }
        registration.ensure(first) { calls++ }
        registration.ensure(Any()) { calls++ }
        assertEquals(2, calls)
    }
    @Test fun failedRegistrationCanRetry() {
        val registration = IdentityRegistration<Any>()
        val dispatcher = Any()
        try { registration.ensure(dispatcher) { throw IllegalStateException("failed") }; fail() }
        catch (expected: IllegalStateException) { }
        var registered = false
        registration.ensure(dispatcher) { registered = true }
        assertTrue(registered)
    }
    @Test fun resetAllowsSameObjectToRegisterAgain() {
        val registration = IdentityRegistration<Any>()
        val dispatcher = Any()
        var calls = 0
        registration.ensure(dispatcher) { calls++ }
        registration.clear()
        registration.ensure(dispatcher) { calls++ }
        assertEquals(2, calls)
    }
    @Test fun parallelRegistrationRunsOnce() {
        val registration = IdentityRegistration<Any>()
        val dispatcher = Any()
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        repeat(32) { executor.submit { registration.ensure(dispatcher) { calls.incrementAndGet() } } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }
}
