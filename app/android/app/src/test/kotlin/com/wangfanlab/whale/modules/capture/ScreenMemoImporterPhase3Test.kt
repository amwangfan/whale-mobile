package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.capture.migration.ScreenMemoImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 3：旧 ScreenMemo 库的收藏/备注映射导入 + 重复导入不重复。
 * （沿用既有纪律：只读旧库 + 备份先行。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenMemoImporterPhase3Test {

    private lateinit var context: Context
    private lateinit var store: CaptureStore
    private lateinit var baseDir: File
    private val dbName = "test_capture_import_p3.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        store = CaptureStore(context, dbName)
        baseDir = File(context.cacheDir, "sm_export_p3")
        baseDir.deleteRecursively()
        File(baseDir, "databases/shards").mkdirs()
        // 隔离 checkpoint（与既有测试共用 whale_capture prefs）
        context.getSharedPreferences("whale_capture", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
        baseDir.deleteRecursively()
        context.getSharedPreferences("whale_capture", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** 建带收藏/备注表的旧分库。 */
    private fun makeShardWithMeta(name: String) {
        val f = File(baseDir, "databases/shards/$name")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL(
                "CREATE TABLE screenshots_202611 (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " file_path TEXT NOT NULL UNIQUE, capture_time INTEGER NOT NULL," +
                    " file_size INTEGER NOT NULL DEFAULT 0, page_url TEXT, ocr_text TEXT," +
                    " is_deleted INTEGER NOT NULL DEFAULT 0," +
                    " created_at INTEGER, updated_at INTEGER)",
            )
            db.execSQL(
                "INSERT INTO screenshots_202611(file_path, capture_time, ocr_text, is_deleted)" +
                    " VALUES('/p/1.jpg', 1000, '内容一', 0), ('/p/2.jpg', 2000, '内容二', 0)",
            )
            db.execSQL(
                "CREATE TABLE favorites (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " capture_time INTEGER NOT NULL, note TEXT, created_at INTEGER)",
            )
            db.execSQL("INSERT INTO favorites(capture_time, note, created_at) VALUES(1000, '旧收藏', 5000)")
            db.execSQL(
                "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " capture_time INTEGER NOT NULL, text TEXT, created_at INTEGER, updated_at INTEGER)",
            )
            db.execSQL("INSERT INTO notes(capture_time, text, created_at, updated_at) VALUES(2000, '旧备注', 6000, 6000)")
        }
    }

    @Test
    fun `旧库收藏备注映射进新表且保留关联`() {
        // 分库文件名 → 包名（packageOfShard 启发式：下划线转点；用无年份后缀名得 "com.a"）
        makeShardWithMeta("com.a.db")
        val importer = ScreenMemoImporter(context, store)
        assertTrue(importer.validate(baseDir).ok)
        val report = importer.import(baseDir)
        assertEquals(2, report.rowsImported)
        assertEquals(1, report.favoritesImported)
        assertEquals(1, report.notesImported)
        // 关联到映射后的目标行
        val targetId = store.findIdByKey(1000, "com.a", 0L)!!
        assertTrue(store.isFavoriteCapture(targetId))
        val fav = store.listFavorites().first { it.captureId == targetId }
        assertEquals("旧收藏", fav.note)
        assertEquals(5000, fav.createdAt)
        val target2 = store.findIdByKey(2000, "com.a", 0L)!!
        val notes = store.listNotes(captureId = target2)
        assertEquals(1, notes.size)
        assertEquals("旧备注", notes[0].text)
        assertEquals(6000, notes[0].createdAt)
    }

    @Test
    fun `重复导入收藏备注不重复`() {
        makeShardWithMeta("com.a.db")
        val importer = ScreenMemoImporter(context, store)
        importer.import(baseDir)
        // 第二次：截图走 checkpoint 跳过（0 行）；收藏/备注即使重扫也幂等
        val r2 = importer.import(baseDir)
        assertEquals(0, r2.rowsImported)
        assertEquals(2, store.count())
        assertEquals(1, store.listFavorites().size)
        assertEquals(1, store.listNotes().size)
    }

    @Test
    fun `无收藏备注表的旧分库照常导入`() {
        val f = File(baseDir, "databases/shards/com.b_2026.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL(
                "CREATE TABLE screenshots_202611 (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " file_path TEXT NOT NULL UNIQUE, capture_time INTEGER NOT NULL," +
                    " file_size INTEGER NOT NULL DEFAULT 0, page_url TEXT, ocr_text TEXT," +
                    " is_deleted INTEGER NOT NULL DEFAULT 0," +
                    " created_at INTEGER, updated_at INTEGER)",
            )
            db.execSQL(
                "INSERT INTO screenshots_202611(file_path, capture_time, ocr_text, is_deleted)" +
                    " VALUES('/p/9.jpg', 9000, '内容', 0)",
            )
        }
        val report = ScreenMemoImporter(context, store).import(baseDir)
        assertEquals(1, report.rowsImported)
        assertEquals(0, report.favoritesImported)
        assertEquals(0, report.notesImported)
        assertFalse(store.listCaptures().isEmpty())
    }
}

/** Phase 3：排除包名持久化读写（隐私遮罩基础）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureExclusionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CaptureModule.setExcludedPackages(context, emptySet())
    }

    @After
    fun tearDown() {
        CaptureModule.setExcludedPackages(context, emptySet())
    }

    @Test
    fun `排除列表读写规范化与决策联动`() {
        assertTrue(CaptureModule.excludedPackages(context).isEmpty())
        CaptureModule.setExcludedPackages(context, setOf(" com.bank.a ", "", "com.bank.b", "com.bank.b"))
        assertEquals(setOf("com.bank.a", "com.bank.b"), CaptureModule.excludedPackages(context))
        // CaptureDecider 命中排除
        val decision = CaptureDecider.shouldCapture(
            moduleEnabled = true, allPaused = false, conflictWithStandalone = false,
            deviceLocked = false, foregroundPackage = "com.bank.a",
            excludedPackages = CaptureModule.excludedPackages(context),
            nowMs = 10_000, lastCaptureMs = 0,
        )
        assertEquals(CaptureDecider.Decision.Skip("excluded_package"), decision)
    }

    @Test
    fun `增删单个包名幂等`() {
        assertTrue(CaptureModule.addExcludedPackage(context, "com.wallet"))
        assertFalse(CaptureModule.addExcludedPackage(context, "com.wallet"))
        assertFalse(CaptureModule.addExcludedPackage(context, "  "))
        assertEquals(setOf("com.wallet"), CaptureModule.excludedPackages(context))
        assertTrue(CaptureModule.removeExcludedPackage(context, "com.wallet"))
        assertFalse(CaptureModule.removeExcludedPackage(context, "com.wallet"))
        assertTrue(CaptureModule.excludedPackages(context).isEmpty())
    }
}
