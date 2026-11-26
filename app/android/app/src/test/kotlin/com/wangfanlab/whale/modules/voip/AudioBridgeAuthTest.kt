package com.wangfanlab.whale.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioBridge 媒体凭据与端点决策纯逻辑测试：
 * - 会话凭据（首帧 AUTH 载荷=session_id，不落盘）优先；空白回落 BridgePrefs
 *   独立媒体 token（调试路）；两者皆空 → 空串（调用方 fail-closed）
 * - 端点占位符化（Tob 裁决 c）：默认空 host+端口 0=未配置，fail-closed 不开
 *   socket；isEndpointConfigured 覆盖占位默认不拨号/配置后正常/端口双端边界
 * JVM 测试，不触碰 Android 框架（纯函数）。
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
    fun `桥默认端点为占位（未配置语义 真实坐标不入仓）`() {
        assertEquals("", AudioBridge.DEFAULT_HOST)
        assertEquals(0, AudioBridge.DEFAULT_PORT)
        assertEquals("", BridgePrefs.DEFAULT_HOST)
        assertEquals(0, BridgePrefs.DEFAULT_PORT)
    }

    @Test
    fun `占位默认不拨号 - isEndpointConfigured 空 host 或端口 0 拒连`() {
        // 占位默认（未配置）→ false（AudioBridge fail-closed 不开 socket）
        assertFalse(AudioBridge.isEndpointConfigured("", 0))
        assertFalse(AudioBridge.isEndpointConfigured("", 18799))
        assertFalse(AudioBridge.isEndpointConfigured(null, 18900))
        assertFalse(AudioBridge.isEndpointConfigured("   ", 18799))
        assertFalse(AudioBridge.isEndpointConfigured("has space.example", 18799))
    }

    @Test
    fun `配置后正常 + 端口双端边界`() {
        // 配置后（文档保留段示例坐标）→ true
        assertTrue(AudioBridge.isEndpointConfigured("203.0.113.10", 18799))
        assertTrue(AudioBridge.isEndpointConfigured("bridge.example", 18900))
        // 端口边界两端：1 与 65535 有效；0 与 65536 无效
        assertTrue(AudioBridge.isEndpointConfigured("203.0.113.10", 1))
        assertTrue(AudioBridge.isEndpointConfigured("203.0.113.10", 65535))
        assertFalse(AudioBridge.isEndpointConfigured("203.0.113.10", 0))
        assertFalse(AudioBridge.isEndpointConfigured("203.0.113.10", 65536))
        assertFalse(AudioBridge.isEndpointConfigured("203.0.113.10", -1))
    }
}
