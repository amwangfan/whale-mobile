package com.wangfanlab.whale.modules.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.wangfanlab.whale.runtime.NodeRuntime
import com.wangfanlab.whale.transport.WssClient

/**
 * 通知模块（modules/notifications/DESIGN.md，阶段一：post/update/cancel + 精确回执）。
 *
 * - 注册为 `phone.notification.post` 的 [WssClient.ActionExecutor]；
 *   WssClient 过完传输层校验（验签/幂等/有效期/scope）后调用 [execute]，
 *   本模块再过端侧校验链（见 [NotificationDecider]），结果经 reply 回终态。
 * - 渠道注册（§3）：whale_reminder（默认重要级）/ whale_call（高）/ whale_status（低）。
 * - Android 13+ POST_NOTIFICATIONS 未授予 → failed/permission_missing（不崩溃）。
 */
class NotificationModule(
    context: Context,
    private val notifier: Notifier = SystemNotifier(context.applicationContext),
    private val decider: NotificationDecider = NotificationDecider(
        moduleEnabled = { isEnabled(context.applicationContext) },
        permissionGranted = { permissionGranted(context.applicationContext) },
        allPaused = { NodeRuntime.isAllPaused(context.applicationContext) },
    ),
) : WssClient.ActionExecutor {

    private val appContext = context.applicationContext

    init {
        ensureChannels()
    }

    /** WssClient executor 线程调用；同步执行，经 reply 回终态（executed/failed）。 */
    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?>
        when (val decision = decider.onRequest(args)) {
            is NotificationDecision.Reject -> {
                Log.w(TAG, "action 校验未通过: ${decision.errorCode}")
                reply("failed", decision.errorCode, null)
            }

            is NotificationDecision.Execute -> try {
                apply(decision.spec)
                reply("executed", null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "通知执行异常", t)
                reply("failed", "internal", null)
            }
        }
    }

    private fun apply(spec: NotificationSpec) {
        when (spec.op) {
            NotificationOp.POST, NotificationOp.UPDATE ->
                notifier.post(spec) // update = 同 tag+id 覆盖（Android 原生语义）

            NotificationOp.CANCEL ->
                notifier.cancel(spec.notificationTag ?: return)
        }
    }

    /** 幂等渠道注册（§3；重复调用安全，用户后续系统设置不被覆盖）。 */
    private fun ensureChannels() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_REMINDER, "提醒", NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    CHANNEL_CALL, "通话", NotificationManager.IMPORTANCE_HIGH,
                ),
                NotificationChannel(
                    CHANNEL_STATUS, "状态", NotificationManager.IMPORTANCE_LOW,
                ),
            )
        )
    }

    /** 通知发布/撤销抽象（便于 JVM 测试替换实现）。 */
    interface Notifier {
        fun post(spec: NotificationSpec)
        fun cancel(tag: String)
    }

    /** 系统 NotificationManager 实现。 */
    class SystemNotifier(private val context: Context) : Notifier {
        private val nm =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        override fun post(spec: NotificationSpec) {
            val builder = Notification.Builder(context, spec.channelId)
                .setContentTitle(spec.title)
                .setContentText(spec.text)
                .setSmallIcon(smallIconRes())
                .setShowWhen(true)
                .setCategory(categoryOf(spec.channelId))
            if (spec.channelId == CHANNEL_STATUS) builder.setOngoing(true)
            if (spec.openAppOnClick) {
                builder.setContentIntent(openAppIntent())
                builder.setAutoCancel(true)
            }
            nm.notify(spec.notificationTag, notificationIdOf(spec.notificationTag), builder.build())
        }

        override fun cancel(tag: String) {
            nm.cancel(tag, notificationIdOf(tag))
        }

        /** 仅允许打开本 App（click_action 白名单已在校验层收敛）。 */
        private fun openAppIntent(): PendingIntent? {
            val launch: Intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun smallIconRes(): Int = context.applicationInfo.icon

        private fun categoryOf(channelId: String): String = when (channelId) {
            CHANNEL_CALL -> Notification.CATEGORY_CALL
            CHANNEL_STATUS -> Notification.CATEGORY_STATUS
            else -> Notification.CATEGORY_REMINDER
        }
    }

    companion object {
        private const val TAG = "WhaleNotification"
        const val CAPABILITY = "phone.notification.post"

        /** 模块开关存储（DESIGN §2.2；与 TransportBridge 通用模块开关共用）。 */
        const val PREFS_NAME = "whale_modules"
        const val KEY_ENABLED = "notification_post_enabled"

        const val CHANNEL_REMINDER = "whale_reminder"
        const val CHANNEL_CALL = "whale_call"
        const val CHANNEL_STATUS = "whale_status"

        /** 无 tag 通知的稳定 id；有 tag 时用 tag 的 hash（update/cancel 幂等定位）。 */
        private const val DEFAULT_NOTIFICATION_ID = 42001

        fun notificationIdOf(tag: String?): Int = tag?.hashCode() ?: DEFAULT_NOTIFICATION_ID

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** Android 13+ POST_NOTIFICATIONS；更低版本看通知总开关。 */
        fun permissionGranted(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 33) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.areNotificationsEnabled()
            }

        /** UI 展示用权限状态。 */
        fun permissionStatus(context: Context): String =
            if (permissionGranted(context)) "granted" else "denied"

        /**
         * 发起运行时权限申请（仅 33+ 且未授予时弹系统框）。
         * 结果回调需 Activity 转发，当前桥不接 MainActivity，故为 fire-and-forget：
         * 返回申请发起时刻的状态，UI 侧延迟后重新查询。
         */
        fun requestPermission(activity: Activity?, context: Context): String {
            val status = permissionStatus(context)
            if (status != "granted" && activity != null && Build.VERSION.SDK_INT >= 33) {
                try {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE_POST_NOTIFICATIONS,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "权限申请发起失败", t)
                }
            }
            return status
        }

        private const val REQUEST_CODE_POST_NOTIFICATIONS = 0x5741 // 'WA'
    }
}
