package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/** SM-03：内置数据源查询处理器（Robolectric + 真实 CaptureStore）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeScreenQueryHandlerTest {

    private lateinit var context: Context
    private lateinit var store: CaptureStore
    private lateinit var handler: NativeScreenQueryHandler
    private val replies = mutableListOf<Map<String, Any?>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_native_q.db")
        store = CaptureStore(context, "test_native_q.db")
        replies.clear()
        handler = NativeScreenQueryHandler(context, store) { requestId, status, data, truncated, source ->
            replies.add(
                mapOf(
                    "request_id" to requestId, "status" to status,
                    "data" to data, "truncated" to truncated, "source" to source,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("test_native_q.db")
    }

    private fun row(ts: Long, pkg: String, ocr: String?) = CaptureStore.Row(
        id = 0, captureTime = ts, packageName = pkg, appLabel = pkg, dhash = ts,
        width = 100, height = 200, ocrStatus = if (ocr != null) "ok" else "failed",
        ocrText = ocr, imagePath = "202611/$ts.jpg",
    )

    @Test
    fun `history_search 命中且形状与 adapter 对齐`() {
        store.insert(row(1000, "com.a", "今天开会讨论产品"))
        val ok = handler.handle(
            mapOf(
                "request_id" to "r1", "operation" to "screen.history.search",
                "filters" to mapOf("query" to "开会"), "max_items" to 10,
            ),
        )
        assertTrue(ok)
        val r = replies.single()
        assertEquals("ok", r["status"])
        assertEquals("capture.local", r["source"])
        @Suppress("UNCHECKED_CAST")
        val data = r["data"] as List<Map<String, Any?>>
        assertEquals(1, data.size)
        assertEquals("screenshot", data[0]["kind"])
        assertEquals("owner_private", data[0]["privacy_class"])
        assertNotNull(data[0]["evidence_ref"])
        assertTrue((data[0]["snippet"] as String).length <= 200)
    }

    @Test
    fun `history_search 缺 query 回 error`() {
        handler.handle(mapOf("request_id" to "r2", "operation" to "screen.history.search"))
        assertEquals("error", replies.single()["status"])
    }

    @Test
    fun `context_read 返回最近条目`() {
        store.insert(row(1000, "com.a", "早"))
        store.insert(row(2000, "com.b", "晚"))
        handler.handle(mapOf("request_id" to "r3", "operation" to "screen.context.read"))
        @Suppress("UNCHECKED_CAST")
        val data = replies.single()["data"] as List<Map<String, Any?>>
        assertEquals(2, data.size)
        assertEquals(2000L, data[0]["capture_time"])
    }

    @Test
    fun `evidence_read 缺 refs 回 error`() {
        handler.handle(mapOf("request_id" to "r4", "operation" to "screen.evidence.read"))
        assertEquals("error", replies.single()["status"])
    }

    @Test
    fun `evidence_read 合法 ref 返回压缩图`() {
        // 造一张真实 JPEG 到证据目录
        val dir = File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/202611")
        dir.mkdirs()
        File(dir, "3000.jpg").also { f ->
            FileOutputStream(f).use {
                Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
        }
        val id = store.insert(row(3000, "com.a", "有图"))
        handler.handle(
            mapOf(
                "request_id" to "r5", "operation" to "screen.evidence.read",
                "filters" to mapOf("image_refs" to listOf("capture:$id")),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val data = replies.single()["data"] as List<Map<String, Any?>>
        assertEquals("capture:$id", data[0]["image_ref"])
        assertEquals("owner_sensitive", data[0]["privacy_class"])
        assertNotNull(data[0]["data_base64"])
    }

    @Test
    fun `evidence_read 未知 ref 回 not_found`() {
        handler.handle(
            mapOf(
                "request_id" to "r6", "operation" to "screen.evidence.read",
                "filters" to mapOf("image_refs" to listOf("capture:999")),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val data = replies.single()["data"] as List<Map<String, Any?>>
        assertEquals("not_found", data[0]["error"])
    }

    @Test
    fun `supports 判定`() {
        assertTrue(NativeScreenQueryHandler.supports("screen.history.search"))
        assertTrue(NativeScreenQueryHandler.supports("screen.context.read"))
        assertTrue(NativeScreenQueryHandler.supports("screen.evidence.read"))
        assertTrue(!NativeScreenQueryHandler.supports("screen.live.subscribe"))
    }
}
