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
                                    if (id < 0) dup++ else imported++
                                }
                            }
                        }
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
        return ImportReport(shards.size, imported, dup, deleted, errors, backupDir.path)
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
