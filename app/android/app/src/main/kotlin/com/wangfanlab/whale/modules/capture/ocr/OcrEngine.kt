package com.wangfanlab.whale.modules.capture.ocr

import android.graphics.Bitmap
import android.util.Log

/**
 * OCR 引擎抽象（SM-02）：生产实现为 ML Kit 中文识别（本地、离线），
 * 测试注入假实现。引擎失败返回 null（由调用方记 ocr_status=failed，可重试）。
 */
fun interface OcrEngine {
    /** 识别位图文字；失败返回 null。位图由调用方管理生命周期。 */
    fun recognize(bitmap: Bitmap): String?

    companion object {
        /** 生产引擎（懒加载 ML Kit 模型；模型缺失/下载失败返回 null）。 */
        val mlKit: OcrEngine by lazy { MlKitOcrEngine() }
    }
}
