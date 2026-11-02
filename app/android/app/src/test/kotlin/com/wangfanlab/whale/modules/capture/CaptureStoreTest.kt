package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.capture.migration.ScreenMemoImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** SM-02：CaptureStore（FTS5/LIKE 检索、去重、证据路径）+ 迁移工具。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureStoreTest {

    private lateinit var context: Context
    private val dbName = "test_capture.db"
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

    @Test
    fun `插入与去重键幂等`() {
        assertTrue(store.insert(row(1000, "com.a", "你好世界")) > 0)
        // 同 (capture_time, package, dhash) 冲突 → -1
        assertEquals(-1, store.insert(row(1000, "com.a", "你好世界")))
        assertEquals(1, store.count())
    }

    @Test
    fun `中文查询走 LIKE 回退命中`() {
        store.insert(row(1000, "com.a", "今天在北京开会讨论产品路线图"))
        store.insert(row(2000, "com.b", "unrelated english text"))
        val hits = store.search("北京")
        assertEquals(1, hits.size)
        assertEquals("com.a", hits[0].packageName)
    }

    @Test
    fun `英文查询走 FTS5 命中`() {
        store.insert(row(1000, "com.a", "the quick brown fox jumps"))
        store.insert(row(2000, "com.b", "nothing relevant here"))
        val hits = store.search("quick")
        assertEquals(1, hits.size)
        assertEquals(1000, hits[0].captureTime)
    }

    @Test
    fun `FTS 查询运算符注入被转义`() {
        store.insert(row(1000, "com.a", "hello world"))
        // 不应因 FTS 语法崩溃
        val hits = store.search("\" OR 1=1 --")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `时间窗过滤`() {
        store.insert(row(1000, "com.a", "early"))
        store.insert(row(5000, "com.a", "late"))
        assertEquals(1, store.search("late", sinceMs = 2000).size)
        assertEquals(0, store.search("early", sinceMs = 2000).size)
    }

    @Test
    fun `recent 时间倒序`() {
        store.insert(row(1000, "com.a", "a"))
        store.insert(row(3000, "com.b", "b"))
        val r = store.recent(5)
        assertEquals(3000, r[0].captureTime)
    }

    @Test
    fun `证据文件路径逃逸被拒`() {
        val ok = row(1000, "com.a", "x").also { store.insert(it) }
        val evil = ok.copy(imagePath = "../../etc/passwd")
        assertNull(store.evidenceFile(context, evil))
    }

    @Test
    fun `needsLikeFallback 判定`() {
        assertTrue(CaptureStore.needsLikeFallback("中文"))
        assertFalse(CaptureStore.needsLikeFallback("english"))
        assertTrue(CaptureStore.needsLikeFallback("混合 mixed"))
    }
}

/** SM-02 迁移工具测试（fixture ScreenMemo 风格分库）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenMemoImporterTest {

    private lateinit var context: Context
    private lateinit var store: CaptureStore
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_capture_import.db")
        store = CaptureStore(context, "test_capture_import.db")
        baseDir = File(context.cacheDir, "sm_export")
        baseDir.deleteRecursively()
        File(baseDir, "databases/shards").mkdirs()
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("test_capture_import.db")
        baseDir.deleteRecursively()
    }

    private fun makeShard(name: String, table: String, rows: List<Array<Any?>>) {
        val f = File(baseDir, "databases/shards/$name")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL(
                "CREATE TABLE $table (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " file_path TEXT NOT NULL UNIQUE, capture_time INTEGER NOT NULL," +
                    " file_size INTEGER NOT NULL DEFAULT 0, page_url TEXT, ocr_text TEXT," +
                    " is_deleted INTEGER NOT NULL DEFAULT 0," +
                    " created_at INTEGER, updated_at INTEGER)",
            )
            for (r in rows) {
                db.execSQL(
                    "INSERT INTO $table(file_path, capture_time, ocr_text, is_deleted) VALUES(?,?,?,?)",
                    r,
                )
            }
        }
    }

    @Test
    fun `无效目录拒绝`() {
        val v = ScreenMemoImporter(context, store).validate(File("/nonexistent"))
        assertFalse(v.ok)
    }

    @Test
    fun `完整导入流程`() {
        makeShard(
            "com.example.reader_2026.db", "screenshots_202611",
            listOf(
                arrayOf("/data/x/1.jpg", 1000L, "第一章 内容", 0),
                arrayOf("/data/x/2.jpg", 2000L, "第二章 内容", 0),
                arrayOf("/data/x/3.jpg", 3000L, "已删除的", 1),
            ),
        )
        val importer = ScreenMemoImporter(context, store)
        val v = importer.validate(baseDir)
        assertTrue(v.ok)
        assertEquals(1, v.shardCount)
        val report = importer.import(baseDir)
        assertEquals(2, report.rowsImported)
        assertEquals(1, report.rowsSkippedDeleted)
        assertTrue(report.errors.isEmpty())
        // 中文检索命中迁移内容
        assertEquals(1, store.search("第一章").size)
        // 备份存在
        assertTrue(File(report.backupDir, "com.example.reader_2026.db").exists())
    }

    @Test
    fun `重复导入幂等（去重键 + checkpoint）`() {
        makeShard(
            "com.a_2026.db", "screenshots_202611",
            listOf(arrayOf("/p/1.jpg", 1000L, "内容一", 0)),
        )
        val importer = ScreenMemoImporter(context, store)
        importer.import(baseDir)
        // 再次导入：checkpoint 跳过（0 新行）
        val r2 = importer.import(baseDir)
        assertEquals(0, r2.rowsImported)
        assertEquals(1, store.count())
    }

    @Test
    fun `真实 ScreenMemo 分库格式夹具导入（已脱敏）`() {
        // 夹具来自 leaf5 真实分库（表结构 shots_YYYYMM + FTS 影子表），
        // ocr_text/file_path 已替换为合成内容（真实内容不入库）。
        val fixture = java.io.File(
            System.getProperty("user.dir"),
            "src/test/resources/fixtures/smm_com_dragon_read_2026.db",
        )
        assertTrue(fixture.exists())
        val dest = File(baseDir, "databases/shards/smm_com_dragon_read_2026.db")
        fixture.copyTo(dest, overwrite = true)
        val importer = ScreenMemoImporter(context, store)
        val v = importer.validate(baseDir)
        assertTrue("真实分库校验应通过", v.ok)
        val report = importer.import(baseDir)
        // 夹具共 77 行（shots_202610 76 + shots_202611 1）
        assertEquals(77, report.rowsImported)
        assertTrue(report.errors.isEmpty())
        assertEquals(77, store.count())
        // 合成中文内容可检索
        assertTrue(store.search("合成夹具文本").isNotEmpty())
    }

    @Test
    fun `增量导入只带新行`() {
        makeShard("com.a_2026.db", "screenshots_202611", listOf(arrayOf("/p/1.jpg", 1000L, "旧", 0)))
        val importer = ScreenMemoImporter(context, store)
        importer.import(baseDir)
        // 追加新行后重导
        val f = File(baseDir, "databases/shards/com.a_2026.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL(
                "INSERT INTO screenshots_202611(file_path, capture_time, ocr_text, is_deleted) VALUES('/p/2.jpg', 5000, '新', 0)",
            )
        }
        val r = importer.import(baseDir)
        assertEquals(1, r.rowsImported)
        assertEquals(2, store.count())
    }
}
