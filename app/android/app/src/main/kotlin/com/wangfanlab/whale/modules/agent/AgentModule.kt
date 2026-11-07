package com.wangfanlab.whale.modules.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.transport.v2.LegacyActionExecutor
import java.io.File
import java.io.FileOutputStream

/**
 * 设备自主体模块（core#58）：AI 对 Root 手机的自主 Shell 与应用控制。
 *
 * 三个能力（默认全关，两级开关）：
 * - device.agent.control：launch_app/tap/swipe/key/input_text/media（模块开关即可）
 * - device.agent.observe：ui_tree/screenshot（模块开关；截图隐私级 owner_sensitive）
 * - device.agent.shell：Root Shell（另需「持续 Root Shell 授权」开关）
 *
 * 纪律：命令不设白名单（issue 明确）；超时/并发/输出上限/紧急停止防失控；
 * 回执 result.summary 有界截断，大输出落私有文件经 device.agent.output.read 拉取；
 * 暂停/撤销/紧急停止立即拒绝并销毁会话。
 */
class AgentModule(
    context: Context,
    /** shell 实例公开（紧急停止入口需要销毁会话）。 */
    val shell: AgentShell = AgentShell(
        File(context.applicationContext.filesDir, "agent/out"),
    ),
) : LegacyActionExecutor {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val recentActions = ArrayDeque<Long>()

    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?, result: Map<String, Any?>?) -> Unit,
    ) {
        val capability = payload["capability"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?>
        val now = System.currentTimeMillis()
        val decision = synchronized(lock) {
            val d = AgentLogic.validate(
                capability, args,
                moduleEnabled = isEnabled(appContext),
                rootAuthorized = isRootAuthorized(appContext),
                allPaused = PauseFlags.isAllPaused(appContext),
                estop = isEstop(appContext),
                recent = recentActions.toList(),
                nowMs = now,
            )
            if (d is AgentLogic.Decision.Run) {
                recentActions.addLast(now)
                while (recentActions.size > 64) recentActions.removeFirst()
            }
            d
        }
        when (decision) {
            is AgentLogic.Decision.Reject ->
                reply("failed", decision.errorCode, null, null)
            is AgentLogic.Decision.Run ->
                Thread { executeRun(capability, decision.args, reply) }
                    .apply { isDaemon = true; start() }
        }
    }

    private fun executeRun(
        capability: String,
        args: Map<String, Any?>,
        reply: (String, String?, String?, Map<String, Any?>?) -> Unit,
    ) {
        try {
            when (capability) {
                AgentLogic.CAP_SHELL -> executeShell(args, reply)
                AgentLogic.CAP_CONTROL -> executeControl(args, reply)
                AgentLogic.CAP_OBSERVE -> executeObserve(args, reply)
                else -> reply("failed", "capability_unknown", null, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "agent 执行异常", t)
            reply("failed", "internal", null, null)
        }
    }

    // ---- shell ----

    private fun executeShell(
        args: Map<String, Any?>,
        reply: (String, String?, String?, Map<String, Any?>?) -> Unit,
    ) {
        val command = (args["command"] as? String) ?: (args["script"] as? String) ?: ""
        val timeoutS = (args["timeout_s"] as? Number)?.toInt()
            ?: AgentLogic.DEFAULT_TIMEOUT_S
        val session = (args["session"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val r = shell.execute(
            command, timeoutS, session,
            estop = { isEstop(appContext) || PauseFlags.isAllPaused(appContext) },
        )
        val summary = AgentLogic.truncateSummary(
            (if (r.outputTail.isBlank()) "(无输出)" else r.outputTail.trim()) +
                "\nexit=${r.exitCode ?: "?"}${if (r.timedOut) " timeout" else ""}",
        )
        val result = mutableMapOf<String, Any?>(
            "summary" to summary,
        )
        // 大输出引用（device.agent.output.read 可拉全量尾部）
        if (r.outputTail.length > AgentLogic.MAX_OUTPUT_CHARS / 2) {
            result["output_ref"] = "agent:out:${File(r.outputFile).name}"
        }
        when {
            r.timedOut -> reply("failed", "timeout", null, result)
            r.exitCode == null -> reply("unknown", null, null, result) // 结果不明，不谎报
            r.exitCode == 0 -> reply("executed", null, null, result)
            else -> reply("executed", null, "exit=${r.exitCode}", result)
        }
    }

    // ---- control（经 root input/am 命令；不依赖无障碍读内容权限） ----

    private fun executeControl(
        args: Map<String, Any?>,
        reply: (String, String?, String?, Map<String, Any?>?) -> Unit,
    ) {
        val action = (args["action"] as? String)!!
        val cmd = buildControlCommand(action, args)
        val r = shell.execute(cmd, 30, null,
            estop = { isEstop(appContext) || PauseFlags.isAllPaused(appContext) })
        when {
            r.timedOut ->
                reply("failed", "timeout", null, mapOf("summary" to "控制命令超时"))
            // 退出码非 0 = 命令失败（启动失败/设备拒绝），不谎报 executed
            r.exitCode != null && r.exitCode != 0 ->
                reply("failed", "internal", "exit=${r.exitCode}",
                    mapOf("summary" to "$action 失败（exit=${r.exitCode}）"))
            else ->
                reply("executed", null, null,
                    mapOf("summary" to "$action ok (exit=${r.exitCode ?: 0})"))
        }
    }

    private fun buildControlCommand(action: String, args: Map<String, Any?>): String = when (action) {
        "launch_app" -> {
            val pkg = args["package"] as String
            // 解析 launcher activity 再启动（比 monkey 快且明确）
            "LA=$(cmd package resolve-activity --brief $pkg | tail -1); am start -n \"${'$'}LA\""
        }
        "tap" -> {
            val x = (args["x"] as Number).toInt()
            val y = (args["y"] as Number).toInt()
            "input tap $x $y"
        }
        "swipe" -> {
            val x = (args["x"] as Number).toInt()
            val y = (args["y"] as Number).toInt()
            val x2 = (args["x2"] as Number).toInt()
            val y2 = (args["y2"] as Number).toInt()
            val dur = (args["duration_ms"] as? Number)?.toInt() ?: 300
            "input swipe $x $y $x2 $y2 $dur"
        }
        "key" -> "input keyevent ${args["key"]}"
        "input_text" -> "input text ${shellQuote(inputEscape(args["text"] as String))}"
        "media" -> when (args["media_op"] as String) {
            "play" -> "input keyevent KEYCODE_MEDIA_PLAY"
            "pause" -> "input keyevent KEYCODE_MEDIA_PAUSE"
            "play_pause" -> "input keyevent KEYCODE_MEDIA_PLAY_PAUSE"
            "next" -> "input keyevent KEYCODE_MEDIA_NEXT"
            "previous" -> "input keyevent KEYCODE_MEDIA_PREVIOUS"
            "seek_forward" -> "input keyevent KEYCODE_MEDIA_FAST_FORWARD"
            "seek_backward" -> "input keyevent KEYCODE_MEDIA_REWIND"
            else -> "true"
        }
        else -> "true"
    }

    /**
     * input text 的 Android 层转义：空格→%s、%→%25（input 命令的格式串语义）。
     * 注意顺序：先 % 后空格。
     */
    private fun inputEscape(text: String): String =
        text.replace("%", "%25").replace(" ", "%s")

    /** Shell 单引号包裹（$、反引号、双引号全部失去特殊语义）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---- observe ----

    private fun executeObserve(
        args: Map<String, Any?>,
        reply: (String, String?, String?, Map<String, Any?>?) -> Unit,
    ) {
        when ((args["mode"] as? String) ?: "ui_tree") {
            "ui_tree" -> {
                val r = shell.execute(
                    "uiautomator dump /sdcard/whale_agent_ui.xml >/dev/null 2>&1; echo done",
                    30, null,
                    estop = { isEstop(appContext) || PauseFlags.isAllPaused(appContext) },
                )
                val f = File("/sdcard/whale_agent_ui.xml")
                val text = try {
                    f.readText()
                } catch (t: Throwable) {
                    ""
                }
                if (text.isBlank()) {
                    reply("failed", "unavailable", null,
                        mapOf("summary" to "ui dump 为空（屏幕休眠/权限）"))
                    return
                }
                reply("executed", null, null, mapOf(
                    "summary" to AgentLogic.truncateSummary(
                        stripXmlNoise(text).take(AgentLogic.MAX_OUTPUT_CHARS),
                    ),
                    "output_ref" to "agent:ui:latest",
                ))
            }
            "screenshot" -> {
                val name = "shot-${System.currentTimeMillis()}.jpg"
                val outDir = File(appContext.filesDir, "agent/shots").apply { mkdirs() }
                val target = File(outDir, name)
                val r = shell.execute(
                    "screencap -p /sdcard/whale_agent_shot.png && echo ok", 30, null,
                    estop = { isEstop(appContext) || PauseFlags.isAllPaused(appContext) },
                )
                val png = File("/sdcard/whale_agent_shot.png")
                if (!png.exists() || png.length() == 0L) {
                    reply("failed", "unavailable", null,
                        mapOf("summary" to "screencap 无输出"))
                    return
                }
                val jpeg = compressToBudget(png)
                png.delete()
                if (jpeg == null) {
                    reply("failed", "internal", null, mapOf("summary" to "图像压缩失败"))
                    return
                }
                FileOutputStream(target).use { it.write(jpeg) }
                reply("executed", null, null, mapOf(
                    "summary" to "screenshot ${target.length()}B（JPEG 帧预算内）",
                    "output_ref" to "agent:shot:$name",
                ))
            }
        }
    }

    /** UI XML 噪音剥离（去 bounds/resource-id 等长尾属性，保文本结构）。 */
    private fun stripXmlNoise(xml: String): String {
        val noisyAttrs = Regex(
            "\\s(?:bounds|package|checkable|checked|clickable|enabled|focusable|focused|" +
                "scrollable|long-clickable|password|selected|index|class|resource-id|afise|content-desc)" +
                "=\"[^\"]*\"",
        )
        return xml.replace(noisyAttrs, "")
    }

    /** JPEG 阶梯压缩进帧预算（与证据路径同纪律）。 */
    private fun compressToBudget(png: File): ByteArray? {
        val src = BitmapFactory.decodeFile(png.path) ?: return null
        try {
            var bmp = src
            for (q in listOf(70, 55, 40)) {
                for (maxSide in listOf(1080, 720, 480)) {
                    val scaled = if (maxOf(bmp.width, bmp.height) > maxSide) {
                        val ratio = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                        Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true,
                        )
                    } else bmp
                    val out = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                    if (scaled !== bmp && scaled !== src) scaled.recycle()
                    val bytes = out.toByteArray()
                    if (bytes.size <= 256 * 1024) return bytes
                }
            }
            return null
        } finally {
            src.recycle()
        }
    }

    // ---- device.agent.output.read（大输出/截图拉取，经 sensor.query 通道） ----

    /**
     * output_ref 解析拉取：agent:out:<file>（会话/单次输出尾部）、
     * agent:shot:<file>（截图 base64）、agent:ui:latest（最近 UI dump 全文尾部）。
     */
    fun readOutput(ref: String): Map<String, Any?> {
        AgentLogic.outputReadError(
            ref,
            moduleEnabled = isEnabled(appContext),
            rootAuthorized = isRootAuthorized(appContext),
            allPaused = PauseFlags.isAllPaused(appContext),
            estop = isEstop(appContext),
        )?.let { return mapOf("error" to it) }
        val base = File(appContext.filesDir, "agent")
        return when {
            ref == "agent:ui:latest" -> {
                val f = File("/sdcard/whale_agent_ui.xml")
                if (!f.exists()) return mapOf("error" to "not_found")
                val text = stripXmlNoise(f.readText())
                mapOf(
                    "content_type" to "text",
                    "text" to text.takeLast(AgentShell.MAX_TAIL_CHARS),
                    "truncated" to (text.length > AgentShell.MAX_TAIL_CHARS),
                )
            }
            ref.startsWith("agent:out:") -> {
                val name = ref.removePrefix("agent:out:")
                val f = File(File(base, "out"), File(name).name)
                if (!f.exists()) return mapOf("error" to "not_found")
                val text = f.readText()
                mapOf(
                    "content_type" to "text",
                    "text" to text.takeLast(AgentShell.MAX_TAIL_CHARS),
                    "truncated" to (text.length > AgentShell.MAX_TAIL_CHARS),
                )
            }
            ref.startsWith("agent:shot:") -> {
                val name = ref.removePrefix("agent:shot:")
                val f = File(File(base, "shots"), File(name).name)
                if (!f.exists()) return mapOf("error" to "not_found")
                mapOf(
                    "content_type" to "image_jpeg",
                    "data_base64" to Base64.encodeToString(f.readBytes(), Base64.NO_WRAP),
                    "size_bytes" to f.length(),
                )
            }
            else -> mapOf("error" to "bad_ref")
        }
    }

    // ---- 开关 ----

    companion object {
        private const val TAG = "WhaleAgent"
        const val KEY_ENABLED = "agent_enabled"
        const val KEY_ROOT_AUTH = "agent_root_shell_authorized"
        const val KEY_ESTOP = "agent_estop"

        fun isEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun isRootAuthorized(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                .getBoolean(KEY_ROOT_AUTH, false)

        fun isEstop(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                .getBoolean(KEY_ESTOP, false)

        /** 紧急停止：销毁全部会话 + 置位（之后所有 agent 请求立即拒绝）。 */
        fun emergencyStop(context: Context, shell: AgentShell) {
            context.applicationContext
                .getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ESTOP, true).apply()
            shell.destroyAll()
            Log.w(TAG, "agent 紧急停止已生效")
        }

        fun clearEstop(context: Context) {
            context.applicationContext
                .getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ESTOP, false).apply()
        }
    }
}
