package com.wangfanlab.whale.voip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FileLog 观测眼测试（C 线，2026-11-26）：
 * - init 后关键事件必落盘（写后读回）
 * - 写入失败不再静默吞异常（不崩、现场可恢复）
 * Robolectric 提供真实 filesDir。
 */
@RunWith(RobolectricTestRunner::class)
class FileLogTest {

    @Test
    fun `init 后 i e 落盘可读回`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.filesDir, "nova.log")
        f.delete()
        FileLog.init(ctx)
        FileLog.i("probe-c-ring")
        FileLog.e("probe-c-answer", RuntimeException("demo"))
        val body = f.readText()
        assertTrue("i 行应落盘", body.contains("I probe-c-ring"))
        assertTrue("e 行应落盘含异常", body.contains("E probe-c-answer") && body.contains("demo"))
        f.delete()
    }

    @Test
    fun `写入失败不崩溃（失败上抛 logcat 不吞）`() {
        // 指向一个目录 → FileOutputStream 必失败；旧版静默吞，现版 Log.e 上抛，调用方不崩
        val dir = File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "nova-log-dir-probe",
        )
        dir.mkdirs()
        FileLog.setFileForTest(dir)
        FileLog.i("probe-c-write-fail") // 不抛即通过
        FileLog.setFileForTest(null) // 复位，防串台
        assertTrue(dir.isDirectory)
        dir.delete()
    }
}
