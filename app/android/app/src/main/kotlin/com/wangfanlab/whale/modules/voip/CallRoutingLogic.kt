package com.wangfanlab.whale.modules.voip

/**
 * VoIP 通话路由纯逻辑层（来电 UI 路由 + 拨号重定向决策）：不依赖任何 Android 类，
 * 可在本地 JUnit（JVM）下直接测试。Android 胶水见：
 * - java/com.wangfanlab.whale.voip/CallAccountHelper.java（账户注册/来电上报）
 * - java/com.wangfanlab.whale.voip/FakeConnectionService.java（Connection 创建）
 * - java/com.wangfanlab.whale.voip/WhaleCallRedirectionService.java（拨号重定向）
 *
 * 背景（HANDOFF 路A）：whale 来电以 managed(CallProvider) PhoneAccount 上报 Telecom
 * → Telecom 绑定原厂 com.android.incallui → 自动获得荣耀来电动画+系统默认铃声，
 * whale 不再自绘来电 UI、不再自播铃声。账户停用或未启用时回落 self-managed 旧路径。
 */

/** PhoneAccount / 组件标识常量（const val，Java 侧直接以静态字段引用，编译期内联）。 */
object VoipAccounts {
    const val WHALE_PACKAGE = "com.wangfanlab.whale"
    const val CONNECTION_SERVICE_CLASS = "com.wangfanlab.whale.voip.FakeConnectionService"

    /** 既有 self-managed 账户（whale 自绘来电 UI 的回落路径）。 */
    const val ID_SELF_MANAGED = "nova-fake-call"

    /** 既有 managed CallProvider（拨 1 打 AI，系统通话界面；限号 "1"）。 */
    const val ID_AI_OUT = "nova-ai-out"

    /**
     * 新增 managed(CallProvider) 来电/重定向承接账户（路A 主路径）：
     * 来电上报走它 → 原厂 InCallUI 接管；拨号重定向也指向它（号码不限，
     * 与「拨 1 打 AI」的 ID_AI_OUT 限号语义隔离）。
     */
    const val ID_MANAGED_IN = "whale-in-managed"
}

/** 来电 UI 路由决策：原厂 InCallUI（managed）或 whale 自绘（self-managed）。 */
object IncomingRoutePolicy {
    /**
     * managed 账户已启用 → 走原厂 InCallUI（系统渲染动画+铃声）。
     * 未启用/被停用 → 回落 self-managed（whale 自绘 + 自播铃声 + 全屏通知）。
     */
    @JvmStatic
    fun useStockInCallUi(managedAccountEnabled: Boolean): Boolean = managedAccountEnabled

    /** 仅 self-managed 路径需要 whale 自播铃声（managed 由系统响默认铃声）。 */
    @JvmStatic
    fun shouldPlayOwnRingtone(managedAccountEnabled: Boolean): Boolean = !managedAccountEnabled

    /** 仅 self-managed 路径需要 whale 自发全屏来电通知（managed 由系统出 UI）。 */
    @JvmStatic
    fun shouldPostOwnNotification(managedAccountEnabled: Boolean): Boolean = !managedAccountEnabled
}

/** 拨号重定向决策结果。 */
sealed interface RedirectDecision {
    /** 重定向到指定 whale managed 账户（号码原样携带）。 */
    data class Redirect(val targetAccountId: String) : RedirectDecision

    /** 不接管，原样下发（用户走 SIM/原账户正常拨号）。 */
    data object PassThrough : RedirectDecision
}

/** 重定向候选账户的纯数据视图（由 Telecom PhoneAccountHandle 适配而来）。 */
data class RedirectCandidate(
    val packageName: String,
    val className: String,
    val accountId: String,
)

object RedirectionLogic {
    /**
     * 拨号→VoIP 重定向决策：
     * - 仅当 whale 自己的 managed 承接账户（[VoipAccounts.ID_MANAGED_IN]）出现在
     *   Telecom 返回的 call-capable 列表中（=已启用）才接管；
     * - whale 的 self-managed / 拨1账户 / 任何他包账户（SIM 等）一律不接管；
     * - 发起方已是 whale 自己（whale 内部 placeCall，如拨 1 打 AI）→ 不接管，
     *   避免自我拦截/循环。
     *
     * 安全语义：拿不准一律 PassThrough，绝不让正常拨号落入失败路径。
     */
    @JvmStatic
    fun decide(
        candidates: List<RedirectCandidate>,
        initiatedByWhale: Boolean,
    ): RedirectDecision {
        if (initiatedByWhale) return RedirectDecision.PassThrough
        val found = candidates.any {
            it.packageName == VoipAccounts.WHALE_PACKAGE &&
                it.className == VoipAccounts.CONNECTION_SERVICE_CLASS &&
                it.accountId == VoipAccounts.ID_MANAGED_IN
        }
        return if (found) {
            RedirectDecision.Redirect(VoipAccounts.ID_MANAGED_IN)
        } else {
            RedirectDecision.PassThrough
        }
    }
}
