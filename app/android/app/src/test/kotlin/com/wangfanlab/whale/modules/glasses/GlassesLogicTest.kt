package com.wangfanlab.whale.modules.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GL-01 眼镜 HUD 桥纯逻辑单测。 */
class GlassesLogicTest {

    private fun validate(
        args: Map<String, Any?> = mapOf(
            "card_id" to "card-0001", "title" to "课程提醒", "body" to "10 分钟后上课",
        ),
        allPaused: Boolean = false,
        moduleEnabled: Boolean = true,
        recent: List<Long> = emptyList(),
        now: Long = 100_000L,
    ) = GlassesLogic.validateShow(args, allPaused, moduleEnabled, recent, now)

    @Test
    fun `正常 show 通过且字段截断受控`() {
        val d = validate()
        assertTrue(d is GlassesLogic.Decision.Show)
        val longArgs = mapOf(
            "card_id" to "card-0002",
            "title" to "t".repeat(100),
            "body" to "b".repeat(300),
        )
        val s = validate(args = longArgs) as GlassesLogic.Decision.Show
        assertEquals(GlassesLogic.MAX_TITLE_CHARS, s.card.title.length)
        assertEquals(GlassesLogic.MAX_BODY_CHARS, s.card.body.length)
    }

    @Test
    fun `暂停与模块关闭 fail-closed`() {
        assertEquals(
            "unavailable",
            (validate(allPaused = true) as GlassesLogic.Decision.Reject).errorCode,
        )
        assertEquals(
            "module_disabled",
            (validate(moduleEnabled = false) as GlassesLogic.Decision.Reject).errorCode,
        )
    }

    @Test
    fun `owner_sensitive 默认不上镜`() {
        val d = validate(
            args = mapOf(
                "card_id" to "card-0003", "title" to "私密",
                "privacy_class" to "owner_sensitive",
            ),
        )
        assertEquals("scope_denied", (d as GlassesLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `频控 6 次每分钟`() {
        val now = 100_000L
        val recent = (1..6).map { now - it * 5000L }
        val d = validate(recent = recent, now = now)
        assertEquals("rate_limited", (d as GlassesLogic.Decision.Reject).errorCode)
    }

    @Test
    fun `card_id 非法拒绝`() {
        assertEquals(
            "invalid_message",
            (validate(args = mapOf("card_id" to "x")) as GlassesLogic.Decision.Reject).errorCode,
        )
    }

    @Test
    fun `标题正文皆空拒绝`() {
        val d = validate(args = mapOf("card_id" to "card-0009", "title" to "", "body" to ""))
        assertEquals("invalid_message", (d as GlassesLogic.Decision.Reject).errorCode)
    }
}
