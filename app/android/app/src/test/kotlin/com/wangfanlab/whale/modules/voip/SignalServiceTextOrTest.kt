package com.wangfanlab.whale.voip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * legacy 链 textOr 穿透门测试（2026-11-26 两端同改口径：core 恒传含空串 +
 * hub 尊重空串 + 端侧穿透）：
 * - 显式空串 → 穿透为 ""（匿名），不回落默认
 * - 仅字段缺席/null → 回落默认
 * Robolectric（android-all 携带真 org.json）。
 */
@RunWith(RobolectricTestRunner::class)
class SignalServiceTextOrTest {

    @Test
    fun `显式空串穿透 - 不回落默认`() {
        assertEquals("", SignalService.textOr(JSONObject("""{"name":""}"""), "name", "默认"))
        assertEquals("", SignalService.textOr(JSONObject("""{"name":"   "}"""), "name", "默认"))
    }

    @Test
    fun `缺席与 null 回落默认`() {
        assertEquals("默认", SignalService.textOr(JSONObject("{}"), "name", "默认"))
        assertEquals("默认", SignalService.textOr(JSONObject("""{"name":null}"""), "name", "默认"))
        assertEquals("默认", SignalService.textOr(null, "name", "默认"))
    }

    @Test
    fun `实名原样 trim`() {
        assertEquals("鲸鱼", SignalService.textOr(JSONObject("""{"name":" 鲸鱼 "}"""), "name", "默认"))
        assertEquals("0", SignalService.textOr(JSONObject("""{"number":"0"}"""), "number", ""))
    }
}
