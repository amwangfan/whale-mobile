package com.wangfanlab.whale.modules.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * API 30+ 截屏执行器（单独类以隔离新 API：低版本系统类加载/验证不会触达
 * TakeScreenshotCallback 等新类型，由调用方用 Build.VERSION 守门后调用）。
 *
 * 截屏链路参照 ScreenMemo 已验证的 takeScreenshot 用法（署名见 README 致谢），
 * 影子模式位图只算 dHash 即回收，不落盘。
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
object ShotTaker30 {

    /**
     * @param keepBitmap true 时 onResult 携带软件位图（调用方负责回收）；
     *     false（影子模式）时内部算完 dHash 即回收，onResult 的 bitmap 恒为 null。
     */
    fun take(
        service: AccessibilityService,
        executor: Executor,
        keepBitmap: Boolean = false,
        onResult: (hash: Long?, latencyMs: Long, errorCode: Int?, bitmap: Bitmap?) -> Unit,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val latency = SystemClock.elapsedRealtime() - startMs
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        if (hw == null) {
                            onResult(null, latency, -2, null)
                            return
                        }
                        // HARDWARE 位图不可读像素：先转 ARGB_8888 软件位图
                        // （ScreenMemo 同样经 copy 转换；位图仅用于 dHash，随即回收）。
                        val sw = try {
                            hw.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            hw.recycle()
                        }
                        if (sw == null) {
                            onResult(null, latency, -3, null)
                            return
                        }
                        val hash = DHash.compute(sw)
                        if (keepBitmap) {
                            // 位图所有权移交调用方（持久化管线；用后回收）
                            onResult(hash, latency, null, sw)
                        } else {
                            try {
                                onResult(hash, latency, null, null)
                            } finally {
                                sw.recycle()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null, SystemClock.elapsedRealtime() - startMs, errorCode, null)
                    }
                },
            )
        } catch (t: Throwable) {
            onResult(null, SystemClock.elapsedRealtime() - startMs, -1, null)
        }
    }
}
