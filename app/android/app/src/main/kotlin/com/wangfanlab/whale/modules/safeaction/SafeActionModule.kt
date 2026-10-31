package com.wangfanlab.whale.modules.safeaction

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.wangfanlab.whale.transport.TransportBridge
import com.wangfanlab.whale.transport.WssClient

/**
 * 受控设备动作模块（阶段二 B3 前半：phone.device.safe_action）。
 *
 * - 注册为 `phone.device.safe_action` 的 [WssClient.ActionExecutor]；
 *   WssClient 过完传输层校验（验签/幂等/有效期/scope）后调用 [execute]，
 *   本模块再过端侧校验链（[SafeActionDecider]：暂停→开关→白名单→共享频控），
 *   终态经 reply 精确回执（executed / failed+error_code）。
 * - launch_app：仅允许已安装且带 LAUNCHER 入口的 App
 *   （PackageManager queryLaunchables 校验；设置/安全中心类包名在逻辑层拒绝）；
 *   经 getLaunchIntentForPackage 启动（等价用户点击图标，无组件导出授权）。
 * - open_deeplink：仅 http/https scheme（intent://、file:// 等已拒绝），
 *   ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK；不附加任何 extras/授权标志。
 */
class SafeActionModule(
    private val context: Context,
    private val decider: SafeActionDecider = SafeActionDecider(
        moduleEnabled = { isEnabled(context.applicationContext) },
        allPaused = { TransportBridge.isAllPaused(context.applicationContext) },
        isLaunchable = { pkg -> queryLaunchable(context.applicationContext, pkg) },
    ),
    /** 执行出口（测试可替换）；默认走系统 Intent。 */
    private val launcher: (SafeActionSpec) -> Unit = { spec ->
        doExecute(context.applicationContext, spec)
    },
) : WssClient.ActionExecutor {

    /** WssClient executor 线程调用；同步执行，经 reply 回终态（executed/failed）。 */
    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?>
        when (val decision = decider.onRequest(args)) {
            is SafeActionDecision.Reject -> {
                Log.w(TAG, "safe_action 校验未通过: ${decision.errorCode}")
                reply("failed", decision.errorCode, null)
            }

            is SafeActionDecision.Execute -> try {
                val launchStartMs = System.currentTimeMillis()
                launcher(decision.spec)
                // 诚实回执：launch_app 需证实前台化，否则按背景拦截上报。
                if (decision.spec.op == SafeActionOp.LAUNCH_APP) {
                    val pkg = decision.spec.packageName!!
                    when (verifyForeground(context, pkg, launchStartMs)) {
                        ForegroundVerdict.VERIFIED ->
                            reply("executed", null, "foreground_verified")
                        ForegroundVerdict.BLOCKED ->
                            reply("failed", "background_restricted", null)
                        ForegroundVerdict.UNVERIFIABLE ->
                            reply("executed", null, "unverified_no_usage_access")
                    }
                } else {
                    reply("executed", null, null)
                }
            } catch (e: android.content.ActivityNotFoundException) {
                // 目标可被校验但运行时无处理器（浏览器被禁用/竞态卸载）→ 精确回执
                Log.w(TAG, "safe_action 无可用处理器", e)
                reply("failed", "unavailable", null)
            } catch (t: Throwable) {
                Log.e(TAG, "safe_action 执行异常", t)
                reply("failed", "internal", null)
            }
        }
    }

    private enum class ForegroundVerdict { VERIFIED, BLOCKED, UNVERIFIABLE }

    /** 有「使用情况访问」时轮询证实目标 App 前台化（≤2s）；无权限则标记不可证实。 */
    private fun verifyForeground(
        context: Context, pkg: String, sinceMs: Long,
    ): ForegroundVerdict {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as? android.app.AppOpsManager ?: return ForegroundVerdict.UNVERIFIABLE
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return ForegroundVerdict.UNVERIFIABLE
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName,
        )
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) return ForegroundVerdict.UNVERIFIABLE
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                sinceMs - 10_000, System.currentTimeMillis(),
            )
            val hit = stats?.firstOrNull {
                it.packageName == pkg && it.lastTimeUsed >= sinceMs
            }
            if (hit != null) return ForegroundVerdict.VERIFIED
            Thread.sleep(150)
        }
        return ForegroundVerdict.BLOCKED
    }

    companion object {
        private const val TAG = "WhaleSafeAction"
        const val CAPABILITY = "phone.device.safe_action"

        /** 模块开关（whale_modules/safe_action_enabled，默认 false）。 */
        const val PREFS_NAME = "whale_modules"
        const val KEY_ENABLED = "safe_action_enabled"

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** PM queryLaunchables 校验：已安装且带 MAIN+LAUNCHER 入口。 */
        fun queryLaunchable(context: Context, packageName: String): Boolean = try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(
                    intent, PackageManager.ResolveInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            resolved.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "queryLaunchable 异常: $packageName", t)
            false
        }

        private fun doExecute(context: Context, spec: SafeActionSpec) {
            when (spec.op) {
                SafeActionOp.LAUNCH_APP -> {
                    val pkg = spec.packageName
                        ?: throw android.content.ActivityNotFoundException("missing package")
                    // 校验链已保证 launchable；此处仍防空转（竞态卸载）→ unavailable。
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(pkg)
                        ?: throw android.content.ActivityNotFoundException(
                            "launch intent vanished: $pkg",
                        )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                SafeActionOp.OPEN_DEEPLINK -> {
                    val url = spec.url
                        ?: throw android.content.ActivityNotFoundException("missing url")
                    // scheme 已限 http/https；不带 extras，不授予任何组件导出。
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) == null) {
                        throw android.content.ActivityNotFoundException("no handler: $url")
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}
