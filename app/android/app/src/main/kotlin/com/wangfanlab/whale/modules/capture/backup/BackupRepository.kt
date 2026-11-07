package com.wangfanlab.whale.modules.capture.backup

import android.content.Context
import android.util.Log
import com.wangfanlab.whale.modules.capture.CaptureStore
import com.wangfanlab.whale.transport.v2.Ulid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 采集数据备份/恢复（Phase 3）：ZIP + manifest + JSONL。
 *
 * 导出结构：
 * ```
 * backup.zip
 * ├── manifest.json        格式/版本/backup_id/计数/文件清单（含 sha256）
 * └── data/
 *     ├── captures.jsonl   每行一条截图记录（含原始 capture_time/created_at）
 *     ├── segments.jsonl   动态段（segment_id 全局唯一，原样保留）
 *     ├── favorites.jsonl  收藏（capture_id 为导出侧 id，导入时经 id_map 重映射）
 *     ├── notes.jsonl      备注（同上）
 *     └── files/<图像相对路径>  截图文件（与清单 sha256 对应）
 * ```
 *
 * 导入纪律（规划 §3.4）：
 * - 幂等：manifest.backup_id 落 [CaptureStore.recordImport] 台账，同一备份包
 *   重复导入整包跳过；包内行级冲突（截图去重键 / segment_id / 收藏唯一索引 /
 *   备注同目标同文同时间）逐条跳过；
 * - 关联保留：源截图 id → 目标 id 的 id_map 同时回写 favorites/notes 引用与
 *   segments.evidence 内的 `capture:<id>` 引用；
 * - 时间戳保留：capture_time/created_at/updated_at 原样入库；
 * - 完整性：文件逐一对照 manifest sha256，校验失败不计入导入且不入库；
 * - 防 zip slip：条目名规范化校验，拒绝 `..`/绝对路径。
 */
class BackupRepository(
    private val context: Context,
    private val store: CaptureStore,
) {

    data class ExportReport(
        val zipFile: File,
        val backupId: String,
        val captures: Int,
        val segments: Int,
        val favorites: Int,
        val notes: Int,
        val files: Int,
        val bytes: Long,
    )

    data class ImportReport(
        val backupId: String,
        val capturesImported: Int,
        val segmentsImported: Int,
        val favoritesImported: Int,
        val notesImported: Int,
        val filesImported: Int,
        val rowsSkippedDuplicate: Int,
        val rowsFailed: Int,
        val checksumFailed: Int,
        val skippedDuplicateBackup: Boolean,
        val errors: List<String>,
    )

    // ------------------------------------------------------------- 导出

    /** 导出全量（或 [sinceMs] 起）数据到 [destZip]。 */
    fun exportZip(destZip: File, sinceMs: Long? = null): ExportReport {
        val backupId = "bk_" + Ulid.newUlid()
        val captures = store.listCaptures(sinceMs = sinceMs)
        val segments = store.listSegments(limit = Int.MAX_VALUE, sinceMs = null)
        val favorites = store.listFavorites()
        val notes = store.listNotes()

        val manifestFiles = JSONArray()
        var fileCount = 0
        var totalBytes = 0L
        destZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destZip)).use { zip ->
            // 1) 数据 JSONL
            putJsonl(zip, "data/captures.jsonl", captures.map { captureJson(it) })
            putJsonl(zip, "data/segments.jsonl", segments.map { segmentJson(it) })
            putJsonl(zip, "data/favorites.jsonl", favorites.map { favoriteJson(it) })
            putJsonl(zip, "data/notes.jsonl", notes.map { noteJson(it) })
            // 2) 图像文件 + 清单（sha256）
            for (row in captures) {
                if (row.imagePath.isBlank()) continue
                val f = store.evidenceFile(context, row) ?: continue
                val entryPath = "files/${row.imagePath}"
                val bytes = f.readBytes()
                zip.putNextEntry(ZipEntry(entryPath))
                zip.write(bytes)
                zip.closeEntry()
                manifestFiles.put(
                    JSONObject()
                        .put("path", entryPath)
                        .put("sha256", sha256Hex(bytes))
                        .put("size", bytes.size),
                )
                fileCount++
                totalBytes += bytes.size
            }
            // 3) manifest 放最后（导入侧先读到谁都行，导出侧便于边写边统计）
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", FORMAT_VERSION)
                .put("backup_id", backupId)
                .put("created_at", System.currentTimeMillis())
                .put(
                    "counts",
                    JSONObject()
                        .put("captures", captures.size)
                        .put("segments", segments.size)
                        .put("favorites", favorites.size)
                        .put("notes", notes.size)
                        .put("files", fileCount),
                )
                .put("files", manifestFiles)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        Log.i(TAG, "导出完成 $backupId captures=${captures.size} files=$fileCount")
        return ExportReport(
            destZip, backupId, captures.size, segments.size,
            favorites.size, notes.size, fileCount, totalBytes,
        )
    }

    // ------------------------------------------------------------- 导入

    /** 导入备份包；同一 backup_id 重复导入整包跳过（幂等）。 */
    fun importZip(zipFile: File): ImportReport {
        val errors = mutableListOf<String>()
        if (!zipFile.isFile) {
            return ImportReport("", 0, 0, 0, 0, 0, 0, 0, 0, false,
                listOf("备份包不存在: ${zipFile.path}"))
        }
        val tmpDir = File(context.cacheDir, "capture_backup_import_" + Ulid.newUlid())
        tmpDir.mkdirs()
        try {
            // 1) 解包（防 zip slip）
            val entries = unzip(zipFile, tmpDir)
            val manifestFile = entries["manifest.json"]
                ?: return ImportReport("", 0, 0, 0, 0, 0, 0, 1, 0, false,
                    listOf("缺少 manifest.json"))
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) {
                return ImportReport("", 0, 0, 0, 0, 0, 0, 1, 0, false,
                    listOf("format 不匹配: ${manifest.optString("format")}"))
            }
            if (manifest.optInt("version") > FORMAT_VERSION) {
                return ImportReport("", 0, 0, 0, 0, 0, 0, 1, 0, false,
                    listOf("备份版本过新: ${manifest.optInt("version")}"))
            }
            val backupId = manifest.optString("backup_id").ifBlank {
                sha256Hex(manifestFile.readBytes())
            }
            // 2) 整包幂等
            if (store.hasImport(backupId)) {
                return ImportReport(backupId, 0, 0, 0, 0, 0, 0, 0, 0, true, emptyList())
            }
            // 3) 文件校验 + 落位
            var filesImported = 0
            var checksumFailed = 0
            val shotsRoot = File(context.filesDir, CaptureStore.SHOTS_DIR_RELATIVE)
            val okFiles = mutableSetOf<String>() // 清单内校验通过的 entry 名
            val filesArr = manifest.optJSONArray("files") ?: JSONArray()
            for (i in 0 until filesArr.length()) {
                val fe = filesArr.getJSONObject(i)
                val path = fe.optString("path")
                val src = entries[path]
                if (src == null) { checksumFailed++; continue }
                val bytes = src.readBytes()
                if (!sha256Hex(bytes).equals(fe.optString("sha256"), ignoreCase = true)) {
                    checksumFailed++
                    Log.w(TAG, "文件校验失败: $path")
                    continue
                }
                // 落位到 shots 根下（entry 形如 files/<relPath>；再次做逃逸校验）
                val rel = path.removePrefix("files/")
                val dest = File(shotsRoot, rel)
                if (!dest.canonicalPath.startsWith(shotsRoot.canonicalPath + File.separator)) {
                    checksumFailed++
                    continue
                }
                if (!dest.exists()) {
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(bytes)
                }
                okFiles.add(path)
                filesImported++
            }
            // 4) 记录导入（截图时间升序，构建 id_map）
            var capturesImported = 0
            var segmentsImported = 0
            var favoritesImported = 0
            var notesImported = 0
            var dup = 0
            var failed = 0
            val idMap = mutableMapOf<String, String>()
            val captures = readJsonl(entries["data/captures.jsonl"])
                .sortedBy { it.optLong("capture_time") }
            for (c in captures) {
                try {
                    val srcId = c.getLong("id")
                    val imagePath = c.optString("image_path", "")
                    // 图像缺失/校验失败的行仍入库，但清空图像引用（证据按需报告缺失）
                    val hasFile = imagePath.isNotBlank() && okFiles.contains("files/$imagePath")
                    val row = CaptureStore.Row(
                        id = 0,
                        captureTime = c.getLong("capture_time"),
                        packageName = c.getString("package_name"),
                        appLabel = c.optString("app_label").ifBlank { null },
                        dhash = c.getLong("dhash"),
                        width = c.optInt("width"),
                        height = c.optInt("height"),
                        ocrStatus = c.optString("ocr_status", "ok"),
                        ocrText = c.optString("ocr_text").ifBlank { null },
                        imagePath = if (hasFile) imagePath else "",
                    )
                    val newId = store.insert(
                        row,
                        createdAtOverride = c.optLong("created_at").takeIf { it > 0 },
                    )
                    if (newId >= 0) {
                        capturesImported++
                        idMap[srcId.toString()] = newId.toString()
                    } else {
                        // 行级去重：映射到既有目标行，关联记录仍可重映射
                        val existing = store.findIdByKey(row.captureTime, row.packageName, row.dhash)
                        if (existing != null) idMap[srcId.toString()] = existing.toString()
                        dup++
                    }
                } catch (t: Throwable) {
                    failed++
                    errors.add("capture 导入失败: ${t.message}")
                }
            }
            // 段：segment_id 全局唯一原样落库；evidence 内截图引用经 id_map 重映射
            for (s in readJsonl(entries["data/segments.jsonl"])) {
                try {
                    val evidence = CaptureStore.parseEvidence(s.optString("evidence"))
                        .map { ref ->
                            val srcRowId = CaptureStore.evidenceRefToRowId(ref)
                            val mapped = srcRowId?.let { idMap[it.toString()] }
                            if (mapped != null) "capture:$mapped" else ref
                        }
                    val ok = store.insertImportedSegment(
                        CaptureStore.Segment(
                            segmentId = s.getString("segment_id"),
                            packageName = s.getString("package_name"),
                            startedAt = s.getLong("started_at"),
                            endedAt = s.getLong("ended_at"),
                            summary = s.optString("summary").ifBlank { null },
                            evidence = evidence,
                            memberCount = s.optInt("member_count", evidence.size),
                            closed = s.optInt("closed", 1) != 0,
                        ),
                    )
                    if (ok) segmentsImported++ else dup++
                } catch (t: Throwable) {
                    failed++
                    errors.add("segment 导入失败: ${t.message}")
                }
            }
            // 收藏：capture_id 经 id_map 重映射；源目标缺失则跳过；唯一索引幂等
            for (f in readJsonl(entries["data/favorites.jsonl"])) {
                try {
                    val srcCaptureId = f.optLong("capture_id", -1L).takeIf { it > 0 }
                    val targetCapture = srcCaptureId?.let { idMap[it.toString()]?.toLongOrNull() }
                    val segmentId = f.optString("segment_id").ifBlank { null }
                    if (srcCaptureId != null && targetCapture == null) {
                        dup++ // 源截图不在本包/未映射（例如另一个包已导入过）→ 跳过
                        continue
                    }
                    val id = store.addFavorite(
                        captureId = targetCapture,
                        segmentId = segmentId,
                        note = f.optString("note").ifBlank { null },
                        createdAt = f.optLong("created_at", System.currentTimeMillis()),
                    )
                    if (id >= 0) favoritesImported++ else dup++
                } catch (t: Throwable) {
                    failed++
                    errors.add("favorite 导入失败: ${t.message}")
                }
            }
            // 备注：重映射 + 同目标同文同时间去重
            for (n in readJsonl(entries["data/notes.jsonl"])) {
                try {
                    val srcCaptureId = n.optLong("capture_id", -1L).takeIf { it > 0 }
                    val targetCapture = srcCaptureId?.let { idMap[it.toString()]?.toLongOrNull() }
                    val segmentId = n.optString("segment_id").ifBlank { null }
                    if (srcCaptureId != null && targetCapture == null) { dup++; continue }
                    val text = n.getString("text")
                    val createdAt = n.optLong("created_at", System.currentTimeMillis())
                    if (store.noteExists(targetCapture, segmentId, text, createdAt)) {
                        dup++
                        continue
                    }
                    store.addNote(targetCapture, segmentId, text, createdAt)
                    notesImported++
                } catch (t: Throwable) {
                    failed++
                    errors.add("note 导入失败: ${t.message}")
                }
            }
            // 5) 台账
            val report = ImportReport(
                backupId, capturesImported, segmentsImported, favoritesImported,
                notesImported, filesImported, dup, failed, checksumFailed, false, errors,
            )
            store.recordImport(
                backupId, idMap,
                "captures=$capturesImported segments=$segmentsImported" +
                    " favorites=$favoritesImported notes=$notesImported" +
                    " files=$filesImported dup=$dup failed=$failed checksumFailed=$checksumFailed",
            )
            return report
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- 内部

    private fun captureJson(r: CaptureStore.Row): JSONObject = JSONObject()
        .put("id", r.id)
        .put("capture_time", r.captureTime)
        .put("package_name", r.packageName)
        .put("app_label", r.appLabel)
        .put("dhash", r.dhash)
        .put("width", r.width)
        .put("height", r.height)
        .put("ocr_status", r.ocrStatus)
        .put("ocr_text", r.ocrText)
        .put("image_path", r.imagePath)
        .put("created_at", r.createdAt)

    private fun segmentJson(s: CaptureStore.Segment): JSONObject = JSONObject()
        .put("segment_id", s.segmentId)
        .put("package_name", s.packageName)
        .put("started_at", s.startedAt)
        .put("ended_at", s.endedAt)
        .put("summary", s.summary)
        .put("evidence", JSONArray(s.evidence))
        .put("member_count", s.memberCount)
        .put("closed", if (s.closed) 1 else 0)

    private fun favoriteJson(f: CaptureStore.Favorite): JSONObject = JSONObject()
        .put("capture_id", f.captureId)
        .put("segment_id", f.segmentId)
        .put("note", f.note)
        .put("created_at", f.createdAt)

    private fun noteJson(n: CaptureStore.Note): JSONObject = JSONObject()
        .put("capture_id", n.captureId)
        .put("segment_id", n.segmentId)
        .put("text", n.text)
        .put("created_at", n.createdAt)
        .put("updated_at", n.updatedAt)

    private fun putJsonl(zip: ZipOutputStream, name: String, rows: List<JSONObject>) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(rows.joinToString("\n") { it.toString() }.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readJsonl(f: File?): List<JSONObject> {
        if (f == null || !f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull {
                try {
                    JSONObject(it)
                } catch (t: Throwable) {
                    null
                }
            }
    }

    /** 解包到 [destDir]，返回 条目名 → 文件 映射；拒绝 zip slip 条目。 */
    private fun unzip(zipFile: File, destDir: File): Map<String, File> {
        val out = mutableMapOf<String, File>()
        val rootCanon = destDir.canonicalPath
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                val dest = File(destDir, name)
                if (!entry.isDirectory &&
                    dest.canonicalPath.startsWith(rootCanon + File.separator)
                ) {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { zip.copyTo(it) }
                    out[name] = dest
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "WhaleBackup"
        const val FORMAT = "whale_capture_backup"
        const val FORMAT_VERSION = 1
    }
}
