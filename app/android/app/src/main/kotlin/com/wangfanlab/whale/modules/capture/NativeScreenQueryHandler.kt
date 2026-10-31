package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 内置数据源 sensor.query 处理器（SM-03，Issue #10）。
 *
 * 当 capture_data_source=builtin 时，screen.context.read / screen.history.search /
 * screen.evidence.read 直接由本机 CaptureStore 回答（**无 UI 也可用**）；
 * 输出字段形状与 MCP adapter 对齐（kind/capture_time/app/snippet/privacy_class，
 * evidence 为 image_ref+data_base64+downscaled），Core 侧无感知。
 *
 * 隐私纪律不变：snippet ≤200 字符；证据图共享 256KiB 帧预算（JPEG 质量阶梯
 * 压缩，仍超则标 too_large_for_frame 不回传）；source 标记 capture.local。
 */
class NativeScreenQueryHandler(
    private val context: Context,
    private val store: CaptureStore,
    private val reply: (requestId: String, status: String, data: List<Any?>, truncated: Boolean, source: String) -> Unit,
) {

    companion object {
        const val SOURCE = "capture.local"
        private const val TAG = "WhaleNativeQuery"
        private const val MAX_SNIPPET = 200
        private const val MAX_EVIDENCE = 5
        private const val FRAME_BUDGET_BYTES = 256 * 1024

        /** 本处理器支持的操作前缀。 */
        fun supports(operation: String): Boolean = operation in setOf(
            "screen.context.read", "screen.history.search", "screen.evidence.read",
        )
    }

    /** 处理一条 sensor.query payload；已回答返回 true。 */
    fun handle(payload: Map<String, Any?>): Boolean {
        val requestId = payload["request_id"] as? String ?: return false
        val operation = payload["operation"] as? String ?: return false
        @Suppress("UNCHECKED_CAST")
        val filters = payload["filters"] as? Map<String, Any?> ?: emptyMap()
        val maxItems = (payload["max_items"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 10
        when (operation) {
            "screen.context.read" -> {
                val rows = store.recent(maxItems)
                reply(requestId, "ok", rows.map { it.toItem() }, false, SOURCE)
            }
            "screen.history.search" -> {
                val query = filters["query"] as? String
                if (query.isNullOrBlank()) {
                    reply(requestId, "error",
                        listOf(mapOf("error" to "missing_query", "message" to "filters.query 为空")),
                        false, SOURCE)
                    return true
                }
                val since = (filters["since_ms"] as? Number)?.toLong()
                val rows = store.search(query, limit = maxItems, sinceMs = since)
                reply(requestId, "ok", rows.map { it.toItem() }, false, SOURCE)
            }
            "screen.evidence.read" -> {
                @Suppress("UNCHECKED_CAST")
                val refs = (filters["image_refs"] as? List<*>)?.filterIsInstance<String>()
                    ?: emptyList()
                if (refs.isEmpty()) {
                    reply(requestId, "error",
                        listOf(mapOf("error" to "missing_refs", "message" to "filters.image_refs 为空")),
                        false, SOURCE)
                    return true
                }
                reply(requestId, "ok", refs.take(MAX_EVIDENCE).map { evidenceItem(it) }, false, SOURCE)
            }
            else -> return false
        }
        return true
    }

    private fun CaptureStore.Row.toItem(): Map<String, Any?> = mapOf(
        "kind" to "screenshot",
        "capture_time" to captureTime,
        "app" to (appLabel ?: packageName),
        "snippet" to (ocrText?.take(MAX_SNIPPET) ?: ""),
        "evidence_ref" to "capture:$id",
        "privacy_class" to "owner_private",
    )

    private fun evidenceItem(ref: String): Map<String, Any?> {
        val id = ref.removePrefix("capture:").toLongOrNull()
            ?: return mapOf("image_ref" to ref, "error" to "bad_ref", "privacy_class" to "owner_sensitive")
        val row = store.getById(id)
            ?: return mapOf("image_ref" to ref, "error" to "not_found", "privacy_class" to "owner_sensitive")
        val file = store.evidenceFile(context, row)
            ?: return mapOf("image_ref" to ref, "error" to "evidence_unavailable",
                "privacy_class" to "owner_sensitive")
        return try {
            val bytes = fitToFrameBudget(file)
            if (bytes == null) {
                mapOf(
                    "image_ref" to ref, "error" to "too_large_for_frame",
                    "downscaled" to true, "privacy_class" to "owner_sensitive",
                )
            } else {
                mapOf(
                    "image_ref" to ref,
                    "data_base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                    "mime" to "image/jpeg",
                    "downscaled" to true,
                    "original_size_bytes" to file.length(),
                    "privacy_class" to "owner_sensitive",
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "证据读取失败 ref=$ref", t)
            mapOf("image_ref" to ref, "error" to "read_failed", "privacy_class" to "owner_sensitive")
        }
    }

    /** JPEG 质量/尺寸阶梯压进帧预算（最小 320px 宽仍超则放弃）。 */
    private fun fitToFrameBudget(file: File): ByteArray? {
        val src = BitmapFactory.decodeFile(file.path) ?: return null
        try {
            var bmp = src
            for (quality in listOf(80, 60, 40)) {
                for (maxSide in listOf(1280, 800, 480, 320)) {
                    val scaled = scaleTo(bmp, maxSide)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (scaled !== bmp && scaled !== src) scaled.recycle()
                    val bytes = out.toByteArray()
                    if (bytes.size <= FRAME_BUDGET_BYTES) return bytes
                }
            }
            return null
        } finally {
            src.recycle()
        }
    }

    private fun scaleTo(bmp: Bitmap, maxSide: Int): Bitmap {
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
}
