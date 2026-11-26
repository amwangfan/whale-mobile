package com.wangfanlab.whale.voip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AudioBridge 媒体凭据决策纯逻辑测试（契约 media_endpoint=(b)）：
 * 会话凭据（首帧 AUTH 载荷=session_id，不落盘）优先；空白回落 BridgePrefs
 * 独立媒体 token（调试路）；两者皆空 → 空串（调用方 fail-closed）。
 * JVM 测试，不触碰 Android 框架（resolveAuthToken 为纯函数）。
 */
class AudioBridgeAuthTest {

    @Test
    fun `会话凭据优先于 prefs token`() {
        assertEquals(
            "01J9XK3EM8TQ2ZP0VN6W4RA8GG",
            AudioBridge.resolveAuthToken("01J9XK3EM8TQ2ZP0VN6W4RA8GG", "prefs-token"),
        )
    }

    @Test
    fun `会话凭据空白回落 prefs token`() {
        assertEquals("prefs-token", AudioBridge.resolveAuthToken(null, "prefs-token"))
        assertEquals("prefs-token", AudioBridge.resolveAuthToken("", "prefs-token"))
        assertEquals("prefs-token", AudioBridge.resolveAuthToken("   ", "prefs-token"))
    }

    @Test
    fun `两者皆空返回空串（调用方 fail-closed）`() {
        assertEquals("", AudioBridge.resolveAuthToken(null, null))
        assertEquals("", AudioBridge.resolveAuthToken("", ""))
        assertEquals("", AudioBridge.resolveAuthToken(null, ""))
    }

    @Test
    fun `桥默认端点为东京单端口 mux（坑③修正 不指 N100）`() {
        assertEquals("100.100.154.46", AudioBridge.DEFAULT_HOST)
        assertEquals(18799, AudioBridge.DEFAULT_PORT)
        assertEquals("100.100.154.46", BridgePrefs.DEFAULT_HOST)
        assertEquals(18799, BridgePrefs.DEFAULT_PORT)
    }
}
