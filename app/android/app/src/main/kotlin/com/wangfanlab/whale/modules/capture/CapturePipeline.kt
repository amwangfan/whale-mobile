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
 * 采集持久化管线（SM-02，Issue #9）：唯一帧 → 存 JPEG → OCR → 索引。
 *
 * - 仅在 capture_persist_enabled=true（非影子模式）时由 ShadowCaptureService 调用；
 * - 位图处理后立即回收，文件存 filesDir/capture/shots/yyyyMM/<ts>.jpg；
 * - OCR 失败记录 ocr_status=failed（可后续重试），不阻塞入库；
 * - 幂等：去重键冲突时跳过（返回 -1）。
 */
class CapturePipeline(
    private val context: Context,
    private val store: CaptureStore,
    private val ocr: OcrEngine,
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
            if (id < 0) File(context.filesDir, CaptureStore.SHOTS_DIR_RELATIVE + "/" + relPath).delete()
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
