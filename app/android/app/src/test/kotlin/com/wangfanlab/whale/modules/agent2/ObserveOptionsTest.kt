package com.wangfanlab.whale.modules.agent2

import org.junit.Assert.*
import org.junit.Test

class ObserveOptionsTest {
    @Test fun defaultsPreserveExistingObservation() {
        assertEquals(ObserveOptions(true, true, 400), ObserveOptions.parse(emptyMap()))
    }
    @Test fun explicitFlagsAndLimitArePreserved() {
        assertEquals(ObserveOptions(false, false, 20), ObserveOptions.parse(
            mapOf("screenshot" to false, "ui_tree" to false, "max_elements" to 20.0)))
    }
    @Test fun highElementLimitUsesDeviceCapacity() {
        assertEquals(400, ObserveOptions.parse(mapOf("max_elements" to 10000)).maxElements)
    }
    @Test fun invalidArgumentsAreRejected() {
        for (args in listOf(mapOf("screenshot" to "false"), mapOf("ui_tree" to 0),
                mapOf("max_elements" to -1), mapOf("max_elements" to 0.5),
                mapOf("max_elements" to Double.POSITIVE_INFINITY))) {
            try { ObserveOptions.parse(args); fail("accepted $args") }
            catch (expected: IllegalArgumentException) { }
        }
    }
}
