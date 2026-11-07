package com.wangfanlab.whale.transport.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * device.hello 组装（contracts/v2 §4/§5.2）单元测试。
 * 按「传入的 capabilities 集合」验证构建变体差异：daily 集合不得含
 * device.shell 等 Root 能力；root 集合包含之（§5.2：不靠 UI 隐藏）。
 */
class HelloAssemblyV2Test {

    private val deviceId = "ba4bb14c75917cfc0e69fb7a1caf8ba3"
    private val challenge = "a".repeat(64)
    private val signature = "b".repeat(128)

    private val fullFeatures = WssClientV2.FeatureSet(
        rootShell = true, accessibility = true, mediaProjection = false,
        telecom = false, ocrMlkit = true,
    )

    /** §5.2：Root 版独有能力（日常版不注册、不声明）。 */
    private val rootOnlyCaps = setOf(
        "device.shell", "device.file.read", "device.file.write", "device.file.list",
        "device.logcat", "device.app.install", "device.app.uninstall", "device.screen.record",
    )

    private val dailyCaps = CapabilitiesV2.ALL - rootOnlyCaps
    private val rootCaps = CapabilitiesV2.ALL

    private fun config(caps: Set<String>, flavor: String) = WssClientV2.HelloConfig(
        capabilities = caps,
        features = fullFeatures,
        deviceModel = "Leaf5",
        androidRelease = "13",
        apiLevel = 33,
        appVersion = "2.0.0",
        appFlavor = flavor,
    )

    @Test
    fun `hello payload contains all required v2 fields in schema order`() {
        val payload = WssClientV2.buildHelloPayload(config(dailyCaps, "daily"), deviceId, challenge, signature)
        // schema 必填：protocol_version/device_id/capabilities/features/device/
        // app_version/app_flavor/signature/challenge
        val keys = payload.keys.toList()
        assertEquals(
            listOf(
                "protocol_version", "device_id", "capabilities", "features", "device",
                "app_version", "app_flavor", "signature", "challenge",
            ),
            keys,
        )
        assertEquals("2.0", payload["protocol_version"])
        assertEquals(deviceId, payload["device_id"])
        assertEquals("daily", payload["app_flavor"])
        assertEquals(signature, payload["signature"])
        assertEquals(challenge, payload["challenge"])
    }

    @Test
    fun `daily capability set excludes root only capabilities`() {
        val payload = WssClientV2.buildHelloPayload(config(dailyCaps, "daily"), deviceId, challenge, signature)
        @Suppress("UNCHECKED_CAST")
        val caps = payload["capabilities"] as List<String>
        for (rootCap in rootOnlyCaps) {
            assertFalse("daily 变体不得声明 $rootCap（§5.2）", caps.contains(rootCap))
        }
        assertTrue(caps.contains("device.tap"))
        assertTrue(caps.contains("screen.context.read"))
        assertEquals(caps.size, caps.toSet().size) // uniqueItems
    }

    @Test
    fun `root capability set includes root only capabilities`() {
        val payload = WssClientV2.buildHelloPayload(config(rootCaps, "root"), deviceId, challenge, signature)
        @Suppress("UNCHECKED_CAST")
        val caps = payload["capabilities"] as List<String>
        assertTrue(caps.containsAll(rootOnlyCaps.toList()))
        assertEquals(32, caps.size)
    }

    @Test
    fun `features block carries exactly the five schema keys`() {
        val payload = WssClientV2.buildHelloPayload(config(dailyCaps, "daily"), deviceId, challenge, signature)
        @Suppress("UNCHECKED_CAST")
        val features = payload["features"] as Map<String, Any?>
        assertEquals(
            setOf("root_shell", "accessibility", "media_projection", "telecom", "ocr_mlkit"),
            features.keys,
        )
        assertEquals(true, features["root_shell"])
        assertEquals(false, features["media_projection"])
        assertEquals(true, features["ocr_mlkit"])
    }

    @Test
    fun `device block carries model release and api level`() {
        val payload = WssClientV2.buildHelloPayload(config(dailyCaps, "daily"), deviceId, challenge, signature)
        @Suppress("UNCHECKED_CAST")
        val device = payload["device"] as Map<String, Any?>
        assertEquals("Leaf5", device["model"])
        assertEquals("13", device["android_release"])
        assertEquals(33, device["api_level"])
    }

    @Test
    fun `assembled hello frame passes envelope codec and matches golden shape`() {
        val payload = WssClientV2.buildHelloPayload(config(rootCaps, "root"), deviceId, challenge, signature)
        val env = EnvelopeV2.uplink(MsgTypeV2.DEVICE_HELLO, 1, payload, ts = 1794052800100L)
        val decoded = EnvelopeCodecV2.decode(env.toJson())
        assertEquals("device.hello", decoded.type)
        assertEquals(DIR_UPLINK, decoded.dir)
        assertEquals(33L, (decoded.payload["device"] as Map<*, *>)["api_level"]) // 数字保型为 Long
        assertEquals("root", decoded.payload["app_flavor"])
    }

    @Test
    fun `app flavor outside daily root is rejected`() {
        try {
            WssClientV2.buildHelloPayload(config(dailyCaps, "beta"), deviceId, challenge, signature)
            throw AssertionError("非法 app_flavor 应抛异常")
        } catch (_: IllegalArgumentException) {
        }
    }
}
