package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 3：CaptureStore v2（favorites/notes/segments/capture_imports）
 * 迁移、CRUD、存储治理测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureStorePhase3Test {

    private lateinit var context: Context
    private val dbName = "test_capture_p3.db"
    private lateinit var store: CaptureStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        store = CaptureStore(context, dbName)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
    }

    private fun row(ts: Long, pkg: String, ocr: String?, hash: Long = ts) = CaptureStore.Row(
        id = 0, captureTime = ts, packageName = pkg, appLabel = pkg, dhash = hash,
        width = 1080, height = 2400, ocrStatus = if (ocr != null) "ok" else "failed",
        ocrText = ocr, imagePath = "202611/$ts.jpg",
    )

    // ------------------------------------------------------------- 迁移 v1 → v2

    @Test
    fun `v1 库升级到 v2 数据保留且新表就绪`() {
        // 手工建一个 v1 结构的数据库（模拟升级前安装）
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE screenshots (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " capture_time INTEGER NOT NULL, package_name TEXT NOT NULL," +
                    " app_label TEXT, dhash INTEGER NOT NULL, width INTEGER NOT NULL," +
                    " height INTEGER NOT NULL, ocr_status TEXT NOT NULL DEFAULT 'pending'," +
                    " ocr_text TEXT, image_path TEXT NOT NULL, created_at INTEGER NOT NULL," +
                    " UNIQUE(capture_time, package_name, dhash))",
            )
            db.execSQL("CREATE INDEX idx_shots_time ON screenshots(capture_time DESC)")
            db.execSQL(
                "INSERT INTO screenshots(capture_time, package_name, dhash, width, height," +
                    " ocr_status, ocr_text, image_path, created_at)" +
                    " VALUES(1000, 'com.old', 42, 1, 1, 'ok', '旧数据', '', 1000)",
            )
            db.version = 1
        }
        // 用新 schema 打开 → onUpgrade(1→2)
        store = CaptureStore(context, dbName)
        assertEquals(1, store.count()) // 旧数据保留
        assertEquals("旧数据", store.recent(1)[0].ocrText)
        // 新表可用
        assertTrue(store.openSegment("segment_01JTEST0000000000000000A", "com.a", 1000, null))
        assertTrue(store.addFavorite(segmentId = "segment_01JTEST0000000000000000A") > 0)
        assertTrue(store.addNote(segmentId = "segment_01JTEST0000000000000000A", text = "备注") > 0)
        assertFalse(store.hasImport("bk_x"))
    }

    // ------------------------------------------------------------- favorites CRUD

    @Test
    fun `收藏 CRUD 与重复收藏幂等`() {
        val id = store.insert(row(1000, "com.a", "x"))
        assertTrue(id > 0)
        val favId = store.addFavorite(captureId = id, note = "重要")
        assertTrue(favId > 0)
        // 重复收藏同一截图 → -1
        assertEquals(-1, store.addFavorite(captureId = id))
        assertTrue(store.isFavoriteCapture(id))
        val list = store.listFavorites()
        assertEquals(1, list.size)
        assertEquals(id, list[0].captureId)
        assertEquals("重要", list[0].note)
        assertEquals(1, store.removeFavorite(captureId = id))
        assertFalse(store.isFavoriteCapture(id))
    }

    @Test
    fun `收藏段与列表`() {
        assertTrue(store.openSegment("segment_AAA", "com.a", 1000, null))
        assertTrue(store.addFavorite(segmentId = "segment_AAA") > 0)
        assertEquals(-1, store.addFavorite(segmentId = "segment_AAA"))
        val list = store.listFavorites()
        assertEquals(1, list.size)
        assertEquals("segment_AAA", list[0].segmentId)
        assertEquals(1, store.removeFavorite(segmentId = "segment_AAA"))
        assertTrue(store.listFavorites().isEmpty())
    }

    // ------------------------------------------------------------- notes CRUD

    @Test
    fun `备注 CRUD 与 updated_at 推进`() {
        val id = store.insert(row(1000, "com.a", "x"))
        val noteId = store.addNote(captureId = id, text = "第一条备注", createdAt = 5000)
        assertTrue(noteId > 0)
        var notes = store.listNotes(captureId = id)
        assertEquals(1, notes.size)
        assertEquals(5000, notes[0].createdAt)
        assertEquals(5000, notes[0].updatedAt)
        Thread.sleep(2)
        assertTrue(store.updateNote(noteId, "改后内容"))
        notes = store.listNotes(captureId = id)
        assertEquals("改后内容", notes[0].text)
        assertTrue(notes[0].updatedAt > notes[0].createdAt)
        assertTrue(store.deleteNote(noteId))
        assertTrue(store.listNotes(captureId = id).isEmpty())
    }

    @Test
    fun `noteExists 导入去重判定`() {
        val id = store.insert(row(1000, "com.a", "x"))
        store.addNote(captureId = id, text = "重复判定", createdAt = 7000)
        assertTrue(store.noteExists(id, null, "重复判定", 7000))
        assertFalse(store.noteExists(id, null, "重复判定", 7001))
        assertFalse(store.noteExists(id, null, "别的", 7000))
    }

    // ------------------------------------------------------------- segments CRUD

    @Test
    fun `段生命周期：开启-追加-封口`() {
        assertTrue(store.openSegment("segment_S1", "com.a", 1000, null))
        // 重复开同 id 段幂等
        assertFalse(store.openSegment("segment_S1", "com.a", 1000, null))
        val after1 = store.appendSegmentEvidence("segment_S1", "capture:1", 1000, "首行 OCR 摘要")
        assertNotNull(after1)
        assertEquals(1, after1!!.memberCount)
        assertEquals("首行 OCR 摘要", after1.summary)
        assertEquals(listOf("capture:1"), after1.evidence)
        // 摘要已有则不覆盖；ended_at 前移
        val after2 = store.appendSegmentEvidence("segment_S1", "capture:2", 5000, "别的")
        assertEquals("首行 OCR 摘要", after2!!.summary)
        assertEquals(5000, after2.endedAt)
        assertEquals(2, after2.memberCount)
        // 封口（幂等：二次封口返回 null）
        val closed = store.closeSegment("segment_S1")
        assertNotNull(closed)
        assertTrue(closed!!.closed)
        assertNull(store.closeSegment("segment_S1"))
        assertNull(store.currentOpenSegment())
    }

    @Test
    fun `currentOpenSegment 取最近未封口段`() {
        assertNull(store.currentOpenSegment())
        store.openSegment("segment_OLD", "com.a", 1000, null)
        store.openSegment("segment_NEW", "com.b", 2000, null)
        assertEquals("segment_NEW", store.currentOpenSegment()!!.segmentId)
    }

    // ------------------------------------------------------------- 存储治理

    private fun makeImage(relPath: String, bytes: Int): File {
        val f = File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/$relPath")
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun `purgeBefore 按时间清理且保留收藏`() {
        val id1 = store.insert(row(1000, "com.a", "旧一"))
        store.insert(row(2000, "com.a", "旧二"))
        store.insert(row(9000, "com.a", "新"))
        makeImage("202611/1000.jpg", 100)
        makeImage("202611/2000.jpg", 200)
        makeImage("202611/9000.jpg", 300)
        store.addFavorite(captureId = id1) // 收藏保护 1000
        val report = store.purgeBefore(cutoffMs = 5000)
        assertEquals(1, report.rowsDeleted)
        assertEquals(1, report.filesDeleted)
        assertEquals(200, report.bytesFreed)
        assertEquals(1, report.favoritesKept)
        assertEquals(2, store.count())
        assertNotNull(store.getById(id1)) // 收藏帧保留
        assertTrue(File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/202611/2000.jpg").let { !it.exists() })
    }

    @Test
    fun `enforceMaxImageBytes 从最旧开始删到达标`() {
        store.insert(row(1000, "com.a", "一"))
        store.insert(row(2000, "com.a", "二"))
        store.insert(row(3000, "com.a", "三"))
        makeImage("202611/1000.jpg", 100)
        makeImage("202611/2000.jpg", 100)
        makeImage("202611/3000.jpg", 100)
        val report = store.enforceMaxImageBytes(maxBytes = 150)
        assertEquals(2, report.rowsDeleted) // 删最旧两帧，剩 100B ≤ 150
        assertEquals(1, store.count())
        assertEquals("三", store.recent(1)[0].ocrText)
    }

    @Test
    fun `storageStats 统计行数与字节`() {
        store.insert(row(1000, "com.a", "一"))
        store.openSegment("segment_ST", "com.a", 1000, null)
        store.addFavorite(segmentId = "segment_ST")
        store.addNote(segmentId = "segment_ST", text = "n")
        makeImage("202611/1000.jpg", 123)
        val s = store.storageStats()
        assertEquals(1, s.captures)
        assertEquals(1, s.segments)
        assertEquals(1, s.favorites)
        assertEquals(1, s.notes)
        assertEquals(1, s.imageFiles)
        assertEquals(123, s.imageBytes)
    }

    @Test
    fun `purge 后 FTS 不再命中已删行`() {
        store.insert(row(1000, "com.a", "will be purged keyword"))
        assertEquals(1, store.search("purged").size)
        store.purgeBefore(cutoffMs = 2000)
        assertTrue(store.search("purged").isEmpty())
    }

    // ------------------------------------------------------------- 导入台账

    @Test
    fun `recordImport 幂等与 id_map 存取`() {
        assertTrue(store.recordImport("bk_1", mapOf("7" to "42"), "r"))
        assertFalse(store.recordImport("bk_1", emptyMap(), "r"))
        assertTrue(store.hasImport("bk_1"))
        assertFalse(store.hasImport("bk_2"))
    }
}
