package com.wangfanlab.whale.modules.notification2

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wangfanlab.whale.MainActivity

/**
 * 通知点击跳转路由（修正方案 §3.3：点击进入对应会话/任务/设备/审批页）。
 *
 * target.type ∈ task / conversation / device / approval / chat / none（协议枚举）。
 * 原生层无法知道 Flutter 侧页面是否真实存在，约定如下：
 * - 已知类型 → 生成约定路由串（/task/<id> 等），作为 extras 放进 MainActivity intent，
 *   由 Flutter 侧读取并路由（Flutter 是路由事实源）；
 * - id 缺失 → 该栏目首页（/task 等）；
 * - type 为 none 或未知 → 打开 App 首页（/），并在日志注明（不静默吞掉）。
 */
object NotificationDeepLinkRouter {
    private const val TAG = "WhaleNotif2Route"

    const val EXTRA_ROUTE = "whale_notification_route"
    const val EXTRA_TARGET_TYPE = "whale_notification_target_type"
    const val EXTRA_TARGET_ID = "whale_notification_target_id"
    const val EXTRA_NOTIFICATION_ID = "whale_notification_id"
    const val EXTRA_EVENT_ID = "whale_notification_event_id"

    /** 已知 target.type → 路由前缀。 */
    private val ROUTE_PREFIX = mapOf(
        "task" to "/task",
        "conversation" to "/conversation",
        "device" to "/device",
        "approval" to "/approval",
        "chat" to "/chat",
    )

    /** target → 约定路由串；未知/none 返回 "/"（首页）。 */
    fun routeFor(target: NotificationTarget?): String {
        if (target == null || target.type == "none") return "/"
        val prefix = ROUTE_PREFIX[target.type]
        if (prefix == null) {
            Log.w(TAG, "未知 target.type=${target.type}，回退首页")
            return "/"
        }
        return if (target.id.isNullOrBlank()) prefix else "$prefix/${target.id}"
    }

    /**
     * 构造拉起 MainActivity 的 intent（携带路由 extras，Flutter 侧读取）。
     * MainActivity 为 singleTop：App 活着时走 onNewIntent，被杀时冷启动带 extras。
     */
    fun buildLaunchIntent(
        context: Context,
        target: NotificationTarget?,
        notificationId: String?,
        eventId: String?,
    ): Intent {
        val route = routeFor(target)
        if (route == "/" && target != null && target.type != "none") {
            Log.w(TAG, "target(${target.type},${target.id}) 无对应页面约定，打开首页")
        }
        return Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ROUTE, route)
            putExtra(EXTRA_TARGET_TYPE, target?.type ?: "none")
            if (!target?.id.isNullOrBlank()) putExtra(EXTRA_TARGET_ID, target.id)
            if (notificationId != null) putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            if (eventId != null) putExtra(EXTRA_EVENT_ID, eventId)
        }
    }
}
