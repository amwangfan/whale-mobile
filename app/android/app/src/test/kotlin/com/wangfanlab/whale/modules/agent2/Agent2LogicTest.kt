package com.wangfanlab.whale.modules.agent2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Agent2Logic 纯逻辑单测：动作参数校验、坐标屏内越界、UI 树解析、
 * §8 稳定键重校验三判据（0/1/≥2 匹配）与指纹规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Agent2LogicTest {

    private val W = 1080
    private val H = 2400

    private fun rejectOf(d: Agent2Logic.Decision): Agent2Logic.Decision.Reject =
        d as Agent2Logic.Decision.Reject

    // ------------------------------------------------------------- tap / long_press

    @Test
    fun `tap valid coordinates pass`() {
        val d = Agent2Logic.validate("device.tap", mapOf("x" to 100L, "y" to 200L), W, H)
        assertTrue(d is Agent2Logic.Decision.Run)
    }

    @Test
    fun `tap missing coordinates rejected`() {
        val d = Agent2Logic.validate("device.tap", mapOf("x" to 100L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
    }

    @Test
    fun `tap wrong type rejected`() {
        val d = Agent2Logic.validate("device.tap", mapOf("x" to "abc", "y" to 1L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
    }

    @Test
    fun `tap negative coordinate rejected`() {
        val d = Agent2Logic.validate("device.tap", mapOf("x" to -1L, "y" to 0L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
    }

    @Test
    fun `tap coordinate outside screen rejected`() {
        // x=1080 在 1080 宽屏上越界（合法域 0..1079）
        val d = Agent2Logic.validate("device.tap", mapOf("x" to 1080L, "y" to 100L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
        assertTrue(rejectOf(d).reason.contains("越出屏幕"))
    }

    @Test
    fun `tap y at bottom edge outside screen rejected`() {
        val d = Agent2Logic.validate("device.tap", mapOf("x" to 10L, "y" to 2400L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
    }

    @Test
    fun `tap with element id valid passes`() {
        val d = Agent2Logic.validate("device.tap", mapOf("element_id" to 23L), W, H)
        assertTrue(d is Agent2Logic.Decision.Run)
    }

    @Test
    fun `tap with invalid element id rejected`() {
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.tap", mapOf("element_id" to 0L), W, H)).errorCode)
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.tap", mapOf("element_id" to "x"), W, H)).errorCode)
    }

    @Test
    fun `long press duration bounds enforced`() {
        assertTrue(
            Agent2Logic.validate(
                "device.long_press",
                mapOf("x" to 1L, "y" to 1L, "duration_ms" to 800L), W, H,
            ) is Agent2Logic.Decision.Run,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.long_press",
                    mapOf("x" to 1L, "y" to 1L, "duration_ms" to 50L), W, H,
                ),
            ).errorCode,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.long_press",
                    mapOf("x" to 1L, "y" to 1L, "duration_ms" to 99_999L), W, H,
                ),
            ).errorCode,
        )
    }

    // ------------------------------------------------------------- swipe

    @Test
    fun `swipe all coords in screen pass`() {
        assertTrue(
            Agent2Logic.validate(
                "device.swipe",
                mapOf("x" to 0L, "y" to 0L, "x2" to 1079L, "y2" to 2399L, "duration_ms" to 300L),
                W, H,
            ) is Agent2Logic.Decision.Run,
        )
    }

    @Test
    fun `swipe end coordinate outside screen rejected`() {
        val d = Agent2Logic.validate(
            "device.swipe",
            mapOf("x" to 0L, "y" to 0L, "x2" to 2000L, "y2" to 100L), W, H,
        )
        assertEquals("invalid_message", rejectOf(d).errorCode)
    }

    @Test
    fun `swipe element form requires x2 y2`() {
        // element 形态缺终点 → 拒绝
        val d = Agent2Logic.validate("device.swipe", mapOf("element_id" to 3L), W, H)
        assertEquals("invalid_message", rejectOf(d).errorCode)
        // element 形态带终点 → 通过
        assertTrue(
            Agent2Logic.validate(
                "device.swipe",
                mapOf("element_id" to 3L, "x2" to 100L, "y2" to 900L), W, H,
            ) is Agent2Logic.Decision.Run,
        )
    }

    // ------------------------------------------------------------- key / input_text / wait

    @Test
    fun `key whitelist enforced`() {
        assertTrue(
            Agent2Logic.validate("device.key", mapOf("key" to "HOME"), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.key", mapOf("key" to "rm -rf"), W, H)).errorCode)
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.key", mapOf("key" to "VOLUME_X"), W, H)).errorCode)
    }

    @Test
    fun `media keys whitelisted`() {
        // #96：core 决策白名单早已放行 media_play_pause，本端对齐放行
        // （root 走 input keyevent；无 root 走 MediaSessionControl 直控）
        for (key in listOf("MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS")) {
            assertTrue(
                key,
                Agent2Logic.validate("device.key", mapOf("key" to key), W, H)
                    is Agent2Logic.Decision.Run,
            )
        }
        // 小写形态经 trim+uppercase 归一同样放行
        assertTrue(
            Agent2Logic.validate("device.key", mapOf("key" to "media_play_pause"), W, H)
                is Agent2Logic.Decision.Run,
        )
    }

    @Test
    fun `swipe x1 y1 aliases normalized to x y`() {
        // #96：模型对 swipe 有强 x1/y1 先验（GSI 验收单阶段 9 连拒实证），
        // 别名归一与 core adb 通道 a7e6803 对齐
        val d = Agent2Logic.validate(
            "device.swipe",
            mapOf("x1" to 60L, "y1" to 1988L, "x2" to 540L, "y2" to 1988L), W, H,
        )
        assertTrue(d is Agent2Logic.Decision.Run)
        val args = (d as Agent2Logic.Decision.Run).args
        assertEquals(60L, (args["x"] as Number).toLong())
        assertEquals(1988L, (args["y"] as Number).toLong())
        // 规范键存在时别名不覆盖
        val d2 = Agent2Logic.validate(
            "device.swipe",
            mapOf("x" to 10L, "y" to 20L, "x1" to 60L, "y1" to 1988L,
                "x2" to 100L, "y2" to 900L), W, H,
        )
        val args2 = (d2 as Agent2Logic.Decision.Run).args
        assertEquals(10L, (args2["x"] as Number).toLong())
        assertEquals(20L, (args2["y"] as Number).toLong())
        // 入参不被改写
        val orig = mapOf<String, Any?>("x1" to 60L, "y1" to 1988L, "x2" to 540L, "y2" to 1988L)
        Agent2Logic.validate("device.swipe", orig, W, H)
        assertFalse(orig.containsKey("x"))
        // 别名值非法仍拒绝（归一后走同一坐标校验）
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.swipe",
                    mapOf("x1" to -5L, "y1" to 100L, "x2" to 200L, "y2" to 300L), W, H,
                ),
            ).errorCode)
    }

    @Test
    fun `input text rules`() {
        assertTrue(
            Agent2Logic.validate("device.input_text", mapOf("text" to "你好 world"), W, H)
                is Agent2Logic.Decision.Run,
        )
        // 空串
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.input_text", mapOf("text" to ""), W, H)).errorCode)
        // 超长
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.input_text", mapOf("text" to "x".repeat(2001)), W, H,
                ),
            ).errorCode)
        // 换行（input text 不支持，拒绝而非静默改写）
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.input_text", mapOf("text" to "a\nb"), W, H),
            ).errorCode)
    }

    @Test
    fun `input text escape order percent before space`() {
        // % → %25 先于 空格 → %s（顺序敏感：空格替换不得吃掉 %25 的 %）
        assertEquals("a%25b%sc", Agent2Logic.inputEscape("a%b c"))
    }

    @Test
    fun `wait duration bounds`() {
        assertTrue(
            Agent2Logic.validate("device.wait", mapOf("duration_ms" to 1000L), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.wait", mapOf("duration_ms" to 50L), W, H)).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.wait", mapOf("duration_ms" to 300_001L), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.wait", emptyMap(), W, H)).errorCode)
    }

    // ------------------------------------------------------------- app / shell / file / logcat

    @Test
    fun `app launch package validation`() {
        assertTrue(
            Agent2Logic.validate("device.app.launch", mapOf("package" to "com.example.app"), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.app.launch", mapOf("package" to "a;b"), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.app.launch", mapOf("package" to "x"), W, H),
            ).errorCode)
    }

    @Test
    fun `app list filter enum`() {
        assertTrue(
            Agent2Logic.validate("device.app.list", mapOf("filter" to "third_party"), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.app.list", mapOf("filter" to "weird"), W, H),
            ).errorCode)
    }

    // ------------------------------------------------------------- device.media.seek_to（§5.1 增项）

    @Test
    fun `media seek_to accepts position_ms or ratio`() {
        assertTrue(
            Agent2Logic.validate("device.media.seek_to", mapOf("position_ms" to 83_000L), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertTrue(
            Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to 0.5), W, H)
                is Agent2Logic.Decision.Run,
        )
        // 边界值：position_ms=0、ratio=0/1 皆合法（int≥0 / float∈[0,1] 闭区间）
        assertTrue(
            Agent2Logic.validate("device.media.seek_to", mapOf("position_ms" to 0L), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertTrue(
            Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to 0.0), W, H)
                is Agent2Logic.Decision.Run,
        )
        assertTrue(
            Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to 1.0), W, H)
                is Agent2Logic.Decision.Run,
        )
        // 二选一：两者同现合法（执行侧 position_ms 优先）
        assertTrue(
            Agent2Logic.validate(
                "device.media.seek_to", mapOf("position_ms" to 1000L, "ratio" to 0.5), W, H,
            ) is Agent2Logic.Decision.Run,
        )
    }

    @Test
    fun `media seek_to requires one of position_ms or ratio`() {
        assertEquals(
            "invalid_message",
            rejectOf(Agent2Logic.validate("device.media.seek_to", emptyMap(), W, H)).errorCode,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("foo" to 1L), W, H),
            ).errorCode,
        )
    }

    @Test
    fun `media seek_to position_ms must be non negative integer`() {
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("position_ms" to -1L), W, H),
            ).errorCode,
        )
        // 契约 int≥0：小数拒绝而非静默截断
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("position_ms" to 83.5), W, H),
            ).errorCode,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("position_ms" to "83000"), W, H),
            ).errorCode,
        )
    }

    @Test
    fun `media seek_to ratio bounds enforced`() {
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to -0.1), W, H),
            ).errorCode,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to 1.5), W, H),
            ).errorCode,
        )
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate("device.media.seek_to", mapOf("ratio" to "half"), W, H),
            ).errorCode,
        )
        // 同现时旁路字段也须值域合法（不携带垃圾值入场）
        assertEquals(
            "invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.media.seek_to", mapOf("position_ms" to 1000L, "ratio" to 2.0), W, H,
                ),
            ).errorCode,
        )
    }

    @Test
    fun `shell command validation`() {
        assertTrue(
            Agent2Logic.validate(
                "device.shell",
                mapOf("command" to "id", "timeout_s" to 30L, "session" to "s-1"), W, H,
            ) is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.shell", mapOf("command" to "  "), W, H)).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.shell", mapOf("command" to "x".repeat(4001)), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.shell", mapOf("command" to "id", "timeout_s" to 999L), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.shell", mapOf("command" to "id", "session" to "a b"), W, H,
                ),
            ).errorCode)
    }

    @Test
    fun `file path validation rejects relative traversal nul`() {
        // 相对路径
        assertEquals("invalid_message",
            rejectOf(Agent2Logic.validate("device.file.read", mapOf("path" to "etc/hosts"), W, H)).errorCode)
        // .. 穿越
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.file.read", mapOf("path" to "/data/../etc/hosts"), W, H),
            ).errorCode)
        // NUL 注入
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.file.read", mapOf("path" to "/etc/hosts\u0000"), W, H),
            ).errorCode)
        // 合法
        assertTrue(
            Agent2Logic.validate("device.file.read", mapOf("path" to "/sdcard/a.txt"), W, H)
                is Agent2Logic.Decision.Run,
        )
    }

    @Test
    fun `file write content bounds`() {
        val b64 = android.util.Base64.encodeToString("hello".toByteArray(), android.util.Base64.NO_WRAP)
        assertTrue(
            Agent2Logic.validate(
                "device.file.write",
                mapOf("path" to "/sdcard/a.txt", "content_base64" to b64), W, H,
            ) is Agent2Logic.Decision.Run,
        )
        // 缺 content_base64
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.file.write", mapOf("path" to "/sdcard/a.txt"), W, H),
            ).errorCode)
        // 非法字符
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate(
                    "device.file.write",
                    mapOf("path" to "/sdcard/a.txt", "content_base64" to "!!!"), W, H,
                ),
            ).errorCode)
    }

    @Test
    fun `logcat bounds`() {
        assertTrue(
            Agent2Logic.validate(
                "device.logcat",
                mapOf("lines" to 200L, "tag" to "Whale", "level" to "I"), W, H,
            ) is Agent2Logic.Decision.Run,
        )
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.logcat", mapOf("lines" to 99999L), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.logcat", mapOf("tag" to "a b"), W, H),
            ).errorCode)
        assertEquals("invalid_message",
            rejectOf(
                Agent2Logic.validate("device.logcat", mapOf("level" to "Q"), W, H),
            ).errorCode)
    }

    @Test
    fun `unknown capability rejected as capability unknown`() {
        val d = Agent2Logic.validate("device.fly", emptyMap(), W, H)
        assertEquals("capability_unknown", rejectOf(d).errorCode)
    }

    // ------------------------------------------------------------- UI 树解析与 §8

    private val sampleXml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
                package="com.x" content-desc="" checkable="false" checked="false"
                clickable="false" enabled="true" focusable="false" focused="false"
                scrollable="false" long-clickable="false" password="false" selected="false"
                bounds="[0,0][1080,2400]" displayed="true">
            <node index="1" text="搜索" resource-id="com.x:id/search" class="android.widget.Button"
                  package="com.x" content-desc="搜索按钮" checkable="false" checked="false"
                  clickable="true" enabled="true" focusable="true" focused="false"
                  scrollable="false" long-clickable="false" password="false" selected="false"
                  bounds="[100,200][300,280]" displayed="true"/>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun `parse ui tree extracts elements with local ids`() {
        val elements = Agent2Logic.parseUiTree(sampleXml)
        assertEquals(2, elements.size)
        val btn = elements[1]
        assertEquals(2, btn.elementId)
        assertEquals("android.widget.Button", btn.className)
        assertEquals("com.x:id/search", btn.resourceId)
        assertEquals("搜索按钮", btn.contentDescription)
        assertEquals("搜索", btn.text)
        assertTrue(btn.clickable)
        assertFalse(btn.scrollable)
        assertEquals(Pair(200, 240), Agent2Logic.boundsCenter(btn.bounds))
    }

    @Test
    fun `parse bounds valid and invalid`() {
        assertNotNull(Agent2Logic.parseBounds("[0,0][1080,2400]"))
        assertNull(Agent2Logic.parseBounds("0,0,1080,2400"))
        assertNull(Agent2Logic.parseBounds("[-1,0][5,5]"))
        assertNull(Agent2Logic.parseBounds(""))
    }

    @Test
    fun `fingerprint includes text while stable key excludes it`() {
        val a = Agent2Logic.parseUiTree(sampleXml)[1]
        val changedText = a.copy(text = "搜索一下")
        // 指纹含 text（§8）：text 变 → 指纹变
        assertFalse(a.fingerprint == changedText.fingerprint)
        // 稳定键不含 text（§8）：text 变 → 稳定键仍匹配
        assertTrue(a.stableKeyEquals(changedText))
        // bounds 变 → 稳定键不匹配
        assertFalse(a.stableKeyEquals(a.copy(bounds = "[100,200][301,280]")))
    }

    @Test
    fun `revalidate exactly one match passes`() {
        val target = Agent2Logic.parseUiTree(sampleXml)[1]
        val fresh = Agent2Logic.parseUiTree(sampleXml)
        val v = Agent2Logic.revalidate(target, fresh)
        assertTrue(v is Agent2Logic.MatchVerdict.Unique)
    }

    @Test
    fun `revalidate zero match is stale`() {
        val target = Agent2Logic.parseUiTree(sampleXml)[1]
        // fresh 树中没有该元素（换个 resource-id 的树）
        val otherXml = sampleXml.replace("com.x:id/search", "com.x:id/other")
        val v = Agent2Logic.revalidate(target, Agent2Logic.parseUiTree(otherXml))
        assertTrue(v is Agent2Logic.MatchVerdict.Stale)
        assertEquals(0, (v as Agent2Logic.MatchVerdict.Stale).matchedCount)
    }

    @Test
    fun `revalidate two matches is stale ambiguous`() {
        val target = Agent2Logic.parseUiTree(sampleXml)[1]
        // 构造两个稳定键相同的节点（bounds 相同、text 不同——稳定键不含 text）
        val dupXml = sampleXml.replace(
            "</hierarchy>",
            """<node text="另一个" resource-id="com.x:id/search" class="android.widget.Button"
                 content-desc="搜索按钮" clickable="true" bounds="[100,200][300,280]"/></hierarchy>""",
        )
        val v = Agent2Logic.revalidate(target, Agent2Logic.parseUiTree(dupXml))
        assertTrue(v is Agent2Logic.MatchVerdict.Stale)
        assertEquals(2, (v as Agent2Logic.MatchVerdict.Stale).matchedCount)
    }

    // ------------------------------------------------------------- 命令拼装

    @Test
    fun `build command tap and long press`() {
        assertEquals(
            "input tap 100 200",
            Agent2Logic.buildCommand("device.tap", mapOf("x" to 100L, "y" to 200L), Pair(100, 200)),
        )
        assertEquals(
            "input swipe 100 200 100 200 800",
            Agent2Logic.buildCommand("device.long_press", mapOf("x" to 100L, "y" to 200L), Pair(100, 200)),
        )
    }

    @Test
    fun `build command input text is shell quoted and escaped`() {
        val cmd = Agent2Logic.buildCommand(
            "device.input_text", mapOf("text" to "a b'c"), null,
        )
        // 空格→%s，单引号经 shell 包裹转义
        assertEquals("input text 'a%sb'\\''c'", cmd)
    }

    @Test
    fun `build command logcat with tag and level`() {
        assertEquals(
            "logcat -d -t 50 -s Whale:W",
            Agent2Logic.buildCommand(
                "device.logcat",
                mapOf("lines" to 50L, "tag" to "Whale", "level" to "W"), null,
            ),
        )
    }

    @Test
    fun `truncate summary bound`() {
        val s = "x".repeat(3000)
        val t = Agent2Logic.truncateSummary(s)
        assertTrue(t.length <= 2000)
        assertTrue(t.endsWith("[truncated]"))
        assertEquals("short", Agent2Logic.truncateSummary("short"))
    }
}
