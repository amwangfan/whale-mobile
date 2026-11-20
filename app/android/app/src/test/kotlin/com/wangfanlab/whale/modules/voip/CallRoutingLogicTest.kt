package com.wangfanlab.whale.modules.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话路由纯逻辑单元测试（HANDOFF 路A + 拨号重定向）：
 * - IncomingRoutePolicy：managed 启用 → 原厂 InCallUI（不自播铃/不自发通知）；
 *   停用 → self-managed 回落（自播铃+全屏通知）
 * - RedirectionLogic：仅 whale-in-managed 已启用才接管；whale 自发起/他包/空列表
 *   一律 PassThrough（绝不让正常拨号落入失败路径）
 * - VoipAccounts 常量钉住（与 Java 侧 CallAccountHelper 编译期同源）
 *
 * 全部为 JVM 测试，不触碰 Android 框架。
 */
class CallRoutingLogicTest {

    private val whaleManaged = RedirectCandidate(
        packageName = "com.wangfanlab.whale",
        className = "com.wangfanlab.whale.voip.FakeConnectionService",
        accountId = "whale-in-managed",
    )
    private val whaleSelfManaged = whaleManaged.copy(accountId = "nova-fake-call")
    private val whaleAiOut = whaleManaged.copy(accountId = "nova-ai-out")
    private val simAccount = RedirectCandidate(
        packageName = "com.android.phone",
        className = "com.android.services.telephony.TelephonyConnectionService",
        accountId = "sim1",
    )

    // ------------------------------------------------------------ 常量钉住

    @Test
    fun `账户常量与 Java 侧同源钉住`() {
        assertEquals("com.wangfanlab.whale", VoipAccounts.WHALE_PACKAGE)
        assertEquals(
            "com.wangfanlab.whale.voip.FakeConnectionService",
            VoipAccounts.CONNECTION_SERVICE_CLASS,
        )
        assertEquals("nova-fake-call", VoipAccounts.ID_SELF_MANAGED)
        assertEquals("nova-ai-out", VoipAccounts.ID_AI_OUT)
        assertEquals("whale-in-managed", VoipAccounts.ID_MANAGED_IN)
    }

    // ------------------------------------------------------------ 来电 UI 路由

    @Test
    fun `managed 启用 - 走原厂 InCallUI 不自播铃不自发通知`() {
        assertTrue(IncomingRoutePolicy.useStockInCallUi(managedAccountEnabled = true))
        assertFalse(IncomingRoutePolicy.shouldPlayOwnRingtone(managedAccountEnabled = true))
        assertFalse(IncomingRoutePolicy.shouldPostOwnNotification(managedAccountEnabled = true))
    }

    @Test
    fun `managed 停用 - 回落 self-managed 自播铃自发全屏通知`() {
        assertFalse(IncomingRoutePolicy.useStockInCallUi(managedAccountEnabled = false))
        assertTrue(IncomingRoutePolicy.shouldPlayOwnRingtone(managedAccountEnabled = false))
        assertTrue(IncomingRoutePolicy.shouldPostOwnNotification(managedAccountEnabled = false))
    }

    // ------------------------------------------------------------ 拨号重定向

    @Test
    fun `whale managed 承接账户在列 - 重定向到它`() {
        val d = RedirectionLogic.decide(
            candidates = listOf(simAccount, whaleManaged),
            initiatedByWhale = false,
        )
        assertEquals(RedirectDecision.Redirect("whale-in-managed"), d)
    }

    @Test
    fun `whale 自发起呼叫 - 不接管防自我拦截`() {
        val d = RedirectionLogic.decide(
            candidates = listOf(whaleManaged, simAccount),
            initiatedByWhale = true,
        )
        assertEquals(RedirectDecision.PassThrough, d)
    }

    @Test
    fun `仅 self-managed 或拨1账户在列 - 不接管`() {
        for (candidates in listOf(
            listOf(whaleSelfManaged),
            listOf(whaleAiOut),
            listOf(whaleSelfManaged, whaleAiOut, simAccount),
        )) {
            val d = RedirectionLogic.decide(candidates, initiatedByWhale = false)
            assertEquals("$candidates 应放行", RedirectDecision.PassThrough, d)
        }
    }

    @Test
    fun `他包账户或空列表 - 不接管`() {
        assertEquals(
            RedirectDecision.PassThrough,
            RedirectionLogic.decide(listOf(simAccount), initiatedByWhale = false),
        )
        assertEquals(
            RedirectDecision.PassThrough,
            RedirectionLogic.decide(emptyList(), initiatedByWhale = false),
        )
    }

    @Test
    fun `包名或类名不符的仿冒账户 - 不接管`() {
        val fakePkg = whaleManaged.copy(packageName = "com.evil.fake")
        val fakeCls = whaleManaged.copy(className = "com.wangfanlab.whale.voip.FakeService")
        for (c in listOf(fakePkg, fakeCls)) {
            val d = RedirectionLogic.decide(listOf(c), initiatedByWhale = false)
            assertEquals("$c 应放行", RedirectDecision.PassThrough, d)
        }
    }
}
