package com.wangfanlab.whale.modules.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 内置采集存储（SM-02，Issue #9）：截图元数据 + OCR 文本索引 + FTS5/LIKE 检索。
 *
 * - 库 `whale_capture.db`：screenshots 表（元数据 + ocr_text）+
 *   screenshots_fts（FTS5 unicode61；中文等无空格语言走 LIKE 回退）；
 * - 图像文件存 filesDir/capture/shots/（相对路径入库；出库经
 *   [evidenceFile] 做根目录逃逸校验，不透明引用规则与 WM-03 一致）；
 * - 去重键：(capture_time, package_name, dhash) 唯一索引——迁移重放/双写幂等；
 * - 隐私：本表为 owner_sensitive，永不进任何群聊/上行路径（上行只有
 *   经 adapter 裁剪的条目或影子统计）。
 */
class CaptureStore(
    context: Context,
    dbName: String = DEFAULT_DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

    data class Row(
        val id: Long,
        val captureTime: Long,
        val packageName: String,
        val appLabel: String?,
        val dhash: Long,
        val width: Int,
        val height: Int,
        val ocrStatus: String,
        val ocrText: String?,
        val imagePath: String,
    )

    /** FTS5 是否可用（部分 OEM/测试环境的 SQLite 构建无 FTS5 → LIKE 全回退）。 */
    @Volatile
    var fts5Available: Boolean = true
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE screenshots (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              capture_time  INTEGER NOT NULL,
              package_name  TEXT NOT NULL,
              app_label     TEXT,
              dhash         INTEGER NOT NULL,
              width         INTEGER NOT NULL,
              height        INTEGER NOT NULL,
              ocr_status    TEXT NOT NULL DEFAULT 'pending',
              ocr_text      TEXT,
              image_path    TEXT NOT NULL,
              created_at    INTEGER NOT NULL,
              UNIQUE(capture_time, package_name, dhash)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_shots_time ON screenshots(capture_time DESC)")
        fts5Available = try {
            db.execSQL(
                "CREATE VIRTUAL TABLE screenshots_fts USING fts5(" +
                    "ocr_text, content='screenshots', content_rowid='id')",
            )
            true
        } catch (t: Throwable) {
            // SQLite 构建无 FTS5（部分 OEM/测试环境）：降级为纯 LIKE 检索
            android.util.Log.w("WhaleCaptureStore", "FTS5 不可用，降级 LIKE 检索")
            false
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 为首个版本；后续迁移按版本追加
    }

    /**
     * 插入截图记录（幂等：去重键冲突返回 -1）。OCR 文本同步进 FTS。
     */
    @Synchronized
    fun insert(row: Row): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("capture_time", row.captureTime)
                put("package_name", row.packageName)
                put("app_label", row.appLabel)
                put("dhash", row.dhash)
                put("width", row.width)
                put("height", row.height)
                put("ocr_status", row.ocrStatus)
                put("ocr_text", row.ocrText)
                put("image_path", row.imagePath)
                put("created_at", System.currentTimeMillis())
            }
            val id = db.insertWithOnConflict(
                "screenshots", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (id >= 0 && fts5Available && !row.ocrText.isNullOrBlank()) {
                db.insert(
                    "screenshots_fts", null,
                    ContentValues().apply {
                        put("rowid", id)
                        put("ocr_text", row.ocrText)
                    },
                )
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 关键词检索（时间倒序）。FTS5 对拉丁/数字分词；含 CJK 字符的查询
     * 走 LIKE 回退（unicode61 不对中文分词）。
     */
    @Synchronized
    fun search(query: String, limit: Int = 20, sinceMs: Long? = null): List<Row> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val sinceClause = if (sinceMs != null) " AND s.capture_time >= $sinceMs" else ""
        return if (needsLikeFallback(q) || !fts5Available) {
            readableDatabase.rawQuery(
                "SELECT s.* FROM screenshots s WHERE s.ocr_text LIKE ?$sinceClause" +
                    " ORDER BY s.capture_time DESC LIMIT ?",
                arrayOf("%${q.trim()}%", limit.toString()),
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT s.* FROM screenshots s JOIN screenshots_fts f ON f.rowid = s.id" +
                    " WHERE screenshots_fts MATCH ?$sinceClause" +
                    " ORDER BY s.capture_time DESC LIMIT ?",
                arrayOf(ftsSanitize(q), limit.toString()),
            )
        }.use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }
    }

    /** 最近上下文（screen.context.read 用）。 */
    @Synchronized
    fun recent(limit: Int = 5): List<Row> =
        readableDatabase.rawQuery(
            "SELECT * FROM screenshots ORDER BY capture_time DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }

    @Synchronized
    fun count(): Long = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM screenshots",
    ).use { it.simpleQueryForLong() }

    /** 证据文件（防路径逃逸：仅允许 filesDir/capture/shots 下的相对路径）。 */
    fun evidenceFile(context: Context, row: Row): java.io.File? {
        val root = java.io.File(context.filesDir, SHOTS_DIR_RELATIVE)
        val f = java.io.File(root, row.imagePath)
        return try {
            if (f.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator) &&
                f.exists()
            ) f else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun readRow(c: android.database.Cursor): Row = Row(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        captureTime = c.getLong(c.getColumnIndexOrThrow("capture_time")),
        packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
        appLabel = c.getString(c.getColumnIndexOrThrow("app_label")),
        dhash = c.getLong(c.getColumnIndexOrThrow("dhash")),
        width = c.getInt(c.getColumnIndexOrThrow("width")),
        height = c.getInt(c.getColumnIndexOrThrow("height")),
        ocrStatus = c.getString(c.getColumnIndexOrThrow("ocr_status")),
        ocrText = c.getString(c.getColumnIndexOrThrow("ocr_text")),
        imagePath = c.getString(c.getColumnIndexOrThrow("image_path")),
    )

    companion object {
        private const val DB_VERSION = 1
        const val DEFAULT_DB_NAME = "whale_capture.db"
        const val SHOTS_DIR_RELATIVE = "capture/shots"

        /** 含 CJK 的查询用 LIKE 回退（unicode61 不分中文词）。 */
        fun needsLikeFallback(query: String): Boolean =
            query.any { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }

        /** FTS5 查询转义：每个词加双引号（防运算符注入）。 */
        fun ftsSanitize(query: String): String =
            query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .joinToString(" ") { "\"${it.replace("\"", "")}\"" }
    }
}
