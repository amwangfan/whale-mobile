package com.wangfanlab.whale.modules.capture.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.wangfanlab.whale.modules.capture.CaptureStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ScreenMemo 数据迁移工具（SM-02，Issue #9；只读旧库，绝不原地改写）。
 *
 * 流程（全部可回滚）：
 * 1. **扫描**：在 [baseDir] 下找 `databases/`（ScreenMemo 分片库，表
 *    `screenshots_YYYYMM`）与 `output/` 图像树；校验版本与完整性
 *    （缺表/不可读 → 拒绝并说明）；
 * 2. **备份**：先把待导入分库复制到 filesDir/capture/import_backup/（校验后再动）；
 * 3. **增量导入**：每分库单事务批量插入；checkpoint（whale_capture/import_checkpoint）
 *    记录每分库已导入的最大 capture_time，中断续跑不重复；
 * 4. **去重**：落 [CaptureStore] 的去重键（capture_time+package+dhash）幂等；
 * 5. **切换**：导入完成后由 SM-03 的 feature flag 切换数据源，本类不改任何开关。
 *
 * 隐私：OCR 原文只进本机 CaptureStore，迁移日志不含内容。
 */
class ScreenMemoImporter(
    private val context: Context,
    private val store: CaptureStore,
) {

    data class ImportReport(
        val shardsScanned: Int,
        val rowsImported: Int,
        val rowsSkippedDuplicate: Int,
        val rowsSkippedDeleted: Int,
        val errors: List<String>,
        val backupDir: String,
        /** Phase 3：旧库收藏/备注映射入库数（旧库无对应表时为 0）。 */
        val favoritesImported: Int = 0,
        val notesImported: Int = 0,
    )

    data class Validation(
        val ok: Boolean,
        val reason: String?,
        val shardCount: Int,
    )

    /** 扫描并校验源目录（不写任何数据）。 */
    fun validate(baseDir: File): Validation {
        if (!baseDir.isDirectory) return Validation(false, "目录不存在: ${baseDir.path}", 0)
        val dbDir = File(baseDir, "databases")
        if (!dbDir.isDirectory) return Validation(false, "缺少 databases/ 子目录", 0)
        val shards = findShards(dbDir)
        if (shards.isEmpty()) return Validation(false, "未找到 ScreenMemo 分片库", 0)
        // 抽样打开第一个分库校验表结构
        val sample = shards.first()
        return try {
            SQLiteDatabase.openDatabase(sample.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val hasMonthTable = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND" +
                        " (name LIKE 'shots_%' OR name LIKE 'screenshots_%') AND name NOT LIKE '%_fts%'",
                    null,
                ).use { it.moveToFirst() }
                if (!hasMonthTable) {
                    Validation(false, "分库 ${sample.name} 无 screenshots_YYYYMM 表（版本未知）", shards.size)
                } else {
                    Validation(true, null, shards.size)
                }
            }
        } catch (t: Throwable) {
            Validation(false, "分库不可读: ${t.message}", shards.size)
        }
    }

    /**
     * 执行增量导入（备份→逐分库事务导入→checkpoint）。
     * @param baseDir 含 databases/ 与 output/ 的导出根目录
     */
    fun import(baseDir: File): ImportReport {
        val validation = validate(baseDir)
        val errors = mutableListOf<String>()
        if (!validation.ok) {
            return ImportReport(0, 0, 0, 0, listOfNotNull(validation.reason), "")
        }
        val backupDir = File(context.filesDir, "capture/import_backup")
        backupDir.mkdirs()
        val shards = findShards(File(baseDir, "databases"))
        var imported = 0
        var dup = 0
        var deleted = 0
        var favs = 0
        var notesCnt = 0
        for (shard in shards) {
            try {
                // 备份先行（可复制才导入）
                val backup = File(backupDir, shard.name)
                FileInputStream(shard).use { inp ->
                    FileOutputStream(backup).use { out -> inp.copyTo(out) }
                }
                val pkg = packageOfShard(shard)
                val cpKey = "checkpoint_${shard.name}"
                val prefs = context.getSharedPreferences(IMPORT_PREFS, Context.MODE_PRIVATE)
                val lastImported = prefs.getLong(cpKey, 0L)
                var maxSeen = lastImported
                SQLiteDatabase.openDatabase(shard.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val tables = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND" +
                            " (name LIKE 'shots_%' OR name LIKE 'screenshots_%')" +
                            " AND name NOT LIKE '%_fts%' AND name != 'android_metadata' AND name != 'sqlite_sequence'",
                        null,
                    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
                    // Phase 3：源记录 → 目标行 id 映射（收藏/备注关联保留用；
                    // 含 checkpoint 之前的既有行，靠去重键反查补齐）
                    val srcByTime = mutableMapOf<Long, Long>()
                    val srcByPath = mutableMapOf<String, Long>()
                    store.writableDatabase.beginTransaction()
                    try {
                        for (table in tables) {
                            val cursor = db.rawQuery(
                                "SELECT capture_time, file_path, ocr_text, is_deleted FROM $table" +
                                    " WHERE capture_time > ? ORDER BY capture_time ASC",
                                arrayOf(lastImported.toString()),
                            )
                            cursor.use { c ->
                                while (c.moveToNext()) {
                                    val ts = c.getLong(0)
                                    if (ts > maxSeen) maxSeen = ts
                                    if (c.getInt(3) != 0) { deleted++; continue } // 尊重旧库删除语义
                                    val ocrText = if (c.isNull(2)) null else c.getString(2)
                                    val id = store.insert(
                                        CaptureStore.Row(
                                            id = 0,
                                            captureTime = ts,
                                            packageName = pkg,
                                            appLabel = pkg,
                                            dhash = 0L, // 旧库无 dhash；迁移期以 0 占位（检索不依赖）
                                            width = 0, height = 0,
                                            ocrStatus = if (ocrText.isNullOrBlank()) "failed" else "ok",
                                            ocrText = ocrText,
                                            imagePath = "", // 迁移条目无本地图像（可选后续拷贝）
                                        ),
                                    )
                                    val targetId = if (id >= 0) {
                                        imported++
                                        id
                                    } else {
                                        dup++
                                        // 冲突行：反查既有目标 id（收藏/备注映射不丢关联）
                                        store.findIdByKey(ts, pkg, 0L)
                                    }
                                    if (targetId != null) {
                                        srcByTime[ts] = targetId
                                        if (!c.isNull(1)) srcByPath[c.getString(1)] = targetId
                                    }
                                }
                            }
                        }
                        // Phase 3：旧库若有收藏/备注表则一并映射（无则跳过，兼容旧版导出）
                        favs += importLegacyFavorites(db, srcByTime, srcByPath)
                        notesCnt += importLegacyNotes(db, srcByTime, srcByPath)
                        store.writableDatabase.setTransactionSuccessful()
                    } finally {
                        store.writableDatabase.endTransaction()
                    }
                }
                prefs.edit().putLong(cpKey, maxSeen).apply()
                Log.i(TAG, "分库 ${shard.name} 导入完成（imported 累计 $imported）")
            } catch (t: Throwable) {
                Log.w(TAG, "分库 ${shard.name} 导入失败", t)
                errors.add("${shard.name}: ${t.message}")
            }
        }
        return ImportReport(
            shards.size, imported, dup, deleted, errors, backupDir.path,
            favoritesImported = favs, notesImported = notesCnt,
        )
    }

    // ------------------------------------------------------------- Phase 3：收藏/备注映射

    /** 旧分库是否存在某表。 */
    private fun hasTable(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    /** 表列名集合（列名嗅探，兼容旧版 schema 差异）。 */
    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val out = mutableSetOf<String>()
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(idx))
            out
        }

    /** 源行 → 目标截图 id：优先 capture_time 列，其次 file_path 列。 */
    private fun resolveTarget(
        c: android.database.Cursor,
        cols: Set<String>,
        srcByTime: Map<Long, Long>,
        srcByPath: Map<String, Long>,
    ): Long? {
        if ("capture_time" in cols) {
            val idx = c.getColumnIndex("capture_time")
            if (!c.isNull(idx)) srcByTime[c.getLong(idx)]?.let { return it }
        }
        if ("file_path" in cols) {
            val idx = c.getColumnIndex("file_path")
            if (!c.isNull(idx)) srcByPath[c.getString(idx)]?.let { return it }
        }
        return null
    }

    private fun pickText(c: android.database.Cursor, cols: Set<String>, vararg names: String): String? {
        for (n in names) {
            if (n in cols) {
                val idx = c.getColumnIndex(n)
                if (!c.isNull(idx)) return c.getString(idx)
            }
        }
        return null
    }

    /**
     * 旧库收藏表（favorites）→ 新 favorites。幂等靠收藏的 capture 唯一索引；
     * 无法映射到已导入截图的行跳过（返回导入数）。
     */
    private fun importLegacyFavorites(
        db: SQLiteDatabase,
        srcByTime: Map<Long, Long>,
        srcByPath: Map<String, Long>,
    ): Int {
        if (!hasTable(db, "favorites")) return 0
        val cols = columnsOf(db, "favorites")
        if ("capture_time" !in cols && "file_path" !in cols) {
            Log.w(TAG, "旧库 favorites 表无 capture_time/file_path 列，跳过（列=$cols）")
            return 0
        }
        var n = 0
        db.rawQuery("SELECT * FROM favorites", null).use { c ->
            while (c.moveToNext()) {
                val target = resolveTarget(c, cols, srcByTime, srcByPath) ?: continue
                val note = pickText(c, cols, "note", "text", "content")
                val createdAt = pickText(c, cols, "created_at")?.toLongOrNull()
                    ?: System.currentTimeMillis()
                if (store.addFavorite(captureId = target, note = note, createdAt = createdAt) >= 0) n++
            }
        }
        return n
    }

    /**
     * 旧库备注表（notes）→ 新 notes。幂等靠同目标同文同创建时间查重；
     * 无法映射的行跳过（返回导入数）。
     */
    private fun importLegacyNotes(
        db: SQLiteDatabase,
        srcByTime: Map<Long, Long>,
        srcByPath: Map<String, Long>,
    ): Int {
        if (!hasTable(db, "notes")) return 0
        val cols = columnsOf(db, "notes")
        if ("capture_time" !in cols && "file_path" !in cols) {
            Log.w(TAG, "旧库 notes 表无 capture_time/file_path 列，跳过（列=$cols）")
            return 0
        }
        var n = 0
        db.rawQuery("SELECT * FROM notes", null).use { c ->
            while (c.moveToNext()) {
                val target = resolveTarget(c, cols, srcByTime, srcByPath) ?: continue
                val text = pickText(c, cols, "text", "content", "note") ?: continue
                if (text.isBlank()) continue
                val createdAt = pickText(c, cols, "created_at")?.toLongOrNull()
                    ?: System.currentTimeMillis()
                if (store.noteExists(target, null, text, createdAt)) continue
                store.addNote(captureId = target, text = text, createdAt = createdAt)
                n++
            }
        }
        return n
    }

    private fun findShards(dbDir: File): List<File> {
        // 真实布局（ScreenMemo 分片）：shards/<pkg_underscored>/<year>/smm_<pkg>_<year>.db；
        // 兼容平铺 *.db 导出。统一递归收集 .db 文件。
        val out = mutableListOf<File>()
        dbDir.walkTopDown().filter { it.isFile && it.extension == "db" }.toCollection(out)
        return out.sortedBy { it.path }
    }

    /** 从分库文件名推断包名（smm_com_dragon_read_2026 → com.dragon.read；启发式，文档化）。 */
    internal fun packageOfShard(shard: File): String {
        val base = shard.nameWithoutExtension
            .removePrefix("smm_")
            .removeSuffix("_" + shard.parentFile?.name.orEmpty()) // 去年份目录同名后缀
        return base.replace('_', '.')
    }

    companion object {
        private const val TAG = "WhaleSMImporter"
        private const val IMPORT_PREFS = "whale_capture"
    }
}
