package com.wangfanlab.whale.modules.safeaction

import com.wangfanlab.whale.modules.notifications.SlidingWindowRateLimiter

/**
 * phone.device.safe_action 纯逻辑层（阶段二 B3 前半：受控设备动作）：
 * 不依赖任何 Android 类，可在本地 JUnit（JVM）下直接测试。
 * Android 胶水见 SafeActionModule.kt。
 *
 * 校验链顺序（与 RingDecider 同构，全部通过才执行）：
 *   0. 全局暂停（whale_modules/all_paused → unavailable）
 *   1. 模块开关（whale_modules/safe_action_enabled，默认 false → unavailable）
 *   2. 无特殊系统权限项（launch_app/open_deeplink 均为普通 Intent，无运行时权限；
 *      占位一步与链式文档对齐，不做门）
 *   3. 参数白名单（action 枚举 / 包名格式 / 敏感包拒绝 / scheme 仅 http(s) /
 *      launchable 校验由注入谓词完成，胶水层=PackageManager queryLaunchables）
 *   4. 频控（launch_app 与 open_deeplink 共享 6 次/分钟滑动窗口）
 *
 * 终态回执 error_code 语义（contracts/v1 §4 错误码全集内）：
 *   unavailable   —— 全局暂停/模块关/目标 App 未安装或无 LAUNCHER 入口
 *   invalid_message —— 参数缺失/畸形（未知 action、包名格式错、URL 为空/无 scheme）
 *   scope_denied  —— 策略拒绝（敏感包名；intent://、file:// 等非 http(s) scheme）
 *   rate_limited  —— 共享频控超限
 *   internal      —— 执行期异常（胶水层兜底）
 */

/** 动作枚举（args.action）。 */
enum class SafeActionOp { LAUNCH_APP, OPEN_DEEPLINK }

/** 校验通过后的执行规格。 */
data class SafeActionSpec(
    val op: SafeActionOp,
    val packageName: String?,
    val url: String?,
)

/** 校验/决策结果：执行，或带 error_code 拒绝（终态 failed）。 */
sealed interface SafeActionDecision {
    data class Execute(val spec: SafeActionSpec) : SafeActionDecision

    /** errorCode 对应 action.receipt payload.error_code（§4）。 */
    data class Reject(val errorCode: String) : SafeActionDecision
}

/** 参数白名单（phone.device.safe_action args）。 */
object SafeActionValidator {
    const val MAX_PACKAGE_LEN = 128
    const val MAX_URL_LEN = 2048

    /** Android 包名格式（段以字母开头，字母/数字/下划线；至少两段）。 */
    val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * 敏感包名拒绝表（设置/安全中心类；精确匹配 + com.android.settings 子包前缀）。
     * 命中 → scope_denied（策略拒绝，非消息畸形）。
     */
    val DENIED_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.samsung.android.lool",
    )

    fun isSensitivePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in DENIED_PACKAGES || p.startsWith("com.android.settings")
    }

    fun validate(
        args: Map<*, *>?,
        isLaunchable: (String) -> Boolean,
    ): SafeActionDecision {
        val action = (args?.get("action") as? String)?.trim()?.lowercase()
        return when (action) {
            "launch_app" -> validateLaunchApp(args, isLaunchable)
            "open_deeplink" -> validateDeeplink(args)
            else -> SafeActionDecision.Reject("invalid_message")
        }
    }

    private fun validateLaunchApp(
        args: Map<*, *>?,
        isLaunchable: (String) -> Boolean,
    ): SafeActionDecision {
        val pkg = (args?.get("package") as? String)?.trim()
        if (pkg.isNullOrEmpty() || pkg.length > MAX_PACKAGE_LEN ||
            !PACKAGE_REGEX.matches(pkg)
        ) {
            return SafeActionDecision.Reject("invalid_message")
        }
        if (isSensitivePackage(pkg)) return SafeActionDecision.Reject("scope_denied")
        // 仅允许已安装且带 LAUNCHER 入口的 App（胶水层：PM queryLaunchables）
        if (!isLaunchable(pkg)) return SafeActionDecision.Reject("unavailable")
        return SafeActionDecision.Execute(
            SafeActionSpec(SafeActionOp.LAUNCH_APP, packageName = pkg, url = null),
        )
    }

    private fun validateDeeplink(args: Map<*, *>?): SafeActionDecision {
        val url = (args?.get("url") as? String)?.trim()
        if (url.isNullOrEmpty() || url.length > MAX_URL_LEN) {
            return SafeActionDecision.Reject("invalid_message")
        }
        // scheme 提取：首个 ':' 前的部分；无 scheme（含裸 host）→ 畸形
        val colon = url.indexOf(':')
        if (colon <= 0) return SafeActionDecision.Reject("invalid_message")
        val scheme = url.substring(0, colon).lowercase()
        // 仅 http/https；intent://、file://、javascript: 等一律策略拒绝
        if (scheme != "http" && scheme != "https") {
            return SafeActionDecision.Reject("scope_denied")
        }
        // http(s) 必须带 authority（防 "https:" 裸 scheme 触发意外解析）
        if (!url.substring(colon + 1).startsWith("//") ||
            url.substring(colon + 3).isEmpty()
        ) {
            return SafeActionDecision.Reject("invalid_message")
        }
        return SafeActionDecision.Execute(
            SafeActionSpec(SafeActionOp.OPEN_DEEPLINK, packageName = null, url = url),
        )
    }
}

/**
 * 校验链编排（与 RingDecider 同构）：暂停 → 开关 →（无特殊权限项）→ 白名单 → 频控。
 * 频控为 launch_app/open_deeplink **共享**同一滑动窗口（6 次/分钟）。
 */
class SafeActionDecider(
    private val moduleEnabled: () -> Boolean,
    private val allPaused: () -> Boolean = { false },
    private val isLaunchable: (String) -> Boolean = { false },
    private val rateLimiter: SlidingWindowRateLimiter =
        SlidingWindowRateLimiter(RATE_LIMIT_MAX, RATE_LIMIT_WINDOW_MS),
) {
    fun onRequest(args: Map<*, *>?): SafeActionDecision {
        // 0. 全局暂停（A1）
        if (allPaused()) return SafeActionDecision.Reject("unavailable")
        // 1. 模块开关（默认 false）
        if (!moduleEnabled()) return SafeActionDecision.Reject("unavailable")
        // 2. 无特殊系统权限项（launch/deeplink 均普通 Intent，无运行时权限；占位对齐）
        // 3. 参数白名单
        val decision = SafeActionValidator.validate(args, isLaunchable)
        if (decision !is SafeActionDecision.Execute) return decision
        // 4. 共享频控（两动作合计 6 次/分钟）
        if (!rateLimiter.tryAcquire()) return SafeActionDecision.Reject("rate_limited")
        return decision
    }

    companion object {
        const val RATE_LIMIT_MAX = 6
        const val RATE_LIMIT_WINDOW_MS = 60_000L
    }
}
