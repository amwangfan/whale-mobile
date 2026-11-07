package com.wangfanlab.whale.modules.notification2

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 通知权限检查与引导（修正方案 §3.2 / §11）。
 *
 * - Android 13+（33）：POST_NOTIFICATIONS 运行时权限。
 * - 更低版本：通知总开关（NotificationManagerCompat.areNotificationsEnabled）。
 * - 渠道级开关由 [NotificationChannelRegistry.isChannelEnabled] 单独判断。
 *
 * 状态上报：端侧校验链（NotificationEventHandler）在权限缺失时回 failed
 * （reason=permission_missing 记日志；receipt 协议字段无 error 位，仅 status=failed），
 * 绝不伪报 displayed。
 */
class NotificationPermissionManager(private val context: Context) {

    private val appContext = context.applicationContext

    /** 通知权限是否可用（33+ 运行时权限；33- 通知总开关）。 */
    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }

    /** UI 展示用状态：granted / denied。 */
    fun status(): String = if (isGranted()) STATUS_GRANTED else STATUS_DENIED

    /**
     * 发起运行时权限申请（仅 33+ 且未授予时弹系统框；Activity 结果由 UI 层转发）。
     * fire-and-forget：返回发起时刻状态，调用方延迟后重新查询（同 v1 语义）。
     */
    fun requestPermission(activity: Activity?): String {
        val current = status()
        if (current != STATUS_GRANTED && activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "权限申请发起失败", t)
            }
        }
        return current
    }

    /** 引导 intent：本应用通知设置页（用户被系统拒过后只能来这里开）。 */
    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 引导到单个渠道的设置页（例如审批渠道被用户关闭时引导开启）。 */
    fun channelSettingsIntent(channelId: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    companion object {
        private const val TAG = "WhaleNotif2Perm"
        const val STATUS_GRANTED = "granted"
        const val STATUS_DENIED = "denied"
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 0x5742 // 'WB'
    }
}
