package com.wangfanlab.whale.runtime.v2

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台保活引导（案二持久化）：检测豁免态 + 引导主人开 Honor/安卓保活开关。
 *
 * 背景：MagicOS iaware 把后台 app 打进 net_cls=/bg_both_1 后台网络受限类（冻网非杀进程，
 * 11-22 实证 connect 线程跑了也发不出 SYN）。前台服务也冻。无纯代码免疫（非系统签名）。
 * 有效组合=应用启动管理手动模式（用户开关）+电池优化白名单+前台服务（NodeServiceV2）。
 * 本类只做「检测+引导」（弹系统请求/深链设置页），不代办用户授权。
 */
object BackgroundKeepalive {
    private const val TAG = "WhaleKeepalive"
    /** Honor/EMUI 应用启动管理页（自启动/关联启动/后台活动手动管理）。 */
    private const val HONOR_STARTUP_PKG = "com.hihonor.systemmanager"
    private const val HONOR_STARTUP_CLS =
        "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"

    /** 电池优化是否已豁免（doze 白名单）。 */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** v2 前台服务是否在跑。 */
    fun isForegroundServiceRunning(): Boolean = NodeServiceV2.isRunning

    /** 保活状态快照（UI 显示用）。 */
    fun status(context: Context): Map<String, Any?> = mapOf(
        "battery_exempt" to isBatteryOptimizationIgnored(context),
        "foreground_service" to isForegroundServiceRunning(),
    )

    /** 弹系统「忽略电池优化」请求对话框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
    fun requestBatteryExemption(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "requestBatteryExemption failed", t)
            false
        }
    }

    /**
     * 深链到应用启动管理页（Honor StartupAppControlActivity；失败回退应用详情设置页）。
     * 主人在该页把 whale 关自动管理→手动允许自启动/关联启动/后台活动。
     */
    fun openLaunchManagement(context: Context): Boolean {
        // 首选 Honor 应用启动管理（HSM_STARTUPAPP_MANAGER 语义）。
        val honor = Intent().apply {
            component = ComponentName(HONOR_STARTUP_PKG, HONOR_STARTUP_CLS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(context, honor)) return true
        // 回退：应用详情设置页（各机型通用，用户可自行找电池/启动管理入口）。
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStart(context, detail)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "activity not found: ${intent.component}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
            false
        }
    }
}
