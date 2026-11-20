package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网关地址归一化测试（2026-11-20 nova 复盘：裸根 URL 原样直连打不到设备路由）。
 * 纯 JVM 函数，不走网络。
 */
class GatewayUrlNormalizeV2Test {

    @Test
    fun `bare root without trailing slash gets path appended`() {
        assertEquals(
            "wss://office-n100.tail69bfea.ts.net:8443$WSS_PATH_V2",
            normalizeGatewayUrlV2("wss://office-n100.tail69bfea.ts.net:8443"),
        )
    }

    @Test
    fun `bare root with trailing slash gets path appended`() {
        assertEquals(
            "wss://office-n100.tail69bfea.ts.net:8443$WSS_PATH_V2",
            normalizeGatewayUrlV2("wss://office-n100.tail69bfea.ts.net:8443/"),
        )
    }

    @Test
    fun `full path is returned unchanged`() {
        val url = "wss://office-n100.tail69bfea.ts.net:8443$WSS_PATH_V2"
        assertEquals(url, normalizeGatewayUrlV2(url))
    }

    @Test
    fun `unrelated existing path is not rewritten`() {
        val url = "wss://example.com:8443/ws"
        assertEquals(url, normalizeGatewayUrlV2(url))
    }

    @Test
    fun `query without path gets path inserted before query`() {
        assertEquals(
            "wss://example.com$WSS_PATH_V2?x=1",
            normalizeGatewayUrlV2("wss://example.com?x=1"),
        )
    }

    @Test
    fun `uppercase scheme and surrounding whitespace are handled`() {
        // scheme 大小写不敏感但保留原写法（OkHttp 接受），首尾空白裁掉。
        assertEquals(
            "WSS://Example.COM:8443$WSS_PATH_V2",
            normalizeGatewayUrlV2("  WSS://Example.COM:8443  "),
        )
    }

    @Test
    fun `plain ws scheme is normalized too`() {
        assertEquals("ws://10.0.2.2:8765$WSS_PATH_V2", normalizeGatewayUrlV2("ws://10.0.2.2:8765"))
    }

    @Test
    fun `blank and malformed input pass through unchanged`() {
        assertEquals("", normalizeGatewayUrlV2(""))
        assertEquals("", normalizeGatewayUrlV2("   "))
        assertEquals("not-a-url", normalizeGatewayUrlV2("not-a-url"))
    }
}
