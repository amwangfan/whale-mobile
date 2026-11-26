package com.wangfanlab.whale.modules.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 来电 UI 匿名化与字段穿透门测试（主人令 2026-11-26，B 反转）：
 * - 号码：原样透传（含 "0"）；缺席按开关显 "0"/全空（一行可切）
 * - 名称：UI 恒为通用标签「来电」，真实名（如 payload name=鲸鱼）永不进 UI
 * - invite 链 name 穿透门：显式空串→穿透（匿名）；仅缺席/null→null（调用方回落默认）
 * 全部 JVM 纯逻辑，不触碰 Android 框架。
 */
class IncomingDisplayPolicyTest {

    // ------------------------------------------------------------ 号码

    @Test
    fun `号码 0 原样显示 真实串透传`() {
        assertEquals("0", IncomingDisplayPolicy.sanitizeNumber("0"))
        assertEquals("10086", IncomingDisplayPolicy.sanitizeNumber("10086"))
        assertEquals("+8613800138000", IncomingDisplayPolicy.sanitizeNumber(" +8613800138000 "))
    }

    @Test
    fun `号码缺席 默认显 0 开关关掉全空`() {
        assertEquals("0", IncomingDisplayPolicy.sanitizeNumber(null))
        assertEquals("0", IncomingDisplayPolicy.sanitizeNumber(""))
        assertEquals("0", IncomingDisplayPolicy.sanitizeNumber("   "))
        // 一行可切的另一形态（等主人确认）
        assertEquals("", IncomingDisplayPolicy.sanitizeNumber(null, showZeroWhenAbsent = false))
        assertEquals("", IncomingDisplayPolicy.sanitizeNumber("  ", showZeroWhenAbsent = false))
    }

    // ------------------------------------------------------------ 名称（UI 恒匿名）

    @Test
    fun `名称恒为通用标签 - 真实名 空名 缺席同形`() {
        assertEquals("来电", IncomingDisplayPolicy.UI_NAME_LABEL)
        // 真实名不显（即使 payload 带 name=鲸鱼）
        assertEquals("来电", IncomingDisplayPolicy.displayNameForUi("鲸鱼"))
        assertEquals("来电", IncomingDisplayPolicy.displayNameForUi("Whale AI"))
        // 空名不显（无真实名可显，统一标签）
        assertEquals("来电", IncomingDisplayPolicy.displayNameForUi(""))
        assertEquals("来电", IncomingDisplayPolicy.displayNameForUi(null))
    }

    // ------------------------------------------------------------ invite name 穿透门

    @Test
    fun `invite name - 显式空串穿透 缺席回落 null`() {
        // 显式空串 → 穿透为 ""（匿名语义，不回落默认名）
        assertEquals("", CallInviteLogic.displayName(mapOf("name" to "")))
        assertEquals("", CallInviteLogic.displayName(mapOf("name" to "   ")))
        // 仅字段缺席/null → null（调用方回落默认）
        assertNull(CallInviteLogic.displayName(emptyMap<String, Any?>()))
        assertNull(CallInviteLogic.displayName(mapOf("name" to null)))
        assertNull(CallInviteLogic.displayName(null))
    }

    @Test
    fun `invite name - 实名原样 超长截断 40`() {
        assertEquals("鲸鱼", CallInviteLogic.displayName(mapOf("name" to "鲸鱼")))
        assertEquals("鲸鱼", CallInviteLogic.displayName(mapOf("name" to "  鲸鱼  ")))
        assertEquals(40, CallInviteLogic.displayName(mapOf("name" to "长".repeat(60)))!!.length)
        // 非字符串类型视为缺席
        assertNull(CallInviteLogic.displayName(mapOf("name" to 42)))
    }
}
