package com.wangfanlab.whale.modules.agent2

import android.util.Xml
import com.wangfanlab.whale.transport.v2.ErrorCodeV2
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.MessageDigest

/**
 * contracts/v2 设备动作族纯逻辑（可单测）：参数校验、坐标越界、UI 树解析、
 * §8 稳定键重校验与 §8 元素指纹。
 *
 * 纪律（与 v1 modules/agent 一致，字段对齐 v2）：
 * - 不做命令白名单；参数校验只保证「值域合法 + 不可注入 shell」；
 * - 所有数字参数先验类型再验范围（原始值会插进 shell 命令行）；
 * - 引用元素的动作（element_id+observation_id）在执行前必须经
 *   [revalidate] 在 fresh 观察中按稳定键（className+resourceId+contentDescription+bounds，
 *   **不含 text**，§8）重校验恰好一个匹配，否则 failed/stale_observation，
 *   绝不沿用过期坐标。
 */
object Agent2Logic {

    // ------------------------------------------------------------- 值域常量

    const val MAX_TEXT_CHARS = 2000
    const val MAX_COMMAND_CHARS = 4000
    const val MAX_TIMEOUT_S = 300
    const val DEFAULT_TIMEOUT_S = 30
    const val MAX_SESSION_CHARS = 64
    const val WAIT_MIN_MS = 100L

    /** wait 上限与 action.request timeout_ms 上限（600000）对齐的一半——
     * 超过 timeout_ms 的等待必然被分发器判 timeout，无意义。 */
    const val WAIT_MAX_MS = 300_000L
    const val LONG_PRESS_DEFAULT_MS = 800L
    const val LONG_PRESS_MAX_MS = 10_000L
    const val SWIPE_DEFAULT_MS = 300L
    const val SWIPE_MAX_MS = 10_000L
    const val LOGCAT_MAX_LINES = 5000
    const val LOGCAT_DEFAULT_LINES = 200

    /** file.write 内容上限（content_base64 字符数；控制在 256KiB 帧预算内）。 */
    const val MAX_WRITE_B64_CHARS = 200_000

    /** 坐标全局 sanity 上限（屏幕尺寸提供者异常时的兜底；正常走屏内校验）。 */
    const val COORD_HARD_CAP = 32_767

    /** 单次观察保留的最大元素数（防爆 output 文件；超出截断并在摘要注明）。 */
    const val MAX_OBSERVE_ELEMENTS = 400

    /** 元素 text 字段入观察表的单字段上限。 */
    const val MAX_ELEMENT_TEXT_CHARS = 200

    val PACKAGE_NAME = Regex("^[A-Za-z0-9._]{2,128}$")
    val SESSION_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    val LOGCAT_LEVELS = setOf("V", "D", "I", "W", "E", "F", "S")
    val APP_LIST_FILTERS = setOf("all", "third_party", "system")
    val FILE_ENCODINGS = setOf("text", "base64")

    /** device.key 允许的标准键名（沿用 v1 白名单；input keyevent 接受裸名）。 */
    val ALLOWED_KEYS = setOf(
        "HOME", "BACK", "ENTER", "TAB", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT",
        "DPAD_RIGHT", "DPAD_CENTER", "VOLUME_UP", "VOLUME_DOWN", "MUTE",
        "POWER", "WAKEUP", "SLEEP", "APP_SWITCH", "SEARCH",
    )

    // ------------------------------------------------------------- 判定结果

    sealed interface Decision {
        data class Run(val args: Map<String, Any?>) : Decision

        /** [errorCode] 取 §15 枚举（invalid_message / stale_observation / …）。 */
        data class Reject(val errorCode: String, val reason: String) : Decision
    }

    // ------------------------------------------------------------- 元素模型（§8）

    /** 观察内局部元素（element_id 不跨观察稳定，§8）。 */
    data class UiElement(
        val elementId: Int,
        val className: String,
        val resourceId: String,
        val contentDescription: String,
        val text: String,
        /** uiautomator 原始 bounds 串 "[l,t][r,b]"（稳定键与指纹都用原始串）。 */
        val bounds: String,
        val clickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
    ) {
        /** §8 跨观察稳定 fingerprint：sha256 前 16 hex。 */
        val fingerprint: String by lazy { fingerprintOf(this) }

        /** §8 稳定键（className+resourceId+contentDescription+bounds，不含 text）。 */
        fun stableKeyEquals(other: UiElement): Boolean =
            className == other.className &&
                resourceId == other.resourceId &&
                contentDescription == other.contentDescription &&
                bounds == other.bounds
    }

    fun fingerprintOf(e: UiElement): String {
        val raw = listOf(
            e.className, e.text, e.resourceId, e.contentDescription, e.bounds,
            e.clickable.toString(), e.scrollable.toString(), e.editable.toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** "[l,t][r,b]" → intArrayOf(l, t, r, b)；非法返回 null。 */
    fun parseBounds(bounds: String): IntArray? {
        val m = Regex("^\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]$").matchEntire(bounds)
            ?: return null
        val v = m.groupValues
        return intArrayOf(v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt())
    }

    /** bounds 中心点（tap/long_press 落点）。bounds 非法返回 null。 */
    fun boundsCenter(bounds: String): Pair<Int, Int>? {
        val b = parseBounds(bounds) ?: return null
        return Pair((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    }

    // ------------------------------------------------------------- UI 树解析

    /**
     * 解析 uiautomator dump XML 为元素表（element_id 自 1 起按文档序递增）。
     * 仅收 bounds 可解析的节点；XML 畸形时抛异常（调用方映射 failed/internal）。
     */
    fun parseUiTree(xml: String): List<UiElement> {
        val elements = ArrayList<UiElement>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var nextId = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val bounds = parser.getAttributeValue(null, "bounds") ?: ""
                if (parseBounds(bounds) != null) {
                    nextId++
                    elements.add(
                        UiElement(
                            elementId = nextId,
                            className = parser.getAttributeValue(null, "class") ?: "",
                            resourceId = parser.getAttributeValue(null, "resource-id") ?: "",
                            contentDescription = parser.getAttributeValue(null, "content-desc") ?: "",
                            text = (parser.getAttributeValue(null, "text") ?: "")
                                .take(MAX_ELEMENT_TEXT_CHARS),
                            bounds = bounds,
                            clickable = parser.getAttributeValue(null, "clickable") == "true",
                            scrollable = parser.getAttributeValue(null, "scrollable") == "true",
                            editable = parser.getAttributeValue(null, "class")?.contains("EditText") == true,
                        ),
                    )
                }
            }
            event = parser.next()
        }
        return elements
    }

    // ------------------------------------------------------------- §8 稳定键重校验

    sealed interface MatchVerdict {
        /** 恰好一个匹配；[fresh] 为 fresh 观察中的新元素（坐标以它为准）。 */
        data class Unique(val fresh: UiElement) : MatchVerdict

        /** 0 个匹配（元素已消失）或 ≥2 个匹配（歧义）——统一 failed/stale_observation。 */
        data class Stale(val matchedCount: Int) : MatchVerdict
    }

    /**
     * §8 重校验：在 [freshElements]（fresh 观察）中按稳定键匹配 [target]
     * （来自 observation_id 指向的旧观察），必须恰好一个匹配。
     * text 不参与匹配（稳定键不含 text，§8 明确）。
     */
    fun revalidate(target: UiElement, freshElements: List<UiElement>): MatchVerdict {
        val matches = freshElements.filter { it.stableKeyEquals(target) }
        return if (matches.size == 1) {
            MatchVerdict.Unique(matches[0])
        } else {
            MatchVerdict.Stale(matches.size)
        }
    }

    // ------------------------------------------------------------- 参数校验

    /**
     * 动作参数校验（结构/值域/注入防护）。屏幕内坐标校验以 [screenW]/[screenH] 为准。
     * 只验「参数本身合法」；observation 缓存命中等执行期状态在模块层判定。
     */
    fun validate(
        capability: String,
        args: Map<String, Any?>?,
        screenW: Int,
        screenH: Int,
    ): Decision {
        val a = args ?: return reject("缺少 arguments")
        when (capability) {
            "device.tap", "device.long_press" -> {
                // 两选一：坐标对 或 element_id 引用（observation_id 由分发器强制必填，§8）
                if (a.containsKey("element_id")) {
                    val eid = (a["element_id"] as? Number)?.toInt()
                        ?: return reject("element_id 必须为整数")
                    if (eid < 1) return reject("element_id 越界: $eid")
                } else {
                    val err = validateCoords(a, listOf("x", "y"), screenW, screenH)
                    if (err != null) return err
                }
                if (capability == "device.long_press") {
                    val dur = (a["duration_ms"] as? Number)?.toLong()
                    if (dur != null && (dur < WAIT_MIN_MS || dur > LONG_PRESS_MAX_MS)) {
                        return reject("duration_ms 越界: $dur")
                    }
                }
            }
            "device.swipe" -> {
                if (a.containsKey("element_id")) {
                    // 元素滑动：element_id 为起点，x2/y2 为终点坐标
                    val eid = (a["element_id"] as? Number)?.toInt()
                        ?: return reject("element_id 必须为整数")
                    if (eid < 1) return reject("element_id 越界: $eid")
                    val err = validateCoords(a, listOf("x2", "y2"), screenW, screenH)
                    if (err != null) return err
                } else {
                    val err = validateCoords(a, listOf("x", "y", "x2", "y2"), screenW, screenH)
                    if (err != null) return err
                }
                val dur = (a["duration_ms"] as? Number)?.toLong()
                if (dur != null && (dur < 0 || dur > SWIPE_MAX_MS)) {
                    return reject("duration_ms 越界: $dur")
                }
            }
            "device.key" -> {
                val key = (a["key"] as? String)?.trim()?.uppercase()
                    ?: return reject("缺少 key")
                if (key !in ALLOWED_KEYS) return reject("key 不在白名单: $key")
            }
            "device.input_text" -> {
                val text = (a["text"] as? String) ?: return reject("缺少 text")
                if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
                    return reject("text 长度越界: ${text.length}")
                }
                // input text 不支持换行（换行应拆 keyevent ENTER）；拒绝而非静默改写
                if ('\n' in text || '\r' in text) return reject("text 不允许换行符")
            }
            "device.wait" -> {
                val dur = (a["duration_ms"] as? Number)?.toLong()
                    ?: return reject("缺少 duration_ms")
                if (dur < WAIT_MIN_MS || dur > WAIT_MAX_MS) return reject("duration_ms 越界: $dur")
            }
            "device.app.launch" -> {
                val pkg = (a["package"] as? String)?.trim() ?: return reject("缺少 package")
                if (!PACKAGE_NAME.matches(pkg)) return reject("package 非法: $pkg")
            }
            "device.app.list" -> {
                val filter = (a["filter"] as? String) ?: "all"
                if (filter !in APP_LIST_FILTERS) return reject("filter 非法: $filter")
            }
            "device.shell" -> {
                val cmd = (a["command"] as? String) ?: (a["script"] as? String) ?: ""
                if (cmd.isBlank() || cmd.length > MAX_COMMAND_CHARS) {
                    return reject("command 为空或超长")
                }
                val timeout = (a["timeout_s"] as? Number)?.toInt()
                if (timeout != null && (timeout < 1 || timeout > MAX_TIMEOUT_S)) {
                    return reject("timeout_s 越界: $timeout")
                }
                val session = (a["session"] as? String)?.trim()
                if (!session.isNullOrEmpty() && !SESSION_NAME.matches(session)) {
                    return reject("session 名非法")
                }
            }
            "device.file.read" -> {
                val path = (a["path"] as? String) ?: return reject("缺少 path")
                validatePath(path)?.let { return it }
                val encoding = (a["encoding"] as? String) ?: "text"
                if (encoding !in FILE_ENCODINGS) return reject("encoding 非法: $encoding")
            }
            "device.file.write" -> {
                val path = (a["path"] as? String) ?: return reject("缺少 path")
                validatePath(path)?.let { return it }
                val b64 = (a["content_base64"] as? String) ?: return reject("缺少 content_base64")
                if (b64.isEmpty() || b64.length > MAX_WRITE_B64_CHARS) {
                    return reject("content_base64 为空或超长: ${b64.length}")
                }
                if (!Regex("^[A-Za-z0-9+/=\\s]+$").matches(b64)) {
                    return reject("content_base64 含非法字符")
                }
            }
            "device.file.list" -> {
                val path = (a["path"] as? String) ?: return reject("缺少 path")
                validatePath(path)?.let { return it }
            }
            "device.logcat" -> {
                val lines = (a["lines"] as? Number)?.toInt() ?: LOGCAT_DEFAULT_LINES
                if (lines < 1 || lines > LOGCAT_MAX_LINES) return reject("lines 越界: $lines")
                val tag = (a["tag"] as? String)?.trim()
                if (tag != null && (tag.isEmpty() || tag.length > 64 ||
                        !Regex("^[A-Za-z0-9._*-]+$").matches(tag))
                ) {
                    return reject("tag 非法")
                }
                val level = (a["level"] as? String)?.trim()?.uppercase()
                if (level != null && level !in LOGCAT_LEVELS) return reject("level 非法: $level")
            }
            else -> return Decision.Reject(ErrorCodeV2.CAPABILITY_UNKNOWN, "未知能力: $capability")
        }
        return Decision.Run(a)
    }

    private fun reject(reason: String) = Decision.Reject(ErrorCodeV2.INVALID_MESSAGE, reason)

    /** 坐标校验：必须为非负整数且在屏幕内（另加全局 sanity 上限，防参数注入）。 */
    fun validateCoords(
        args: Map<String, Any?>,
        keys: List<String>,
        screenW: Int,
        screenH: Int,
    ): Decision.Reject? {
        for (k in keys) {
            val v = (args[k] as? Number)?.toInt()
                ?: return reject("缺少/非法坐标: $k")
            if (v < 0 || v > COORD_HARD_CAP) return reject("坐标越界: $k=$v")
            val onX = k.startsWith("x")
            if (onX && screenW > 0 && v >= screenW) return reject("坐标越出屏幕: $k=$v ≥ w=$screenW")
            if (!onX && screenH > 0 && v >= screenH) return reject("坐标越出屏幕: $k=$v ≥ h=$screenH")
        }
        return null
    }

    /** 路径校验：绝对路径、无 `..`、无 NUL（file.* 三能力共用）。 */
    fun validatePath(path: String): Decision.Reject? {
        if (path.isBlank() || !path.startsWith("/")) return reject("path 须为绝对路径")
        if (path.length > 512) return reject("path 超长")
        if (".." in path) return reject("path 不允许 ..")
        if (path.indexOf('\u0000') >= 0) return reject("path 含 NUL")
        return null
    }

    // ------------------------------------------------------------- shell 命令拼装

    /** input text 的 Android 层转义：%→%25、空格→%s（先 % 后空格，顺序敏感）。 */
    fun inputEscape(text: String): String =
        text.replace("%", "%25").replace(" ", "%s")

    /** Shell 单引号包裹（$、反引号、双引号全部失去特殊语义）。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 拼装控制命令（input/am/pm/logcat 系列）。调用前必须已过 [validate]。
     * 返回 null 表示该能力不走 shell 命令（device.wait/device.observe/device.screen.record
     * 走专用路径）。
     */
    fun buildCommand(capability: String, args: Map<String, Any?>, point: Pair<Int, Int>?): String? =
        when (capability) {
            "device.tap" -> {
                val (x, y) = point!!
                "input tap $x $y"
            }
            "device.long_press" -> {
                val (x, y) = point!!
                val dur = (args["duration_ms"] as? Number)?.toLong() ?: LONG_PRESS_DEFAULT_MS
                // input 无原生长按：等价于原地 swipe（v1 同款语义）
                "input swipe $x $y $x $y $dur"
            }
            "device.swipe" -> {
                val (x, y) = point!!
                val x2 = (args["x2"] as Number).toInt()
                val y2 = (args["y2"] as Number).toInt()
                val dur = (args["duration_ms"] as? Number)?.toLong() ?: SWIPE_DEFAULT_MS
                "input swipe $x $y $x2 $y2 $dur"
            }
            "device.key" -> "input keyevent ${(args["key"] as String).trim().uppercase()}"
            "device.input_text" -> "input text ${shellQuote(inputEscape(args["text"] as String))}"
            "device.app.launch" -> {
                val pkg = (args["package"] as String).trim()
                // 解析 launcher activity 再启动（比 monkey 快且明确；沿用 v1 语义）
                "LA=\$(cmd package resolve-activity --brief ${shellQuote(pkg)} | tail -1); " +
                    "am start -n \"\$LA\""
            }
            "device.app.list" -> when ((args["filter"] as? String) ?: "all") {
                "third_party" -> "pm list packages -3"
                "system" -> "pm list packages -s"
                else -> "pm list packages"
            }
            "device.file.read" -> {
                val path = shellQuote(args["path"] as String)
                if (((args["encoding"] as? String) ?: "text") == "base64") {
                    "base64 $path"
                } else {
                    "cat $path"
                }
            }
            "device.file.list" -> "ls -la ${shellQuote(args["path"] as String)}"
            "device.logcat" -> {
                val lines = (args["lines"] as? Number)?.toInt() ?: LOGCAT_DEFAULT_LINES
                val tag = (args["tag"] as? String)?.trim()
                val level = (args["level"] as? String)?.trim()?.uppercase()
                val sb = StringBuilder("logcat -d -t $lines")
                if (!tag.isNullOrEmpty()) {
                    sb.append(" -s ").append(tag)
                    if (level != null) sb.append(":").append(level)
                }
                sb.toString()
            }
            else -> null
        }

    /** 结果摘要截断（receipt result.summary ≤2000，§7.3）。 */
    fun truncateSummary(s: String, maxChars: Int = 2000): String =
        if (s.length <= maxChars) s else s.take(maxChars - 20) + "…[truncated]"
}
