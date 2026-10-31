package com.wangfanlab.whale.modules.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 内置截屏采集模块门面（SM-01 影子模式，Issue #8；默认关）。
 *
 * - 模块开关 whale_modules/capture_enabled（默认 false）；一键暂停/锁屏/
 *   输入法/排除包名等护栏在 [ShadowCaptureService] tick 内前置拦截。
 * - **采集互斥**：检测到独立 ScreenMemo 的无障碍采集服务已启用时拒绝/停止
 *   本模块采集（conflict_standalone_screenmemo），同一时刻只有一套采集源。
 * - 影子模式只统计（成功率/延迟/重复率），位图不落盘不 OCR；见
 *   [ShadowCaptureService] 与 [CaptureStats]。
 */
object CaptureModule {

    const val PREFS_NAME = "whale_modules"
    const val KEY_ENABLED = "capture_enabled"

    /** 排除包名（whale_capture/excluded_packages，Set）。 */
    private const val EXCLUDED_PREFS = "whale_capture"
    private const val KEY_EXCLUDED = "excluded_packages"

    /** 独立 ScreenMemo 的无障碍服务组件前缀（采集互斥判定）。 */
    private const val SCREENMEMO_ACCESSIBILITY_PREFIX = "com.fqyw.screen_memo/"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun excludedPackages(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(EXCLUDED_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    /**
     * 独立 ScreenMemo 的无障碍采集是否已启用（启用即视为其在采集，触发互斥）。
     * 经 AccessibilityManager.getEnabledAccessibilityServiceList 查询，不读其数据。
     */
    fun standaloneScreenMemoActive(context: Context): Boolean = try {
        val am = context.applicationContext
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.id.startsWith(SCREENMEMO_ACCESSIBILITY_PREFIX) } == true
    } catch (t: Throwable) {
        false // 查询失败时按无冲突处理（采集仍受模块开关/暂停约束）
    }

    /** 本模块的无障碍服务是否已被系统启用。 */
    fun accessibilityGranted(context: Context): Boolean = try {
        val am = context.applicationContext
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val expectedPrefix = "${context.packageName}/"
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any {
                it.id.startsWith(expectedPrefix) &&
                    it.id.contains("ShadowCaptureService")
            } == true
    } catch (t: Throwable) {
        false
    }

    /** 「去授权」：跳系统无障碍设置页。 */
    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
        }
    }

    /** 模块状态（UI/诊断）：开关、权限、互斥、服务存活、影子统计。 */
    fun status(context: Context): Map<String, Any?> = mapOf(
        "enabled" to isEnabled(context),
        "accessibility_granted" to accessibilityGranted(context),
        "conflict_standalone" to standaloneScreenMemoActive(context),
        "service_running" to ShadowCaptureService.isRunning,
        "stats" to CaptureStats.snapshot(),
    )
}
