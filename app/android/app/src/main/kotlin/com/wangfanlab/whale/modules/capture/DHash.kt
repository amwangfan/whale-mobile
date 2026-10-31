package com.wangfanlab.whale.modules.capture

import android.graphics.Bitmap

/**
 * 64 位 dHash（差异哈希）：缩放到 9x8 亮度矩阵，比较相邻像素梯度生成 64 位指纹。
 * 用于截屏去重（与 ScreenMemo 的感知去重思路一致，Whale 内重写实现）。
 */
object DHash {

    /**
     * 从位图计算 dHash。采样缩放开销极小（9x8 网格均值），
     * 调用方负责保证 bitmap 可读；本函数不回收 bitmap。
     */
    fun compute(source: Bitmap): Long {
        val w = 9
        val h = 8
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        // 亮度矩阵（0.299/0.587/0.114）
        val lum = IntArray(w * h) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        var hash = 0L
        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                hash = hash shl 1
                if (lum[y * w + x] > lum[y * w + x + 1]) hash = hash or 1L
            }
        }
        return hash
    }
}
