package com.wangfanlab.whale.modules.notification2

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * NotificationManager / NotificationManagerCompat 的薄封装。
 *
 * 隔离 Android 系统调用，便于单元测试替换实现（handler 测试注入 fake）。
 * POST_NOTIFICATIONS 权限检查归 [NotificationPermissionManager]；
 * 本类只负责"提交/撤销"，调用前由 [NotificationEventHandler] 做权限与渠道校验——
 * 回执诚实性（§13.2）：只有真正提交成功才允许上行 displayed。
 */
open class NotificationManagerFacade(context: Context) {

    private val appContext = context.applicationContext
    private val nm: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    /**
     * 发布/更新通知。同 (id, tag) 再次调用即覆盖更新（Android 原生语义，
     * collapse_key 合并更新正是依赖这一点）。
     * @return true=已提交给系统（注意：提交≠用户可见，见 §13.2 诚实性注解）。
     */
    open fun post(id: Int, tag: String?, notification: Notification): Boolean = try {
        nm.notify(tag, id, notification)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "通知提交失败 id=$id", t)
        false
    }

    /** 撤销通知（revoke/用户操作后替换时用）。 */
    open fun cancel(id: Int, tag: String?) {
        try {
            nm.cancel(tag, id)
        } catch (t: Throwable) {
            Log.w(TAG, "通知撤销失败 id=$id", t)
        }
    }

    /** 当前活跃通知数（诊断/测试用）。 */
    open fun activeCount(): Int = try {
        nm.activeNotifications.size
    } catch (t: Throwable) {
        0
    }

    private companion object {
        const val TAG = "WhaleNotif2Facade"
    }
}
