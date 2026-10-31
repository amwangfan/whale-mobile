package com.wangfanlab.whale.modules.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM-01 采集决策单测（纯逻辑）。 */
class CaptureDeciderTest {

    private fun decide(
        moduleEnabled: Boolean = true,
        allPaused: Boolean = false,
        conflict: Boolean = false,
        locked: Boolean = false,
        foreground: String? = "com.example.app",
        excluded: Set<String> = emptySet(),
        nowMs: Long = 10_000L,
        lastCaptureMs: Long = 0L,
        intervalMs: Long = CaptureDecider.DEFAULT_INTERVAL_MS,
    ) = CaptureDecider.shouldCapture(
        moduleEnabled, allPaused, conflict, locked, foreground, excluded,
        nowMs, lastCaptureMs, intervalMs,
    )

    @Test
    fun `正常条件采集`() {
        assertEquals(CaptureDecider.Decision.Capture, decide())
    }

    @Test
    fun `模块关闭跳过`() {
        assertEquals(
            CaptureDecider.Decision.Skip("module_disabled"),
            decide(moduleEnabled = false),
        )
    }

    @Test
    fun `一键暂停优先`() {
        assertEquals(CaptureDecider.Decision.Skip("all_paused"), decide(allPaused = true))
    }

    @Test
    fun `独立 ScreenMemo 冲突互斥`() {
        assertEquals(
            CaptureDecider.Decision.Skip("conflict_standalone_screenmemo"),
            decide(conflict = true),
        )
    }

    @Test
    fun `锁屏跳过`() {
        assertEquals(CaptureDecider.Decision.Skip("device_locked"), decide(locked = true))
    }

    @Test
    fun `排除包名跳过`() {
        assertEquals(
            CaptureDecider.Decision.Skip("excluded_package"),
            decide(foreground = "com.bank.app", excluded = setOf("com.bank.app")),
        )
    }

    @Test
    fun `输入法跳过`() {
        assertTrue(CaptureDecider.isImePackage("com.google.android.inputmethod.latin"))
        assertTrue(CaptureDecider.isImePackage("com.sohu.inputmethod.sogou"))
        assertFalse(CaptureDecider.isImePackage("com.example.reader"))
        assertEquals(
            CaptureDecider.Decision.Skip("ime"),
            decide(foreground = "com.google.android.inputmethod.latin"),
        )
    }

    @Test
    fun `间隔节流`() {
        assertEquals(
            CaptureDecider.Decision.Skip("interval"),
            decide(nowMs = 3_000L, lastCaptureMs = 0L, intervalMs = 5_000L),
        )
    }

    @Test
    fun `dHash 去重判定`() {
        assertFalse(CaptureDecider.isDuplicate(null, 0xFF))
        assertTrue(CaptureDecider.isDuplicate(0b1111L, 0b1111L)) // 距离 0
        assertTrue(CaptureDecider.isDuplicate(0b1111L, 0b1110L)) // 距离 1
        assertFalse(CaptureDecider.isDuplicate(0L, -1L)) // 距离 64
        assertEquals(4, CaptureDecider.hamming(0b1010L, 0b0101L)) // xor=0b1111
    }
}
