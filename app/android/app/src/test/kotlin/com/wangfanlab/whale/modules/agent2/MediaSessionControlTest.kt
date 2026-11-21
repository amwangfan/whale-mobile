package com.wangfanlab.whale.modules.agent2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaSessionLogic] 纯逻辑单测：目标会话选择、状态名、摘要。
 * 选择策略保守——歧义一律 null，交由调用方诚实 failed/unavailable（#96 纪律：
 * 播放控制宁可不动作，不可盲控错误目标）。
 */
class MediaSessionControlTest {

    private fun session(
        pkg: String,
        state: Int,
        positionMs: Long = 83_000L,
        durationMs: Long = 613_000L,
        title: String = "示例视频",
    ) = MediaSessionControl.SessionInfo(pkg, state, positionMs, durationMs, title)

    // ---- selectTarget --------------------------------------------------

    @Test
    fun `empty sessions has no target`() {
        assertNull(MediaSessionLogic.selectTarget(emptyList(), "tv.danmaku.bili"))
        assertNull(MediaSessionLogic.selectTarget(emptyList(), null))
    }

    @Test
    fun `single session selected regardless of foreground`() {
        val s = session("tv.danmaku.bili", MediaSessionLogic.STATE_PLAYING)
        assertEquals(s, MediaSessionLogic.selectTarget(listOf(s), null))
        assertEquals(s, MediaSessionLogic.selectTarget(listOf(s), "com.other.app"))
    }

    @Test
    fun `playing foreground session wins over other playing`() {
        val fg = session("tv.danmaku.bili", MediaSessionLogic.STATE_PLAYING)
        val bg = session("com.music.app", MediaSessionLogic.STATE_PLAYING)
        assertEquals(fg, MediaSessionLogic.selectTarget(listOf(bg, fg), "tv.danmaku.bili"))
    }

    @Test
    fun `unique playing session wins when foreground ambiguous`() {
        val playing = session("tv.danmaku.bili", MediaSessionLogic.STATE_PLAYING)
        val paused = session("com.music.app", MediaSessionLogic.STATE_PAUSED)
        // 前台未知：唯一播放中胜出
        assertEquals(playing, MediaSessionLogic.selectTarget(listOf(paused, playing), null))
        // 前台既不是播放中会话：前台匹配（任意态）优先于全局播放中
        assertEquals(paused,
            MediaSessionLogic.selectTarget(listOf(paused, playing), "com.music.app"))
    }

    @Test
    fun `foreground paused session selected over nothing`() {
        val fg = session("tv.danmaku.bili", MediaSessionLogic.STATE_PAUSED)
        val other = session("com.music.app", MediaSessionLogic.STATE_PAUSED)
        assertEquals(fg, MediaSessionLogic.selectTarget(listOf(other, fg), "tv.danmaku.bili"))
    }

    @Test
    fun `multiple ambiguous sessions yield no target`() {
        // 两个播放中且前台不在其中 → 歧义，拒绝盲选
        val a = session("com.a", MediaSessionLogic.STATE_PLAYING)
        val b = session("com.b", MediaSessionLogic.STATE_PLAYING)
        assertNull(MediaSessionLogic.selectTarget(listOf(a, b), null))
        assertNull(MediaSessionLogic.selectTarget(listOf(a, b), "com.c"))
        // 两个都暂停且无前台匹配 → 歧义
        val c = session("com.a", MediaSessionLogic.STATE_PAUSED)
        val d = session("com.b", MediaSessionLogic.STATE_PAUSED)
        assertNull(MediaSessionLogic.selectTarget(listOf(c, d), null))
    }

    // ---- seek 目标解算（§5.1 增项 device.media.seek_to）-------------------

    @Test
    fun `resolve seek target prefers position_ms over ratio`() {
        // 审注①：position_ms 优先（ratio 依赖 duration 可得性）
        assertEquals(83_000L, MediaSessionLogic.resolveSeekTargetMs(83_000L, 0.5, 613_000L))
    }

    @Test
    fun `resolve seek target converts ratio with duration`() {
        assertEquals(306_500L, MediaSessionLogic.resolveSeekTargetMs(null, 0.5, 613_000L))
        assertEquals(0L, MediaSessionLogic.resolveSeekTargetMs(null, 0.0, 613_000L))
        assertEquals(613_000L, MediaSessionLogic.resolveSeekTargetMs(null, 1.0, 613_000L))
    }

    @Test
    fun `resolve seek target ratio without duration is honest null`() {
        // 审注③：duration 未知（会话未报告，-1/0）→ null，调用方诚实 failed，绝不估算
        assertNull(MediaSessionLogic.resolveSeekTargetMs(null, 0.5, -1L))
        assertNull(MediaSessionLogic.resolveSeekTargetMs(null, 0.5, 0L))
    }

    @Test
    fun `resolve seek target needs at least one argument`() {
        assertNull(MediaSessionLogic.resolveSeekTargetMs(null, null, 613_000L))
    }

    // ---- state / summary ------------------------------------------------

    @Test
    fun `state names and playing flag`() {
        assertTrue(MediaSessionLogic.isPlaying(MediaSessionLogic.STATE_PLAYING))
        assertFalse(MediaSessionLogic.isPlaying(MediaSessionLogic.STATE_PAUSED))
        assertEquals("播放中", MediaSessionLogic.stateName(MediaSessionLogic.STATE_PLAYING))
        assertEquals("已暂停", MediaSessionLogic.stateName(MediaSessionLogic.STATE_PAUSED))
        assertEquals("缓冲中", MediaSessionLogic.stateName(MediaSessionLogic.STATE_BUFFERING))
        assertEquals("其他(0)", MediaSessionLogic.stateName(0))
    }

    @Test
    fun `summary carries state progress package and title`() {
        val s = session("tv.danmaku.bili", MediaSessionLogic.STATE_PLAYING)
        assertEquals("播放中 83/613s tv.danmaku.bili 示例视频", MediaSessionLogic.summaryOf(s))
        // 无进度信息：只报状态+包名+标题
        val noPos = session("com.music.app", MediaSessionLogic.STATE_PAUSED,
            positionMs = -1L, durationMs = -1L, title = "")
        assertEquals("已暂停 com.music.app", MediaSessionLogic.summaryOf(noPos))
    }
}
