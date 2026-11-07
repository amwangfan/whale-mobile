package com.wangfanlab.whale.runtime

import android.content.Context
import com.wangfanlab.whale.modules.notifications.NotificationModule

/**
 * 全局暂停标志（A1 一键暂停）读取点（自 v1 NodeRuntime 伴生对象抽出；
 * 与协议版本无关，v1/v2 共用同一 `whale_modules/all_paused` 键）。
 *
 * 写入由 Flutter 桥层（whale/transport、whale/transport_v2 的 setAllPaused）
 * 完成；执行器校验链前置读取：paused 时 action 回 failed/unavailable、
 * sensor.query 直接回 paused、drop_on_pause 事件不补传。
 */
object PauseFlags {

    /** 全局暂停键（whale_modules/all_paused；默认 false）。 */
    const val KEY_ALL_PAUSED = "all_paused"

    /** 全局暂停标志读取（执行器校验链前置检查）。 */
    fun isAllPaused(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALL_PAUSED, false)
}
