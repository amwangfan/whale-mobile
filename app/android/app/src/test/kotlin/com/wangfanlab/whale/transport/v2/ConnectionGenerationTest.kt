package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionGenerationTest {
    @Test fun lateOpenMessageAndFailureCannotAffectReplacement() {
        val gate = ConnectionGeneration()
        val old = gate.begin()
        val effects = mutableListOf<String>()
        val queued = listOf("open", "message", "failure", "closed").map { event ->
            { gate.runIfCurrent(old) { effects.add(event) } }
        }
        val replacement = gate.begin()
        queued.forEach { it() }
        gate.runIfCurrent(replacement) { effects.add("new open") }
        assertEquals(listOf("new open"), effects)
    }

    @Test fun disconnectDropsQueuedCallbacksAndNextConnectionStillWorks() {
        val gate = ConnectionGeneration()
        val old = gate.begin()
        var count = 0
        gate.invalidate()
        gate.runIfCurrent(old) { count++ }
        val next = gate.begin()
        gate.runIfCurrent(next) { count++ }
        assertEquals(1, count)
    }

    @Test fun firstFailureInvalidatesOtherCallbacksFromSameConnection() {
        val gate = ConnectionGeneration()
        val token = gate.begin()
        var retries = 0
        repeat(2) {
            gate.runIfCurrent(token) { retries++; gate.invalidate() }
        }
        assertEquals(1, retries)
    }
}
