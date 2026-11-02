package com.wangfanlab.whale.modules.notificationread

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.wangfanlab.whale.runtime.NodeRuntime

/**
 * 通知监听模块（阶段二 B3 前半：phone.notification.read，独立可选，默认关）。
 *
 * - [WhaleNotificationListenerService]：NotificationListenerService
 *   （BIND_NOTIFICATION_LISTENER_SERVICE，manifest 声明 + label/description），
 *   回调仅做脱敏字段提取后经静态钩子转交 [NotificationReadModule]。
 * - 白名单制：仅监听用户勾选 App（whale_notif_read/allowed_apps，Set；
 *   默认空 = 一个都不读）。
 * - 脱敏：仅取 package/postedTime/category/标题（≤80 字符），**绝不取正文**
 *   （EXTRA_TEXT/EXTRA_TEXT_LINES/EXTRA_BIG_TEXT/tickerText 一律不读）。
 * - 事件链（[NotificationReadDecider]）：全局暂停 → 模块开关 → 白名单 →
 *   类别过滤 → 60s 去重 → sensor.event kind=notification.posted、
 *   privacy_class=owner_private、summary=「App 名: 标题截断」，
 *   经注入的 eventSender → WssClient.sendSensorEvent（离线入队，
 *   drop_on_pause=true：私人历史不补传，§6）。
 */
class NotificationReadModule(
    context: Context,
    /** 上行事件出口（TransportBridge 注入 → WssClient.sendSensorEvent，离线入队）。 */
    private val eventSender: (summary: String, occurredAt: Long) -> Unit = { _, _ -> },
    private val decider: NotificationReadDecider = NotificationReadDecider(
        moduleEnabled = { isEnabled(context.applicationContext) },
        allowedApps = { allowedApps(context.applicationContext) },
        allPaused = { NodeRuntime.isAllPaused(context.applicationContext) },
    ),
    /** App 显示名解析（PM；测试可替换）。 */
    private val labelResolver: (String) -> String = { pkg ->
        resolveAppLabel(context.applicationContext, pkg)
    },
) {
    private val appContext: Context = context.applicationContext

    init {
        // 静态钩子：service 回调线程不定，decider/deduper 内部线程安全。
        // SEC-02：注册前先回放进程早期（钩子就绪前）缓冲的事件，保证不丢。
        WhaleNotificationListenerService.sNotificationHook = { raw ->
            handle(raw)
        }
        val replay = WhaleNotificationListenerService.pendingBuffer.drain()
        if (replay.isNotEmpty()) {
            Log.i(TAG, "回放缓冲通知事件 ${replay.size} 条")
            for (raw in replay) handle(raw)
        }
    }

    private fun handle(raw: WhaleNotificationListenerService.RawNotification) {
        debugLog("recv pkg=${raw.packageName} title_len=${raw.title.length}")
        val snapshot = NotifSnapshot(
            packageName = raw.packageName,
            appLabel = labelResolver(raw.packageName),
            postedTimeMs = raw.postedTimeMs,
            category = raw.category,
            title = raw.title,
            ongoing = raw.ongoing,
            hasProgress = raw.hasProgress,
        )
        when (val d = decider.onNotification(snapshot)) {
            is NotifReadDecision.Drop -> {
                Log.d(TAG, "通知丢弃(${d.reason}): ${raw.packageName}")
                debugLog("drop ${d.reason} pkg=${raw.packageName}")
            }

            is NotifReadDecision.Emit -> {
                Log.i(TAG, "通知事件: ${d.summary.take(40)}…")
                eventSender(d.summary, raw.postedTimeMs)
            }
        }
    }

    private fun debugLog(line: String) {
        try {
            val dbg = (appContextForLog.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!dbg) return
            java.io.File(appContextForLog.filesDir, "notifread_debug.log")
                .appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }

    private val appContextForLog: Context
        get() = appContext


    companion object {
        private const val TAG = "WhaleNotifRead"
        const val CAPABILITY = "phone.notification.read"

        /** 模块开关（whale_modules/notification_read_enabled，默认 false）。 */
        const val PREFS_NAME = "whale_modules"
        const val KEY_ENABLED = "notification_read_enabled"

        /** App 白名单存储（whale_notif_read/allowed_apps，Set；默认空）。 */
        const val WHITELIST_PREFS = "whale_notif_read"
        const val KEY_ALLOWED_APPS = "allowed_apps"

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun whitelistPrefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(WHITELIST_PREFS, Context.MODE_PRIVATE)

        fun allowedApps(context: Context): Set<String> =
            whitelistPrefs(context).getStringSet(KEY_ALLOWED_APPS, emptySet())
                ?.toSet() ?: emptySet()

        fun setAllowedApps(context: Context, apps: Set<String>) {
            whitelistPrefs(context).edit().putStringSet(KEY_ALLOWED_APPS, apps).apply()
        }

        /**
         * 通知监听特殊访问权限状态（NotificationManagerCompat.getEnabledListenerPackages；
         * 等价于 Settings.Secure enabled_notification_listeners 解析）。
         */
        fun listenerPermissionGranted(context: Context): Boolean = try {
            NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
                .contains(context.packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "监听权限查询失败", t)
            false
        }

        fun permissionStatus(context: Context): String =
            if (listenerPermissionGranted(context)) "granted" else "denied"

        /** 「去授权」：跳系统通知监听设置页（特殊访问权限无运行时弹窗）。 */
        fun openListenerSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "打开通知监听设置失败", t)
            }
        }

        /** App 显示名解析（PM label；失败回落包名）。 */
        fun resolveAppLabel(context: Context, packageName: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (t: Throwable) {
            packageName
        }

        /**
         * 可监听 App 列表（白名单多选页数据源）：
         * 带 LAUNCHER 入口的已安装 App（label + package），按 label 排序。
         */
        fun listListenableApps(context: Context): List<Map<String, String>> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val flags = PackageManager.MATCH_ALL.toLong().let {
                if (Build.VERSION.SDK_INT >= 33) {
                    PackageManager.ResolveInfoFlags.of(it)
                } else {
                    null
                }
            }
            val resolved = if (flags != null) {
                pm.queryIntentActivities(intent, flags)
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            return resolved
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                    mapOf("package" to pkg, "label" to label)
                }
                .distinctBy { it["package"] }
                .sortedBy { it["label"] }
        }
    }
}

/**
 * 通知监听器服务（manifest：BIND_NOTIFICATION_LISTENER_SERVICE + label/description）。
 * 回调只做脱敏字段提取（**绝不读正文**），经静态钩子转交模块；
 * 未授予特殊访问权限时系统不会绑定本服务，天然 fail-closed。
 */
class WhaleNotificationListenerService : NotificationListenerService() {

    /** service → 模块的桥（由 NotificationReadModule.init 注册；为 null 时全部丢弃）。 */
    data class RawNotification(
        val packageName: String,
        val postedTimeMs: Long,
        val category: String?,
        val title: String,
        val ongoing: Boolean,
        val hasProgress: Boolean,
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val hook = sNotificationHook
        // MagicOS 等无 logcat 固件的观测通道（仅 debug 包；只记包名/计数，不记内容）
        try {
            val dbg = (applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (dbg) {
                java.io.File(filesDir, "notifread_debug.log").appendText(
                    "${System.currentTimeMillis()} svc_recv pkg=${sbn.packageName} hook=${hook != null}\n",
                )
            }
        } catch (_: Throwable) {
        }
        try {
            val n = sbn.notification ?: return
            // 脱敏：仅 EXTRA_TITLE；正文（EXTRA_TEXT*）/tickerText 一律不读。
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString().orEmpty()
            val raw = RawNotification(
                packageName = sbn.packageName.orEmpty(),
                postedTimeMs = sbn.postTime,
                category = n.category,
                title = title,
                ongoing = sbn.isOngoing,
                hasProgress = n.extras?.let {
                    it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                } == true,
            )
            if (hook != null) {
                hook(raw)
            } else {
                // SEC-02：运行时未就绪（进程冷启动竞态）→ 有界缓冲，不静默丢。
                pendingBuffer.offer(raw)
                Log.d(TAG, "钩子未就绪，通知事件入缓冲（${pendingBuffer.size}）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "通知字段提取异常", t)
        }
    }

    override fun onListenerConnected() {
        // SEC-02：系统已实际绑定（与「授权已授予」区分观测）。
        listenerBound = true
        lastBoundAtMs = System.currentTimeMillis()
        Log.i(TAG, "NotificationListener 已被系统绑定")
    }

    override fun onListenerDisconnected() {
        listenerBound = false
        lastUnboundAtMs = System.currentTimeMillis()
        Log.i(TAG, "NotificationListener 解绑，请求重绑")
        // 权限被撤销/服务被杀后系统会自动解绑；请求重连（API 24+，minSdk 26）。
        try {
            requestRebind(
                android.content.ComponentName(this, this.javaClass),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "requestRebind 失败", t)
        }
    }

    companion object {
        private const val TAG = "WhaleNotifListener"

        @Volatile
        var sNotificationHook: ((RawNotification) -> Unit)? = null

        /** SEC-02：钩子未就绪时的有界缓冲（溢出丢最旧并计数）。 */
        val pendingBuffer = NotificationEventBuffer<RawNotification>()

        /** 系统是否已实际绑定本服务（onListenerConnected/Disconnected 观测）。 */
        @Volatile
        var listenerBound: Boolean = false
            private set

        @Volatile
        var lastBoundAtMs: Long = 0
            private set

        @Volatile
        var lastUnboundAtMs: Long = 0
            private set

        /** 可观测绑定状态：granted（设置授权）与 bound（系统实际绑定）分开。 */
        fun bindingStatus(context: Context): Map<String, Any?> = mapOf(
            "permission" to NotificationReadModule.permissionStatus(context),
            "bound" to listenerBound,
            "last_bound_at" to lastBoundAtMs.takeIf { it > 0 },
            "last_unbound_at" to lastUnboundAtMs.takeIf { it > 0 },
            "buffered" to pendingBuffer.size,
            "dropped_overflow" to pendingBuffer.droppedOverflow,
        )
    }
}
