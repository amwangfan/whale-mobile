package com.wangfanlab.whale.modules.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** core#58 自主体校验链单测。 */
class AgentLogicTest {

    private fun validate(
        capability: String,
        args: Map<String, Any?>? = emptyMap(),
        moduleEnabled: Boolean = true,
        rootAuthorized: Boolean = true,
        allPaused: Boolean = false,
        estop: Boolean = false,
        recent: List<Long> = emptyList(),
        now: Long = 100_000L,
    ) = AgentLogic.validate(
        capability, args, moduleEnabled, rootAuthorized, allPaused, estop, recent, now,
    )

    @Test
    fun `紧急停止与暂停 fail-closed`() {
        assertEquals("unavailable",
            (validate(AgentLogic.CAP_CONTROL, estop = true) as AgentLogic.Decision.Reject).errorCode)
        assertEquals("unavailable",
            (validate(AgentLogic.CAP_CONTROL, allPaused = true) as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `模块默认关`() {
        assertEquals("module_disabled",
            (validate(AgentLogic.CAP_CONTROL, moduleEnabled = false)
                    as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `shell 需持续 Root 授权`() {
        assertEquals("scope_denied",
            (validate(AgentLogic.CAP_SHELL,
                args = mapOf("command" to "id"), rootAuthorized = false)
                    as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `control 动作白名单形状`() {
        assertEquals("invalid_message",
            (validate(AgentLogic.CAP_CONTROL, args = mapOf("action" to "wipe"))
                    as AgentLogic.Decision.Reject).errorCode)
        assertTrue(validate(AgentLogic.CAP_CONTROL,
            args = mapOf("action" to "tap", "x" to 100, "y" to 200))
            is AgentLogic.Decision.Run)
    }

    @Test
    fun `坐标越界拒绝`() {
        assertEquals("invalid_message",
            (validate(AgentLogic.CAP_CONTROL,
                args = mapOf("action" to "tap", "x" to -1, "y" to 100))
                    as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `shell 空命令与超时上限`() {
        assertEquals("invalid_message",
            (validate(AgentLogic.CAP_SHELL, args = mapOf("command" to "  "))
                    as AgentLogic.Decision.Reject).errorCode)
        assertEquals("invalid_message",
            (validate(AgentLogic.CAP_SHELL,
                args = mapOf("command" to "id", "timeout_s" to 999))
                    as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `频控`() {
        val recent = (1..AgentLogic.SHELL_PER_MIN).map { 100_000L - it * 1000L }
        assertEquals("rate_limited",
            (validate(AgentLogic.CAP_SHELL, args = mapOf("command" to "id"), recent = recent)
                    as AgentLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `结果摘要有界截断`() {
        val long = "x".repeat(5000)
        val t = AgentLogic.truncateSummary(long)
        assertTrue(t.length <= AgentLogic.MAX_OUTPUT_CHARS)
        assertTrue(t.endsWith("[truncated]"))
    }
}
