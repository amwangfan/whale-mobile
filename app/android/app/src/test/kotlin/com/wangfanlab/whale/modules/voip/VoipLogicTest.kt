package com.wangfanlab.whale.modules.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoIP 模块纯逻辑单元测试（B4 前半）：
 * - phone.call.ring 参数白名单（号码正则/长度、名称截断、桥 host/port）
 * - 校验链顺序（全局暂停 → 模块开关默认关 → 权限 → 白名单）与 error_code
 * - call.invite 分发校验（schema: session_id/media_params/expires_at）
 * - CallSessionTracker 事件状态机（ringing/answered/ended + 时长 + 幂等）
 *
 * 全部为 JVM 测试，不触碰 Android 框架。
 */
class VoipLogicTest {

    // ------------------------------------------------------------ ring 白名单

    @Test
    fun `合法号码放行 - 数字 带加号 服务码`() {
        for (n in listOf("10086", "+8613800138000", "*21#", "1")) {
            val d = CallValidator.validateRingArgs(mapOf("number" to n))
            assertTrue("$n 应放行", d is RingDecision.Execute)
        }
    }

    @Test
    fun `号码白名单拒绝 - 空 字母 标点 超长`() {
        for (n in listOf("", "  ", "abc123", "138-0013-8000", "+86138001380001234567890123")) {
            val d = CallValidator.validateRingArgs(mapOf("number" to n))
            assertEquals("$n 应拒绝", "invalid_message", (d as RingDecision.Reject).errorCode)
        }
        // 缺 number 字段
        val d = CallValidator.validateRingArgs(emptyMap<String, Any?>())
        assertEquals("invalid_message", (d as RingDecision.Reject).errorCode)
    }

    @Test
    fun `name 省略回落默认 超长截断 40 字符`() {
        val d1 = CallValidator.validateRingArgs(mapOf("number" to "10086"))
        assertEquals("AI 助手", (d1 as RingDecision.Execute).spec.name)
        val d2 = CallValidator.validateRingArgs(
            mapOf("number" to "10086", "name" to "长".repeat(60)),
        )
        assertEquals(40, (d2 as RingDecision.Execute).spec.name.length)
    }

    @Test
    fun `桥参数 - 缺省回落默认 非法端口拒绝 空白 host 拒绝`() {
        val d1 = CallValidator.validateRingArgs(mapOf("number" to "10086"))
        val s1 = (d1 as RingDecision.Execute).spec
        assertEquals("127.0.0.1", s1.bridgeHost)
        assertEquals(18799, s1.bridgePort)

        val d2 = CallValidator.validateRingArgs(
            mapOf("number" to "10086", "bridge_host" to "100.64.0.1", "bridge_port" to 19000),
        )
        val s2 = (d2 as RingDecision.Execute).spec
        assertEquals("100.64.0.1", s2.bridgeHost)
        assertEquals(19000, s2.bridgePort)

        // 端口越界 / 0 / 负
        for (p in listOf(0, -1, 65536)) {
            val d = CallValidator.validateRingArgs(
                mapOf("number" to "10086", "bridge_port" to p),
            )
            assertEquals("port=$p 应拒绝", "invalid_message", (d as RingDecision.Reject).errorCode)
        }
        // host 含空白字符
        val d3 = CallValidator.validateRingArgs(
            mapOf("number" to "10086", "bridge_host" to "a b.com"),
        )
        assertEquals("invalid_message", (d3 as RingDecision.Reject).errorCode)
    }

    // ------------------------------------------------------------ 校验链顺序

    @Test
    fun `桥默认端点可注入（宿主模块卡配置生效）`() {
        val d = RingDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
            defaultBridgeHost = { "100.64.154.46" },
            defaultBridgePort = { 18799 },
        ).onRequest(mapOf("number" to "10086"))
        val spec = (d as RingDecision.Execute).spec
        assertEquals("100.64.154.46", spec.bridgeHost)
        assertEquals(18799, spec.bridgePort)
    }

    @Test
    fun `校验链 - 模块开关关 回 unavailable（宿主默认关）`() {
        val d = RingDecider(
            moduleEnabled = { false },
            permissionGranted = { true },
        ).onRequest(mapOf("number" to "10086"))
        assertEquals("unavailable", (d as RingDecision.Reject).errorCode)
    }

    @Test
    fun `校验链 - 全局暂停优先于模块开关 回 unavailable`() {
        val d = RingDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
            allPaused = { true },
        ).onRequest(mapOf("number" to "10086"))
        assertEquals("unavailable", (d as RingDecision.Reject).errorCode)
    }

    @Test
    fun `校验链 - 权限缺失 回 permission_missing`() {
        val d = RingDecider(
            moduleEnabled = { true },
            permissionGranted = { false },
        ).onRequest(mapOf("number" to "10086"))
        assertEquals("permission_missing", (d as RingDecision.Reject).errorCode)
    }

    @Test
    fun `校验链 - 全部通过 执行`() {
        val d = RingDecider(
            moduleEnabled = { true },
            permissionGranted = { true },
        ).onRequest(mapOf("number" to "10086"))
        assertTrue(d is RingDecision.Execute)
    }

    @Test
    fun `校验链 - 开关关时参数再合法也 unavailable 而非 invalid_message`() {
        // 顺序断言：开关校验先于参数白名单（不泄露参数校验细节给未授权方）
        val d = RingDecider(
            moduleEnabled = { false },
            permissionGranted = { false },
        ).onRequest(mapOf("number" to "!!!"))
        assertEquals("unavailable", (d as RingDecision.Reject).errorCode)
    }

    // ------------------------------------------------------------ call.invite 分发

    private fun invitePayload(
        sessionId: String = "01J0000000000000000000CALL",
        codec: String = "pcm_s16le",
        sampleRate: Int = 16000,
        channels: Int = 1,
        frameMs: Int? = 20,
        expiresAt: Long = 2_000_000L,
    ): Map<String, Any?> {
        val media = mutableMapOf<String, Any?>(
            "codec" to codec, "sample_rate" to sampleRate, "channels" to channels,
        )
        if (frameMs != null) media["frame_ms"] = frameMs
        return mapOf(
            "session_id" to sessionId,
            "media_params" to media,
            "expires_at" to expiresAt,
        )
    }

    @Test
    fun `call invite - 合法负载接受`() {
        val d = CallInviteLogic.validate(invitePayload(), nowMs = 1_000_000L)
        val a = d as InviteDecision.Accept
        assertEquals("01J0000000000000000000CALL", a.sessionId)
        assertEquals(2_000_000L, a.expiresAt)
        assertEquals(20, a.frameMs)
    }

    @Test
    fun `call invite - 媒体参数非 V1 固定值 忽略 unsupported_media`() {
        assertEquals(
            "unsupported_media",
            (CallInviteLogic.validate(
                invitePayload(codec = "opus"), 1_000_000L,
            ) as InviteDecision.Ignore).reason,
        )
        assertEquals(
            "unsupported_media",
            (CallInviteLogic.validate(
                invitePayload(sampleRate = 8000), 1_000_000L,
            ) as InviteDecision.Ignore).reason,
        )
        assertEquals(
            "unsupported_media",
            (CallInviteLogic.validate(
                invitePayload(channels = 2), 1_000_000L,
            ) as InviteDecision.Ignore).reason,
        )
        assertEquals(
            "unsupported_media",
            (CallInviteLogic.validate(
                invitePayload(frameMs = 30), 1_000_000L,
            ) as InviteDecision.Ignore).reason,
        )
    }

    @Test
    fun `call invite - 过期忽略（§6 过期不执行）`() {
        val d = CallInviteLogic.validate(
            invitePayload(expiresAt = 999_999L), nowMs = 1_000_000L,
        )
        assertEquals("expired", (d as InviteDecision.Ignore).reason)
        // 边界：expires_at == now 视为过期
        val d2 = CallInviteLogic.validate(
            invitePayload(expiresAt = 1_000_000L), nowMs = 1_000_000L,
        )
        assertEquals("expired", (d2 as InviteDecision.Ignore).reason)
    }

    @Test
    fun `call invite - 缺字段 非法 session_id 忽略 malformed`() {
        // 缺 session_id
        val p1 = invitePayload().toMutableMap().also { it.remove("session_id") }
        assertEquals(
            "malformed",
            (CallInviteLogic.validate(p1, 1_000_000L) as InviteDecision.Ignore).reason,
        )
        // session_id 过短（schema minLength 16）
        assertEquals(
            "malformed",
            (CallInviteLogic.validate(
                invitePayload(sessionId = "short"), 1_000_000L,
            ) as InviteDecision.Ignore).reason,
        )
        // 缺 media_params / 缺 expires_at
        val p2 = invitePayload().toMutableMap().also { it.remove("media_params") }
        assertEquals(
            "malformed",
            (CallInviteLogic.validate(p2, 1_000_000L) as InviteDecision.Ignore).reason,
        )
        val p3 = invitePayload().toMutableMap().also { it.remove("expires_at") }
        assertEquals(
            "malformed",
            (CallInviteLogic.validate(p3, 1_000_000L) as InviteDecision.Ignore).reason,
        )
        // 关键：schema 未定义 number/bridge_host 等字段，多余字段不影响分发决策
        val p4 = invitePayload().toMutableMap().also { it["number"] = "10086" }
        assertTrue(CallInviteLogic.validate(p4, 1_000_000L) is InviteDecision.Accept)
    }

    // ------------------------------------------------------------ 会话事件状态机

    @Test
    fun `会话事件 - ringing 到 answered 到 ended 含时长`() {
        var now = 1_000_000L
        val t = CallSessionTracker(clockMs = { now })
        t.bindSession("01J0000000000000000000CALL")

        val e1 = t.onRinging("13800138000", "Whale AI")!!
        assertEquals("call.state", e1.kind)
        assertTrue(e1.summary.contains("state=ringing"))
        assertTrue(e1.summary.contains("session=01J0000000000000000000CALL"))
        assertTrue("号码应脱敏", e1.summary.contains("***8000"))
        assertFalse("号码不应完整出现", e1.summary.contains("13800138000"))
        assertEquals("owner_private", e1.privacyClass)

        now += 5_000 // 5s 后接听
        val e2 = t.onActive()!!
        assertEquals("call.answered", e2.kind)
        assertTrue(e2.summary.contains("state=active"))

        now += 65_000 // 通话 65s
        val e3 = t.onEnded("local")!!
        assertEquals("call.ended", e3.kind)
        assertTrue(e3.summary.contains("state=ended"))
        assertTrue(e3.summary.contains("duration_sec=65"))
        assertTrue(e3.summary.contains("reason=local"))
    }

    @Test
    fun `会话事件 - 未接听挂断 reason 归为 rejected 时长 0`() {
        var now = 1_000_000L
        val t = CallSessionTracker(clockMs = { now })
        t.onRinging("10086", "AI 助手")
        now += 20_000
        val e = t.onEnded("local")!! // 响铃中本地挂断 = 未接/拒接
        assertTrue(e.summary.contains("duration_sec=0"))
        assertTrue(e.summary.contains("reason=rejected"))
    }

    @Test
    fun `会话事件 - ended 幂等 二次不再出事件`() {
        val t = CallSessionTracker(clockMs = { 1_000_000L })
        t.onRinging("10086", "AI 助手")
        t.onActive()
        assertTrue(t.onEnded("local") != null)
        assertNull(t.onEnded("local"))
        assertNull(t.onEnded("remote"))
    }

    @Test
    fun `会话事件 - 无会话绑定 无 session 片段`() {
        val t = CallSessionTracker(clockMs = { 1_000_000L })
        val e = t.onRinging("10086", "AI 助手")!!
        assertFalse(e.summary.contains("session="))
    }

    @Test
    fun `号码脱敏 - 短号全打码`() {
        assertEquals("***", CallSessionTracker.maskNumber("110"))
        assertEquals("***8000", CallSessionTracker.maskNumber("13800138000"))
        assertEquals("***0086", CallSessionTracker.maskNumber("+8610086"))
    }
}
