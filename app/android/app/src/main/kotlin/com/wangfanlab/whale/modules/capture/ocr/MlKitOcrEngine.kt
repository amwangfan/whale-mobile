package com.wangfanlab.whale.modules.capture.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * ML Kit 中文 OCR（SM-02；依赖与 ScreenMemo 同款
 * `com.google.mlkit:text-recognition-chinese`，本地识别、不上云）。
 *
 * 首次使用可能触发模型下载（Google Play 服务通道）；模型未就绪/失败
 * 返回 null，调用方按 ocr_status=failed 记录并可在后续重试。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun recognize(bitmap: Bitmap): String? {
        // Tasks.await 同步等待（不依赖 listener 投递；调用方为工作线程）。
        return try {
            val r = com.google.android.gms.tasks.Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                TIMEOUT_MS, TimeUnit.MILLISECONDS,
            )
            r.text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 失败/超时: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WhaleOcr"
        private const val TIMEOUT_MS = 60_000L
    }
}
