package com.wangfanlab.whale.transport

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.modules.capture.CaptureStore
import com.wangfanlab.whale.modules.capture.backup.BackupRepository
import com.wangfanlab.whale.modules.notification2.NotificationDeepLinkRouter
import com.wangfanlab.whale.modules.notification2.NotificationInbox
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter ↔ 本地数据查询面（v2 装配）——v1/v2 两条 MethodChannel 共享的实现。
 *
 * **通道约定以 app/lib/ 现有调用为准**（Flutter UI 已按 `whale/transport`
 * 通道消费这些方法；v2 桥 `whale/transport_v2` 同步暴露同一方法集）：
 * - 通知历史（features/notifications/inbox_page.dart）：
 *   - `notificationInbox.history` {limit?, offset?} → List<Map>
 *   - `notificationInbox.markRead` {notification_id} → {updated: int}
 *   - `notificationInbox.markAllRead` → {updated: int}
 *   - `notificationInbox.count` → {count: int}
 * - 采集查询（features/screenmemo/data 目录）：
 *   - `capture.listCaptures` {since_ms?, until_ms?, limit?} → List<Map>
 *   - `capture.listSegments` {limit?, since_ms?} → List<Map>
 *   - `capture.search` {query, limit?, since_ms?} → List<Map>
 *   - `capture.getEvidencePath` {ref} → {path}（根目录逃逸校验，路径不出设备）
 *   - `capture.storageStats` / `capture.purgeBefore` / `capture.compressImagesBefore`
 *   - `favorites.list` / `favorites.add` / `favorites.remove`
 *   - `backup.exportZip` {dest_dir, since_ms?} / `backup.importZip` {path}
 * - 深链（main.dart DeepLinkService）：`getLaunchRoute` → {route?, ...}
 *   （读取并消费启动 intent extras，避免重复路由）
 *
 * 重操作（备份/清理/压缩）在后台线程执行，结果切回主线程回执
 * （MethodChannel.Result 主线程纪律，与 v1 importScreenMemoData 同模式）。
 */
class BridgeDataQueries(
    context: Context,
    private val activityProvider: () -> Activity?,
    private val storeProvider: () -> CaptureStore,
    /** 通知收件箱由 v2 运行时持有；未就绪时历史类方法回空集/0（不崩 UI）。 */
    private val inboxProvider: () -> NotificationInbox?,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本桥是否处理该方法名。 */
    fun supports(method: String): Boolean = method in HANDLED_METHODS

    /** 处理一条方法调用（[supports] 为 true 时才应进入）。 */
    fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            // ---- 通知历史（NotificationInbox） ----
            "notificationInbox.history" -> {
                val limit = call.argument<Number>("limit")?.toInt()?.coerceIn(1, 500) ?: 50
                val offset = call.argument<Number>("offset")?.toInt()?.coerceAtLeast(0) ?: 0
                val inbox = inboxProvider()
                if (inbox == null) {
                    result.success(emptyList<Map<String, Any?>>())
                    return
                }
                result.success(inbox.history(limit, offset).map { it.toMap() })
            }
            "notificationInbox.markRead" -> {
                val notificationId = call.argument<String>("notification_id")
                    ?: return result.error("bad_args", "missing notification_id", null)
                val updated = inboxProvider()?.markRead(notificationId) ?: 0
                result.success(mapOf("updated" to updated))
            }
            "notificationInbox.markAllRead" -> {
                val updated = inboxProvider()?.markAllRead() ?: 0
                result.success(mapOf("updated" to updated))
            }
            "notificationInbox.count" -> {
                result.success(mapOf("count" to (inboxProvider()?.count() ?: 0)))
            }

            // ---- 采集查询（CaptureStore） ----
            "capture.listCaptures" -> {
                val sinceMs = call.argument<Number>("since_ms")?.toLong()
                val untilMs = call.argument<Number>("until_ms")?.toLong()
                val limit = call.argument<Number>("limit")?.toInt()?.coerceIn(1, 500) ?: 200
                result.success(
                    storeProvider().listCaptures(sinceMs, untilMs).take(limit).map { it.toMap() },
                )
            }
            "capture.listSegments" -> {
                val limit = call.argument<Number>("limit")?.toInt()?.coerceIn(1, 500) ?: 50
                val sinceMs = call.argument<Number>("since_ms")?.toLong()
                result.success(storeProvider().listSegments(limit, sinceMs).map { it.toMap() })
            }
            "capture.search" -> {
                val query = call.argument<String>("query")
                    ?: return result.error("bad_args", "missing query", null)
                val limit = call.argument<Number>("limit")?.toInt()?.coerceIn(1, 100) ?: 20
                val sinceMs = call.argument<Number>("since_ms")?.toLong()
                result.success(storeProvider().search(query, limit, sinceMs).map { it.toMap() })
            }
            "capture.getEvidencePath" -> {
                val ref = call.argument<String>("ref")
                    ?: return result.error("bad_args", "missing ref", null)
                val path = resolveEvidencePath(ref)
                if (path == null) {
                    result.error("not_found", "证据不存在或引用非法: $ref", null)
                } else {
                    result.success(mapOf("path" to path))
                }
            }
            "capture.storageStats" -> {
                val s = storeProvider().storageStats()
                result.success(
                    mapOf(
                        "captures" to s.captures,
                        "segments" to s.segments,
                        "favorites" to s.favorites,
                        "notes" to s.notes,
                        "image_files" to s.imageFiles,
                        "image_bytes" to s.imageBytes,
                    ),
                )
            }
            "capture.purgeBefore" -> {
                val cutoffMs = call.argument<Number>("cutoff_ms")?.toLong()
                    ?: return result.error("bad_args", "missing cutoff_ms", null)
                val keepFavorites = call.argument<Boolean>("keep_favorites") ?: true
                runHeavy(result) {
                    val r = storeProvider().purgeBefore(cutoffMs, keepFavorites)
                    mapOf(
                        "rows_deleted" to r.rowsDeleted,
                        "files_deleted" to r.filesDeleted,
                        "bytes_freed" to r.bytesFreed,
                        "favorites_kept" to r.favoritesKept,
                    )
                }
            }
            "capture.compressImagesBefore" -> {
                val cutoffMs = call.argument<Number>("cutoff_ms")?.toLong()
                    ?: return result.error("bad_args", "missing cutoff_ms", null)
                val maxSide = call.argument<Number>("max_side")?.toInt() ?: 1280
                val quality = call.argument<Number>("quality")?.toInt() ?: 80
                runHeavy(result) {
                    val r = storeProvider().compressImagesBefore(
                        cutoffMs, quality = quality, maxSide = maxSide,
                    )
                    mapOf(
                        "files_compressed" to r.filesCompressed,
                        "bytes_before" to r.bytesBefore,
                        "bytes_after" to r.bytesAfter,
                    )
                }
            }

            // ---- 收藏 ----
            "favorites.list" -> {
                result.success(storeProvider().listFavorites().map { it.toMap() })
            }
            "favorites.add" -> {
                val captureId = call.argument<Number>("capture_id")?.toLong()
                val segmentId = call.argument<String>("segment_id")
                val note = call.argument<String>("note")
                if (captureId == null && segmentId == null) {
                    return result.error("bad_args", "capture_id 或 segment_id 必填其一", null)
                }
                // 唯一索引保证同一目标只收藏一次（重复添加幂等 no-op，ok 恒 true）
                storeProvider().addFavorite(captureId, segmentId, note)
                result.success(mapOf("ok" to true))
            }
            "favorites.remove" -> {
                val captureId = call.argument<Number>("capture_id")?.toLong()
                val segmentId = call.argument<String>("segment_id")
                val removed = storeProvider().removeFavorite(captureId, segmentId)
                result.success(mapOf("removed" to removed))
            }

            // ---- 备份/恢复（ZIP + manifest + JSONL，幂等） ----
            "backup.exportZip" -> {
                val destDir = call.argument<String>("dest_dir")
                    ?: return result.error("bad_args", "missing dest_dir", null)
                val sinceMs = call.argument<Number>("since_ms")?.toLong()
                runHeavy(result) {
                    val dir = File(destDir)
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalArgumentException("dest_dir 不可写: $destDir")
                    }
                    val destZip = File(dir, "whale_capture_backup_${System.currentTimeMillis()}.zip")
                    val r = BackupRepository(appContext, storeProvider()).exportZip(destZip, sinceMs)
                    mapOf(
                        "zip_path" to r.zipFile.absolutePath,
                        "captures" to r.captures,
                        "segments" to r.segments,
                        "favorites" to r.favorites,
                        "notes" to r.notes,
                        "image_files" to r.files,
                        "backup_hash" to r.backupId,
                    )
                }
            }
            "backup.importZip" -> {
                val path = call.argument<String>("path")
                    ?: return result.error("bad_args", "missing path", null)
                runHeavy(result) {
                    val r = BackupRepository(appContext, storeProvider()).importZip(File(path))
                    mapOf(
                        "backup_hash" to r.backupId,
                        "captures_imported" to r.capturesImported,
                        "captures_duplicate" to r.rowsSkippedDuplicate,
                        "segments_imported" to r.segmentsImported,
                        "favorites_imported" to r.favoritesImported,
                        "notes_imported" to r.notesImported,
                        "images_restored" to r.filesImported,
                        "already_imported" to r.skippedDuplicateBackup,
                        "errors" to r.errors,
                    )
                }
            }

            // ---- 启动深链（读取并消费，避免重复路由） ----
            "getLaunchRoute" -> {
                val intent = activityProvider()?.intent
                val route = intent?.getStringExtra(NotificationDeepLinkRouter.EXTRA_ROUTE)
                if (intent != null && route != null) {
                    val out = LinkedHashMap<String, Any?>()
                    out["route"] = route
                    out["target_type"] =
                        intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_TARGET_TYPE)
                    out["target_id"] =
                        intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_TARGET_ID)
                    out["notification_id"] =
                        intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_NOTIFICATION_ID)
                    out["event_id"] =
                        intent.getStringExtra(NotificationDeepLinkRouter.EXTRA_EVENT_ID)
                    // 消费：读后清除，防止配置变更/重建触发重复路由
                    intent.removeExtra(NotificationDeepLinkRouter.EXTRA_ROUTE)
                    result.success(out)
                } else {
                    result.success(mapOf("route" to null))
                }
            }

            else -> result.notImplemented()
        }
    }

    /** 后台线程执行重操作，主线程回执（与 v1 importScreenMemoData 同模式）。 */
    private fun runHeavy(result: MethodChannel.Result, block: () -> Map<String, Any?>) {
        Thread {
            try {
                val out = block()
                mainHandler.post { result.success(out) }
            } catch (t: Throwable) {
                Log.w(TAG, "重操作失败: ${t.message}")
                mainHandler.post { result.error("internal", t.message, null) }
            }
        }.start()
    }

    /**
     * 证据引用 → 本地文件路径（不透明的本地路径不出设备，仅供本机 UI 展示）。
     * 支持 `capture:row_<id>`（截图帧）与 `capture:segment_<ULID>`（段 →
     * 取首个可解析成员帧）；逃逸 filesDir 的路径一律拒绝。
     */
    private fun resolveEvidencePath(ref: String): String? {
        val store = storeProvider()
        val rowId = when {
            ref.startsWith("capture:segment_") -> {
                val segment = store.getSegment(ref.removePrefix("capture:")) ?: return null
                segment.evidence.firstNotNullOfOrNull { CaptureStore.evidenceRefToRowId(it) }
            }
            else -> CaptureStore.evidenceRefToRowId(ref)
        } ?: return null
        val row = store.getById(rowId) ?: return null
        val file = store.evidenceFile(appContext, row) ?: return null
        return try {
            val root = appContext.filesDir.canonicalFile
            val canonical = file.canonicalFile
            if (canonical.path.startsWith(root.path + File.separator)) canonical.path else null
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------- 序列化

    private fun NotificationInbox.Record.toMap(): Map<String, Any?> = mapOf(
        "event_id" to eventId,
        "notification_id" to notificationId,
        "category" to category,
        "title" to title,
        "body" to body,
        "status" to status.wire,
        "created_at" to createdAt,
        "expires_at" to expiresAt,
        "target_json" to targetJson,
        "received_at" to receivedAt,
        "updated_at" to updatedAt,
        "read" to read,
    )

    private fun CaptureStore.Row.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "capture_time" to captureTime,
        "package_name" to packageName,
        "app_label" to appLabel,
        "width" to width,
        "height" to height,
        "ocr_status" to ocrStatus,
        "ocr_text" to ocrText,
    )

    private fun CaptureStore.Segment.toMap(): Map<String, Any?> = mapOf(
        "segment_id" to segmentId,
        "package_name" to packageName,
        "started_at" to startedAt,
        "ended_at" to endedAt,
        "summary" to summary,
        "evidence" to evidence,
        "member_count" to memberCount,
        "closed" to closed,
    )

    private fun CaptureStore.Favorite.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "capture_id" to captureId,
        "segment_id" to segmentId,
        "note" to note,
        "created_at" to createdAt,
    )

    companion object {
        private const val TAG = "WhaleDataQueries"

        /** 本查询面处理的方法名全集（桥方法存在性测试以此为准）。 */
        val HANDLED_METHODS: Set<String> = setOf(
            "notificationInbox.history", "notificationInbox.markRead",
            "notificationInbox.markAllRead", "notificationInbox.count",
            "capture.listCaptures", "capture.listSegments", "capture.search",
            "capture.getEvidencePath", "capture.storageStats",
            "capture.purgeBefore", "capture.compressImagesBefore",
            "favorites.list", "favorites.add", "favorites.remove",
            "backup.exportZip", "backup.importZip",
            "getLaunchRoute",
        )
    }
}
