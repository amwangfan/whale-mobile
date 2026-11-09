package com.wangfanlab.whale.modules.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingOptionsPolicyTest {
    @Test fun absentOptionsKeepDefaults() {
        assertEquals(RecordingOptionsPolicy.Options(null, null, emptySet()), RecordingOptionsPolicy.parse(null))
    }
    @Test fun validOptionsPreserveExclusionsAndExactDuration() {
        assertEquals(RecordingOptionsPolicy.Options("demo", 1000, setOf("bank.app", "work.app")),
            RecordingOptionsPolicy.parse(mapOf("profile" to "demo", "max_duration_ms" to 1000.0,
                "exclude_packages" to listOf("bank.app", "work.app", "bank.app"))))
    }
    @Test fun malformedExclusionsNeverSilentlyBecomeEmpty() {
        for (value in listOf("bank.app", listOf("bank.app", 123), null, listOf("a".repeat(129)))) {
            assertNull(RecordingOptionsPolicy.parse(mapOf("exclude_packages" to value)))
        }
    }
    @Test fun invalidDurationCannotRemoveTheLimit() {
        for (value in listOf<Any?>("1000", null, true, 1000.5, Double.NaN, Double.POSITIVE_INFINITY, 999, 3600001)) {
            assertNull(RecordingOptionsPolicy.parse(mapOf("max_duration_ms" to value)))
        }
    }
    @Test fun invalidContainerUnknownFieldsAndProfilesFail() {
        assertNull(RecordingOptionsPolicy.parse("default"))
        assertNull(RecordingOptionsPolicy.parse(mapOf("exclude_package" to listOf("bank.app"))))
        assertNull(RecordingOptionsPolicy.parse(mapOf("profile" to true)))
        assertNull(RecordingOptionsPolicy.parse(mapOf("profile" to null)))
    }
}
