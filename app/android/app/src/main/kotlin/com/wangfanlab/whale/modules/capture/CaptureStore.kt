package com.wangfanlab.whale.modules.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * 内置采集存储（SM-02，Issue #9；Phase 3 扩展 v2）：截图元数据 + OCR 文本索引
 * + FTS5/LIKE 检索 + 收藏/备注/动态段（segment）+ 导入幂等台账 + 存储治理。
 *
 * - 库 `whale_capture.db`：
 *   - v1：screenshots 表（元数据 + ocr_text）+ screenshots_fts（FTS5 unicode61；
 *     中文等无空格语言走 LIKE 回退）；
 *   - v2（Phase 3，单事务迁移）：segments（动态重建段）、favorites（收藏）、
 *     notes（备注）、capture_imports（备份导入幂等台账）。
 * - 图像文件存 filesDir/capture/shots/（相对路径入库；出库经
 *   [evidenceFile] 做根目录逃逸校验，不透明引用规则与 WM-03 一致）；
 * - 去重键：(capture_time, package_name, dhash) 唯一索引——迁移重放/双写幂等；
 * - segment_id 形如 `segment_<ULID>`，证据引用为 `capture:segment_<ULID>`
 *   （contracts/v2 §9/§10 示例形态）；
 * - 隐私：本库为 owner_sensitive，永不进任何群聊/上行路径（上行只有
 *   经 adapter 裁剪的条目或影子统计）。
 */
class CaptureStore(
    context: Context,
    dbName: String = DEFAULT_DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

    private val appContext: Context = context.applicationContext

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
        /** 入库时间（备份导出保留原始时间戳用；仅读取时回填）。 */
        val createdAt: Long = 0,
    )

    /** 动态段（Phase 3）：连续同包名截图聚合。evidence 为成员证据引用列表。 */
    data class Segment(
        val segmentId: String,
        val packageName: String,
        val startedAt: Long,
        val endedAt: Long,
        val summary: String?,
        val evidence: List<String>,
        val memberCount: Int,
        val closed: Boolean,
    )

    /** 收藏（截图或段，二选一必填）。 */
    data class Favorite(
        val id: Long,
        val captureId: Long?,
        val segmentId: String?,
        val note: String?,
        val createdAt: Long,
    )

    /** 备注（挂在截图或段上）。 */
    data class Note(
        val id: Long,
        val captureId: Long?,
        val segmentId: String?,
        val text: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** 存储占用统计（storage 页面用；不含任何内容）。 */
    data class StorageStats(
        val captures: Long,
        val segments: Long,
        val favorites: Long,
        val notes: Long,
        val imageFiles: Int,
        val imageBytes: Long,
    )

    /** 清理报告。 */
    data class PurgeReport(
        val rowsDeleted: Int,
        val filesDeleted: Int,
        val bytesFreed: Long,
        val favoritesKept: Int,
    )

    /** 压缩报告。 */
    data class CompressReport(
        val filesCompressed: Int,
        val bytesBefore: Long,
        val bytesAfter: Long,
        val skipped: Int,
    )

    /** FTS5 是否可用（部分 OEM/测试环境的 SQLite 构建无 FTS5 → LIKE 全回退）。 */
    @Volatile
    var fts5Available: Boolean = true
        private set

    // ------------------------------------------------------------- 建库 / 迁移

    override fun onCreate(db: SQLiteDatabase) {
        createScreenshotsV1(db)
        createPhase3V2(db)
    }

    /**
     * v1 → v2（Phase 3）：新增 segments/favorites/notes/capture_imports。
     * SQLiteOpenHelper 的 onUpgrade 本就在单事务内执行，整批 DDL 原子生效；
     * v1 既有数据（screenshots/fts）不动。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createPhase3V2(db)
        }
    }

    private fun createScreenshotsV1(db: SQLiteDatabase) {
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
            Log.w(TAG, "FTS5 不可用，降级 LIKE 检索")
            false
        }
    }

    /** Phase 3 新表（onCreate 与 onUpgrade 共用；IF NOT EXISTS 保证幂等）。 */
    private fun createPhase3V2(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS segments (
              segment_id   TEXT PRIMARY KEY,
              package_name TEXT NOT NULL,
              started_at   INTEGER NOT NULL,
              ended_at     INTEGER NOT NULL,
              summary      TEXT,
              evidence     TEXT NOT NULL DEFAULT '[]',
              member_count INTEGER NOT NULL DEFAULT 0,
              closed       INTEGER NOT NULL DEFAULT 0,
              created_at   INTEGER NOT NULL,
              updated_at   INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_segments_time ON segments(started_at DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorites (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              capture_id  INTEGER,
              segment_id  TEXT,
              note        TEXT,
              created_at  INTEGER NOT NULL,
              CHECK (capture_id IS NOT NULL OR segment_id IS NOT NULL)
            )
            """.trimIndent(),
        )
        // 同一目标只许收藏一次（部分唯一索引规避 SQLite NULL 不等于 NULL 的语义）
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_fav_capture ON favorites(capture_id)" +
                " WHERE capture_id IS NOT NULL",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_fav_segment ON favorites(segment_id)" +
                " WHERE segment_id IS NOT NULL",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              capture_id  INTEGER,
              segment_id  TEXT,
              text        TEXT NOT NULL,
              created_at  INTEGER NOT NULL,
              updated_at  INTEGER NOT NULL,
              CHECK (capture_id IS NOT NULL OR segment_id IS NOT NULL)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_capture ON notes(capture_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_segment ON notes(segment_id)")
        // 备份导入幂等台账：backup_hash 唯一，重复导入整包跳过
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS capture_imports (
              backup_hash TEXT PRIMARY KEY,
              imported_at INTEGER NOT NULL,
              id_map      TEXT NOT NULL DEFAULT '{}',
              report      TEXT
            )
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------- 截图（v1 既有）

    /**
     * 插入截图记录（幂等：去重键冲突返回 -1）。OCR 文本同步进 FTS。
     * [createdAtOverride] 仅供迁移/备份导入保留原始时间戳。
     */
    @Synchronized
    fun insert(row: Row, createdAtOverride: Long? = null): Long {
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
                put(
                    "created_at",
                    createdAtOverride
                        ?: row.createdAt.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                )
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
    fun getById(id: Long): Row? = readableDatabase.query(
        "screenshots", null, "id = ?", arrayOf(id.toString()), null, null, null,
    ).use { c -> if (c.moveToFirst()) readRow(c) else null }

    /** 按去重键找行 id（备份导入冲突时把关联映射到已存在的目标行）。 */
    @Synchronized
    fun findIdByKey(captureTime: Long, packageName: String, dhash: Long): Long? =
        readableDatabase.rawQuery(
            "SELECT id FROM screenshots WHERE capture_time=? AND package_name=? AND dhash=?",
            arrayOf(captureTime.toString(), packageName, dhash.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** 全量/区间列出截图（备份导出用；时间升序，勿用于 UI 大列表）。 */
    @Synchronized
    fun listCaptures(sinceMs: Long? = null, untilMs: Long? = null): List<Row> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (sinceMs != null) { clauses.add("capture_time >= ?"); args.add(sinceMs.toString()) }
        if (untilMs != null) { clauses.add("capture_time <= ?"); args.add(untilMs.toString()) }
        val where = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
        return readableDatabase.query(
            "screenshots", null, where, args.toTypedArray(), null, null,
            "capture_time ASC",
        ).use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }
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

    // ------------------------------------------------------------- 段（Phase 3）

    /** 开新段（segment_id 已存在则忽略，返回 false）。 */
    @Synchronized
    fun openSegment(segmentId: String, packageName: String, startedAt: Long, summary: String?): Boolean {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insertWithOnConflict(
            "segments", null,
            ContentValues().apply {
                put("segment_id", segmentId)
                put("package_name", packageName)
                put("started_at", startedAt)
                put("ended_at", startedAt)
                put("summary", summary)
                put("evidence", "[]")
                put("member_count", 0)
                put("closed", 0)
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return id >= 0
    }

    /**
     * 向段追加一帧证据：evidence JSON 追加、ended_at 前移、member_count+1；
     * 摘要为空时用首条非空 OCR 候选补齐。返回更新后的段。
     */
    @Synchronized
    fun appendSegmentEvidence(
        segmentId: String,
        evidenceRef: String,
        frameTimeMs: Long,
        summaryCandidate: String?,
    ): Segment? {
        val seg = getSegment(segmentId) ?: return null
        val newEvidence = (seg.evidence + evidenceRef)
        val newSummary = if (seg.summary.isNullOrBlank()) {
            summaryCandidate?.lineSequence()?.firstOrNull { it.isNotBlank() }
                ?.take(MAX_SEGMENT_SUMMARY)
        } else {
            seg.summary
        }
        writableDatabase.update(
            "segments",
            ContentValues().apply {
                put("ended_at", maxOf(seg.endedAt, frameTimeMs))
                put("summary", newSummary)
                put("evidence", JSONArray(newEvidence).toString())
                put("member_count", seg.memberCount + 1)
                put("updated_at", System.currentTimeMillis())
            },
            "segment_id = ?", arrayOf(segmentId),
        )
        return getSegment(segmentId)
    }

    /** 封口段（closed=1）。返回封口后的段；已封口/不存在返回 null（不重复产出事件）。 */
    @Synchronized
    fun closeSegment(segmentId: String): Segment? {
        val seg = getSegment(segmentId) ?: return null
        if (seg.closed) return null
        writableDatabase.update(
            "segments",
            ContentValues().apply {
                put("closed", 1)
                put("updated_at", System.currentTimeMillis())
            },
            "segment_id = ?", arrayOf(segmentId),
        )
        return getSegment(segmentId)
    }

    /** 当前进行中的段（最近一个未封口段）。 */
    @Synchronized
    fun currentOpenSegment(): Segment? = readableDatabase.rawQuery(
        "SELECT * FROM segments WHERE closed = 0 ORDER BY started_at DESC LIMIT 1",
        null,
    ).use { c -> if (c.moveToFirst()) readSegment(c) else null }

    @Synchronized
    fun getSegment(segmentId: String): Segment? = readableDatabase.query(
        "segments", null, "segment_id = ?", arrayOf(segmentId), null, null, null,
    ).use { c -> if (c.moveToFirst()) readSegment(c) else null }

    @Synchronized
    fun listSegments(limit: Int = 50, sinceMs: Long? = null): List<Segment> {
        val where = if (sinceMs != null) "started_at >= ?" else null
        val args = if (sinceMs != null) arrayOf(sinceMs.toString()) else null
        return readableDatabase.query(
            "segments", null, where, args, null, null,
            "started_at DESC", limit.toString(),
        ).use { c ->
            val out = mutableListOf<Segment>()
            while (c.moveToNext()) out.add(readSegment(c))
            out
        }
    }

    /** 备份导入用：按原始字段整体写入段（幂等：segment_id 冲突跳过，返回 false）。 */
    @Synchronized
    fun insertImportedSegment(seg: Segment): Boolean {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insertWithOnConflict(
            "segments", null,
            ContentValues().apply {
                put("segment_id", seg.segmentId)
                put("package_name", seg.packageName)
                put("started_at", seg.startedAt)
                put("ended_at", seg.endedAt)
                put("summary", seg.summary)
                put("evidence", JSONArray(seg.evidence).toString())
                put("member_count", seg.memberCount)
                put("closed", if (seg.closed) 1 else 0)
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return id >= 0
    }

    // ------------------------------------------------------------- 收藏（Phase 3）

    /** 收藏截图或段（二选一）。重复收藏同一目标返回 -1（幂等）。 */
    @Synchronized
    fun addFavorite(
        captureId: Long? = null,
        segmentId: String? = null,
        note: String? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        require(captureId != null || segmentId != null) { "收藏目标必填其一" }
        return writableDatabase.insertWithOnConflict(
            "favorites", null,
            ContentValues().apply {
                captureId?.let { put("capture_id", it) }
                segmentId?.let { put("segment_id", it) }
                note?.let { put("note", it) }
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    @Synchronized
    fun removeFavorite(captureId: Long? = null, segmentId: String? = null): Int {
        val (where, args) = when {
            captureId != null -> "capture_id = ?" to arrayOf(captureId.toString())
            segmentId != null -> "segment_id = ?" to arrayOf(segmentId)
            else -> return 0
        }
        return writableDatabase.delete("favorites", where, args)
    }

    @Synchronized
    fun listFavorites(): List<Favorite> = readableDatabase.query(
        "favorites", null, null, null, null, null, "created_at DESC",
    ).use { c ->
        val out = mutableListOf<Favorite>()
        while (c.moveToNext()) out.add(readFavorite(c))
        out
    }

    @Synchronized
    fun isFavoriteCapture(captureId: Long): Boolean = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM favorites WHERE capture_id = $captureId",
    ).use { it.simpleQueryForLong() > 0 }

    /** 受收藏保护的截图 id 集（含收藏段的成员帧；清理/压缩跳过）。 */
    @Synchronized
    fun protectedCaptureIds(): Set<Long> {
        val out = mutableSetOf<Long>()
        readableDatabase.rawQuery(
            "SELECT capture_id FROM favorites WHERE capture_id IS NOT NULL", null,
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        readableDatabase.rawQuery(
            "SELECT evidence FROM favorites f JOIN segments s ON s.segment_id = f.segment_id" +
                " WHERE f.segment_id IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.addAll(parseEvidence(c.getString(0)).mapNotNull { evidenceRefToRowId(it) })
            }
        }
        return out
    }

    // ------------------------------------------------------------- 备注（Phase 3）

    @Synchronized
    fun addNote(
        captureId: Long? = null,
        segmentId: String? = null,
        text: String,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        require(captureId != null || segmentId != null) { "备注关联目标必填其一" }
        require(text.isNotBlank()) { "备注文本不能为空" }
        return writableDatabase.insert(
            "notes", null,
            ContentValues().apply {
                captureId?.let { put("capture_id", it) }
                segmentId?.let { put("segment_id", it) }
                put("text", text)
                put("created_at", createdAt)
                put("updated_at", createdAt)
            },
        )
    }

    @Synchronized
    fun updateNote(id: Long, text: String): Boolean {
        require(text.isNotBlank()) { "备注文本不能为空" }
        return writableDatabase.update(
            "notes",
            ContentValues().apply {
                put("text", text)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(id.toString()),
        ) > 0
    }

    @Synchronized
    fun deleteNote(id: Long): Boolean =
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString())) > 0

    /** 全量或按关联目标过滤（两参数都 null = 全量）。 */
    @Synchronized
    fun listNotes(captureId: Long? = null, segmentId: String? = null): List<Note> {
        val (where, args) = when {
            captureId != null -> "capture_id = ?" to arrayOf(captureId.toString())
            segmentId != null -> "segment_id = ?" to arrayOf(segmentId)
            else -> null to null
        }
        return readableDatabase.query(
            "notes", null, where, args, null, null, "created_at ASC",
        ).use { c ->
            val out = mutableListOf<Note>()
            while (c.moveToNext()) out.add(readNote(c))
            out
        }
    }

    /** 导入去重：同目标同文本同原始创建时间的备注视为重复。 */
    @Synchronized
    fun noteExists(captureId: Long?, segmentId: String?, text: String, createdAt: Long): Boolean {
        val where = StringBuilder("text = ? AND created_at = ?")
        val args = mutableListOf(text, createdAt.toString())
        if (captureId != null) { where.append(" AND capture_id = ?"); args.add(captureId.toString()) }
        if (segmentId != null) { where.append(" AND segment_id = ?"); args.add(segmentId) }
        return readableDatabase.query(
            "notes", arrayOf("id"), where.toString(), args.toTypedArray(),
            null, null, null, "1",
        ).use { it.moveToFirst() }
    }

    // ------------------------------------------------------------- 导入台账（Phase 3）

    /** 备份包是否已导入过（幂等检测）。 */
    @Synchronized
    fun hasImport(backupHash: String): Boolean = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM capture_imports WHERE backup_hash = ?",
    ).apply { bindString(1, backupHash) }.use { it.simpleQueryForLong() > 0 }

    /** 记录一次导入（源记录 ID→目标 ID 映射 + 报告摘要）；重复返回 false。 */
    @Synchronized
    fun recordImport(backupHash: String, idMap: Map<String, String>, report: String): Boolean {
        val json = org.json.JSONObject()
        idMap.forEach { (k, v) -> json.put(k, v) }
        return writableDatabase.insertWithOnConflict(
            "capture_imports", null,
            ContentValues().apply {
                put("backup_hash", backupHash)
                put("imported_at", System.currentTimeMillis())
                put("id_map", json.toString())
                put("report", report)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        ) >= 0
    }

    // ------------------------------------------------------------- 存储治理（Phase 3）

    /** 占用统计（截图/段/收藏/备注行数 + 图像文件数与字节数）。 */
    @Synchronized
    fun storageStats(): StorageStats {
        fun countOf(table: String): Long = readableDatabase.compileStatement(
            "SELECT COUNT(*) FROM $table",
        ).use { it.simpleQueryForLong() }
        var files = 0
        var bytes = 0L
        readableDatabase.rawQuery(
            "SELECT image_path FROM screenshots WHERE image_path != ''", null,
        ).use { c ->
            val root = File(appContext.filesDir, SHOTS_DIR_RELATIVE)
            while (c.moveToNext()) {
                val f = File(root, c.getString(0))
                if (f.isFile) { files++; bytes += f.length() }
            }
        }
        return StorageStats(countOf("screenshots"), countOf("segments"),
            countOf("favorites"), countOf("notes"), files, bytes)
    }

    /**
     * 按时间清理：删除 capture_time < [cutoffMs] 的截图行与其图像文件。
     * [keepFavorites]=true 时跳过被收藏（或属于收藏段）的帧；行删除同步清 FTS。
     */
    @Synchronized
    fun purgeBefore(cutoffMs: Long, keepFavorites: Boolean = true): PurgeReport {
        val protected_ = if (keepFavorites) protectedCaptureIds() else emptySet()
        val victims = readableDatabase.rawQuery(
            "SELECT * FROM screenshots WHERE capture_time < ? ORDER BY capture_time ASC",
            arrayOf(cutoffMs.toString()),
        ).use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }.filter { it.id !in protected_ }
        // 被收藏保护而保留的过期帧数
        val totalExpired = readableDatabase.compileStatement(
            "SELECT COUNT(*) FROM screenshots WHERE capture_time < $cutoffMs",
        ).use { it.simpleQueryForLong() }.toInt()
        return purgeRows(victims, protectedKept = totalExpired - victims.size)
    }

    /**
     * 按占用清理：从最旧的非保护帧开始删，直到图像总字节 ≤ [maxBytes]。
     */
    @Synchronized
    fun enforceMaxImageBytes(maxBytes: Long, keepFavorites: Boolean = true): PurgeReport {
        val stats = storageStats()
        if (stats.imageBytes <= maxBytes) return PurgeReport(0, 0, 0, 0)
        val protected_ = if (keepFavorites) protectedCaptureIds() else emptySet()
        val all = readableDatabase.rawQuery(
            "SELECT * FROM screenshots WHERE image_path != '' ORDER BY capture_time ASC",
            null,
        ).use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }
        var bytes = stats.imageBytes
        val victims = mutableListOf<Row>()
        for (row in all) {
            if (bytes <= maxBytes) break
            if (row.id in protected_) continue
            val f = evidenceFile(appContext, row) ?: continue
            victims.add(row)
            bytes -= f.length()
        }
        return purgeRows(victims, protectedKept = 0)
    }

    private fun purgeRows(victims: List<Row>, protectedKept: Int): PurgeReport {
        if (victims.isEmpty()) return PurgeReport(0, 0, 0, protectedKept)
        val db = writableDatabase
        var filesDeleted = 0
        var bytesFreed = 0L
        db.beginTransaction()
        try {
            for (row in victims) {
                evidenceFile(appContext, row)?.let { f ->
                    val len = f.length()
                    if (f.delete()) { filesDeleted++; bytesFreed += len }
                }
                db.delete("screenshots", "id = ?", arrayOf(row.id.toString()))
                if (fts5Available) {
                    db.execSQL("DELETE FROM screenshots_fts WHERE rowid = ${row.id}")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return PurgeReport(victims.size, filesDeleted, bytesFreed, protectedKept)
    }

    /**
     * JPEG 质量阶梯压缩：对 [cutoffMs] 之前的非保护图像，按
     * （缩到 [maxSide] 边长内 + 降 [quality]）重编码；仅在变小时替换。
     */
    @Synchronized
    fun compressImagesBefore(
        cutoffMs: Long,
        quality: Int = 50,
        maxSide: Int = 1280,
        keepFavorites: Boolean = true,
    ): CompressReport {
        val protected_ = if (keepFavorites) protectedCaptureIds() else emptySet()
        val rows = readableDatabase.rawQuery(
            "SELECT * FROM screenshots WHERE capture_time < ? AND image_path != ''" +
                " ORDER BY capture_time ASC",
            arrayOf(cutoffMs.toString()),
        ).use { c ->
            val out = mutableListOf<Row>()
            while (c.moveToNext()) out.add(readRow(c))
            out
        }
        var compressed = 0
        var before = 0L
        var after = 0L
        var skipped = 0
        for (row in rows) {
            if (row.id in protected_) { skipped++; continue }
            val f = evidenceFile(appContext, row)
            if (f == null) { skipped++; continue }
            val oldLen = f.length()
            val src = try {
                BitmapFactory.decodeFile(f.path)
            } catch (t: Throwable) { null }
            if (src == null) { skipped++; continue }
            try {
                val scaled = scaleToMaxSide(src, maxSide)
                val tmp = File(f.parentFile, f.name + ".tmp")
                val ok = try {
                    FileOutputStream(tmp).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                    true
                } catch (t: Throwable) { false }
                if (scaled !== src) scaled.recycle()
                if (ok && tmp.isFile && tmp.length() < oldLen) {
                    if (f.delete() && tmp.renameTo(f)) {
                        compressed++; before += oldLen; after += f.length()
                    } else {
                        tmp.delete(); skipped++
                    }
                } else {
                    tmp.delete(); skipped++
                }
            } finally {
                src.recycle()
            }
        }
        return CompressReport(compressed, before, after, skipped)
    }

    private fun scaleToMaxSide(bmp: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bmp.width, bmp.height)
        if (max <= maxSide) return bmp
        val ratio = maxSide.toFloat() / max
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ------------------------------------------------------------- 行读取

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
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    )

    private fun readSegment(c: android.database.Cursor): Segment = Segment(
        segmentId = c.getString(c.getColumnIndexOrThrow("segment_id")),
        packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.getLong(c.getColumnIndexOrThrow("ended_at")),
        summary = c.getString(c.getColumnIndexOrThrow("summary")),
        evidence = parseEvidence(c.getString(c.getColumnIndexOrThrow("evidence"))),
        memberCount = c.getInt(c.getColumnIndexOrThrow("member_count")),
        closed = c.getInt(c.getColumnIndexOrThrow("closed")) != 0,
    )

    private fun readFavorite(c: android.database.Cursor): Favorite = Favorite(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        captureId = c.getColumnIndexOrThrow("capture_id").let {
            if (c.isNull(it)) null else c.getLong(it)
        },
        segmentId = c.getString(c.getColumnIndexOrThrow("segment_id")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    )

    private fun readNote(c: android.database.Cursor): Note = Note(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        captureId = c.getColumnIndexOrThrow("capture_id").let {
            if (c.isNull(it)) null else c.getLong(it)
        },
        segmentId = c.getString(c.getColumnIndexOrThrow("segment_id")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    companion object {
        private const val TAG = "WhaleCaptureStore"
        private const val DB_VERSION = 2
        const val DEFAULT_DB_NAME = "whale_capture.db"
        const val SHOTS_DIR_RELATIVE = "capture/shots"

        /** 段摘要最大长度（首条非空 OCR 行截断）。 */
        const val MAX_SEGMENT_SUMMARY = 120

        /** 含 CJK 的查询用 LIKE 回退（unicode61 不分中文词）。 */
        fun needsLikeFallback(query: String): Boolean =
            query.any { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }

        /** FTS5 查询转义：每个词加双引号（防运算符注入）。 */
        fun ftsSanitize(query: String): String =
            query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .joinToString(" ") { "\"${it.replace("\"", "")}\"" }

        /** evidence JSON 数组解析（容错：坏 JSON 返回空表）。 */
        fun parseEvidence(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { arr.optString(it, null) }
            } catch (t: Throwable) {
                emptyList()
            }
        }

        /** `capture:<rowId>` 形态证据引用 → 截图行 id（非该形态返回 null）。 */
        fun evidenceRefToRowId(ref: String): Long? =
            ref.removePrefix("capture:").toLongOrNull()
                ?.takeIf { ref.startsWith("capture:") && !ref.contains("segment_") }
    }
}
