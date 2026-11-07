package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wangfanlab.whale.modules.capture.ocr.OcrEngine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集持久化管线（SM-02，Issue #9；Phase 3 接入段聚合）：唯一帧 → 存 JPEG →
 * OCR → 索引 → 段聚合。
 *
 * - 仅在 capture_persist_enabled=true（非影子模式）时由 ShadowCaptureService 调用；
 * - 位图处理后立即回收，文件存 filesDir/capture/shots/yyyyMM/<ts>.jpg；
 * - OCR 失败记录 ocr_status=failed（可后续重试），不阻塞入库；
 * - 幂等：去重键冲突时跳过（返回 -1，不触达段聚合）；
 * - Phase 3：[segmentAggregator] 非空时，每帧入库后归入/切换动态段；
 *   旧段封口时回调 [onSegmentReady]（由调用方产出 experience.event，见
 *   [SegmentReadyEmitter]）。两者均可空（影子/测试环境不接聚合与上行）。
 */
class CapturePipeline(
    private val context: Context,
    private val store: CaptureStore,
    private val ocr: OcrEngine,
    private val segmentAggregator: SegmentAggregator? = null,
    private val onSegmentReady: ((CaptureStore.Segment) -> Unit)? = null,
) {

    /**
     * 处理一帧唯一截图。返回行 id（-1 = 去重冲突）。
     * 调用方负责传入可读的软件位图；本函数**不回收** bitmap。
     */
    fun process(
        bitmap: Bitmap,
        dhash: Long,
        packageName: String,
        appLabel: String?,
        captureTimeMs: Long,
    ): Long {
        val relPath = saveJpeg(bitmap, captureTimeMs) ?: return -1
        val ocrText = try {
            ocr.recognize(bitmap)
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 异常", t)
            null
        }
        return store.insert(
            CaptureStore.Row(
                id = 0,
                captureTime = captureTimeMs,
                packageName = packageName,
                appLabel = appLabel,
                dhash = dhash,
                width = bitmap.width,
                height = bitmap.height,
                ocrStatus = if (ocrText != null) "ok" else "failed",
                ocrText = ocrText,
                imagePath = relPath,
            ),
        ).also { id ->
            if (id < 0) {
                File(context.filesDir, CaptureStore.SHOTS_DIR_RELATIVE + "/" + relPath).delete()
            } else {
                aggregateIntoSegment(id, packageName, captureTimeMs, ocrText)
            }
        }
    }

    /** Phase 3：入库成功后喂给段聚合器；旧段封口即回调事件产出。 */
    private fun aggregateIntoSegment(
        rowId: Long,
        packageName: String,
        captureTimeMs: Long,
        ocrText: String?,
    ) {
        val agg = segmentAggregator ?: return
        try {
            val result = agg.onFrame(rowId, packageName, captureTimeMs, ocrText)
            if (result is SegmentAggregator.Result.ClosedAndOpened) {
                try {
                    onSegmentReady?.invoke(result.closed)
                } catch (t: Throwable) {
                    // 事件产出失败不阻塞采集主链路（下一段仍会封口）
                    Log.w(TAG, "segment_ready 事件回调失败", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "段聚合失败（帧已入库，不阻塞采集）", t)
        }
    }

    private fun saveJpeg(bitmap: Bitmap, captureTimeMs: Long): String? {
        val monthDir = SimpleDateFormat("yyyyMM", Locale.US).format(Date(captureTimeMs))
        val dir = File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/$monthDir")
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = "${captureTimeMs}.jpg"
        val f = File(dir, name)
        return try {
            FileOutputStream(f).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            "$monthDir/$name"
        } catch (t: Throwable) {
            Log.w(TAG, "截图保存失败", t)
            f.delete()
            null
        }
    }

    companion object {
        private const val TAG = "WhaleCapturePipeline"
        private const val JPEG_QUALITY = 80
    }
}
