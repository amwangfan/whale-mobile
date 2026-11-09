package com.wangfanlab.whale.modules.agent2

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.wangfanlab.whale.CapabilityRegistry
import com.wangfanlab.whale.modules.agent.AgentModule
import com.wangfanlab.whale.modules.agent.AgentShell
import com.wangfanlab.whale.transport.v2.CapabilitiesV2
import com.wangfanlab.whale.transport.v2.ErrorCodeV2
import com.wangfanlab.whale.transport.v2.IdsV2
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2
import com.wangfanlab.whale.transport.v2.ReceiptStatus
import com.wangfanlab.whale.transport.v2.payloadLongV2
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * contracts/v2 设备动作族执行器（Root 版内核；capability → handler 注册到
 * [ReceiptDispatcherV2]，幂等/超时/first-terminal-wins 由分发器负责）。
 *
 * 覆盖能力（§5.1/§5.3）：
 * - 两变体共有：device.tap / long_press / swipe / key / wait / input_text /
 *   observe / app.launch / app.list；
 * - 仅 root 变体（[CapabilityRegistry.isRootEdition] 门控，daily 构建不创建
 *   handler——分发器侧将 fail-closed 回 unavailable）：device.shell /
 *   file.read / file.write / file.list / logcat。
 *   （device.screen.record 由 modules/recording 注册；device.stop 走
 *   [ReceiptDispatcherV2.cancel] 入口；device.status 属 sensor.query 路径，
 *   均不在本模块。）
 *
 * 执行底座：
 * - Root Shell 持续会话/超时真实生效/退出码检查/输出尾部 64KB/estop 熔断——
 *   直接复用现有 [AgentShell]（移植语义，不改原文件）；estop 开关沿用
 *   [AgentModule.isEstop] 的 whale_modules/agent_estop 键（与 v1 同一熔断面）；
 * - 坐标类动作：屏内校验（[Agent2Logic.validateCoords]）；
 * - 元素引用动作（element_id+observation_id）：§8 稳定键重校验——执行前取
 *   fresh UI 树，className+resourceId+contentDescription+bounds 恰好一个匹配，
 *   否则 failed/stale_observation，绝不沿用过期坐标；
 * - device.observe：uiautomator 树（Root 下 `uiautomator dump`；无障碍服务
 *   canRetrieveWindowContent=false 时内容不可读，故走 root 通道）+ 截图
 *   （ShotTaker30 经 ShadowCaptureService，失败时 root screencap 兜底）→
 *   观察 JSON 落盘，回执 output_ref 引用（agent:ui:obs_*）；
 * - 回执：v2 五终态 + result.summary（≤2000）+ verification（端侧可判定的
 *   做 passed/failed——app.launch 前台包名核对、file.write 大小核对；其余
 *   诚实标 skipped，禁止伪报 passed，§7.3）。
 */
class Agent2Module(
    context: Context,
    /** Root Shell 底座（可注入假实现测试；生产为真实 [AgentShell]）。 */
    private val shell: RootShell = RootShell.fromAgentShell(
        AgentShell(File(context.applicationContext.filesDir, "agent2/out")),
    ),
    /** 仅 root 构建允许 Root Shell；daily 构建不尝试 su。 */
    private val rootEdition: Boolean = CapabilityRegistry.isRootEdition(),
    /** 无障碍屏幕操作（与 ShadowCaptureService 复用，不新增服务）。 */
    private val accessibility: AccessibilityDeviceControl =
        AndroidAccessibilityDeviceControl(context.applicationContext),
    /** 优先按需读取无障碍 UI 节点；仅 Root 版在服务不可用时走 uiautomator。 */
    private val uiTreeSource: UiTreeSource = UiTreeSource {
        accessibility.dumpUiTree()
            ?: if (rootEdition) UiTreeSource.rootShell(shell).dump() else null
    },
    /** 截图来源（ShotTaker30 优先，root screencap 兜底；null=不可用）。 */
    private val screenshotSource: ScreenshotSource =
        ScreenshotSource.chain(context.applicationContext, shell),
    /** 前台包名探测：无障碍事件或 Root dumpsys（都不可用则不伪报验证）。 */
    private val foregroundProbe: ForegroundProbe = ForegroundProbe {
        accessibility.foregroundPackage()
            ?: if (rootEdition) ForegroundProbe.rootShell(shell).current() else null
    },
    /** 屏幕尺寸（坐标屏内校验用；缺省取应用资源 displayMetrics）。 */
    private val screenSize: () -> Pair<Int, Int> = {
        val m = context.applicationContext.resources.displayMetrics
        Pair(m?.widthPixels ?: 0, m?.heightPixels ?: 0)
    },
    /** estop 熔断开关（默认沿用 v1 agent_estop 键）。 */
    private val estop: () -> Boolean = { AgentModule.isEstop(context.applicationContext) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newCachedThreadPool { r ->
        Thread(r, "whale-agent2").apply { isDaemon = true }
    },
) {

    // ------------------------------------------------------------- 可注入执行底座

    /** Root Shell 执行口（与 [AgentShell.execute] 同语义，便于测试注入）。 */
    fun interface RootShell {
        fun execute(command: String, timeoutS: Int, sessionId: String?, estop: () -> Boolean): AgentShell.Result

        companion object {
            fun fromAgentShell(shell: AgentShell): RootShell = RootShell { cmd, t, s, e ->
                shell.execute(cmd, t, s, e)
            }
        }
    }

    /** UI 树来源。 */
    fun interface UiTreeSource {
        /** 返回 uiautomator XML 全文；不可用返回 null。 */
        fun dump(): String?

        companion object {
            /** Root 通道：`uiautomator dump` + 读全量输出文件（不受 64KB 尾部限制）。 */
            fun rootShell(shell: RootShell): UiTreeSource = UiTreeSource {
                val tmp = "/data/local/tmp/whale_agent2_ui.xml"
                val r = shell.execute(
                    "uiautomator dump $tmp >/dev/null 2>&1 && cat $tmp",
                    60, null, { false },
                )
                if (r.timedOut || r.exitCode != 0) return@UiTreeSource null
                val f = File(r.outputFile)
                if (!f.exists()) return@UiTreeSource null
                val text = try {
                    f.readText()
                } catch (t: Throwable) {
                    null
                }
                text?.takeIf { it.contains("<hierarchy") }?.take(MAX_UI_TREE_CHARS)
            }
        }
    }

    /** 截图来源（返回已落盘的 JPEG 文件；null=不可用）。 */
    fun interface ScreenshotSource {
        fun takeShot(): File?

        companion object {
            /** ShotTaker30（无障碍服务在跑且 API30+）→ root screencap 兜底。 */
            fun chain(context: Context, shell: RootShell): ScreenshotSource = ScreenshotSource {
                viaAccessibility(context) ?: viaRootScreencap(context, shell)
            }

            /**
             * 无障碍截屏：ShadowCaptureService 实例在跑时经 [com.wangfanlab.whale.modules.capture.ShotTaker30]
             * 取帧并压缩落盘（与 v1 screenshot 同预算纪律）。
             */
            private fun viaAccessibility(context: Context): File? {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
                val svc = com.wangfanlab.whale.modules.capture.ShadowCaptureService.serviceInstance
                    ?: return null
                val latch = java.util.concurrent.CountDownLatch(1)
                val holder = arrayOfNulls<Bitmap>(1)
                try {
                    com.wangfanlab.whale.modules.capture.ShotTaker30.take(
                        svc, Executor { r -> r.run() }, keepBitmap = true,
                    ) { _, _, _, bitmap ->
                        holder[0] = bitmap
                        latch.countDown()
                    }
                } catch (t: Throwable) {
                    return null
                }
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    holder[0]?.recycle()
                    return null
                }
                val bmp = holder[0] ?: return null
                return try {
                    saveJpeg(context, compressToBudget(bmp) ?: return null)
                } finally {
                    bmp.recycle()
                }
            }

            /** Root 兜底：screencap 到 /data/local/tmp 后拷入应用私有目录。 */
            private fun viaRootScreencap(context: Context, shell: RootShell): File? {
                val tmp = "/data/local/tmp/whale_agent2_shot.png"
                val dir = File(context.filesDir, "agent2/shots").apply { mkdirs() }
                val dest = File(dir, "shot-${System.currentTimeMillis()}.png")
                val r = shell.execute(
                    "screencap -p ${Agent2Logic.shellQuote(tmp)} && " +
                        "cp ${Agent2Logic.shellQuote(tmp)} ${Agent2Logic.shellQuote(dest.absolutePath)} && " +
                        "chmod 644 ${Agent2Logic.shellQuote(dest.absolutePath)} && rm -f ${Agent2Logic.shellQuote(tmp)}",
                    30, null, { false },
                )
                if (r.timedOut || r.exitCode != 0 || !dest.exists() || dest.length() == 0L) {
                    dest.delete()
                    return null
                }
                val bmp = BitmapFactory.decodeFile(dest.absolutePath) ?: run {
                    dest.delete(); return null
                }
                return try {
                    saveJpeg(context, compressToBudget(bmp) ?: return null)
                } finally {
                    bmp.recycle()
                    dest.delete()
                }
            }

            /** JPEG 阶梯压缩进 256KiB 帧预算（移植 v1 AgentModule 同纪律）。 */
            private fun compressToBudget(src: Bitmap): ByteArray? {
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
            }

            private fun saveJpeg(context: Context, bytes: ByteArray): File {
                val dir = File(context.filesDir, "agent2/shots").apply { mkdirs() }
                val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                FileOutputStream(f).use { it.write(bytes) }
                return f
            }
        }
    }

    /** 前台包名探测（app.launch 的 verification 与录制遮罩轮询用）。 */
    fun interface ForegroundProbe {
        fun current(): String?

        companion object {
            /** Root 通道：dumpsys window 的 mCurrentFocus 解析包名。 */
            fun rootShell(shell: RootShell): ForegroundProbe = ForegroundProbe {
                val r = shell.execute(
                    "dumpsys window displays | grep -E 'mCurrentFocus' | tail -1",
                    15, null, { false },
                )
                if (r.timedOut || r.exitCode != 0) return@ForegroundProbe null
                // 形如：mCurrentFocus=Window{xxx u0 com.pkg/com.pkg.Activity}
                Regex("""mCurrentFocus=\S+\s+\S+\s+([A-Za-z0-9._]+)/""")
                    .find(r.outputTail)?.groupValues?.get(1)
            }
        }
    }

    // ------------------------------------------------------------- 观察缓存（§8）

    /** 一次 UI 树观察（observation_id → 元素表；element_id 观察内局部）。 */
    data class Observation(
        val observationId: String,
        val capturedAt: Long,
        val elements: List<Agent2Logic.UiElement>,
        /** 元素总数超 [Agent2Logic.MAX_OBSERVE_ELEMENTS] 截断时为 true。 */
        val truncated: Boolean,
    )

    /** 端侧最近观察缓存（LRU，容量 [OBS_CACHE_CAP]；进程内存活，不持久化）。 */
    class ObservationStore(private val cap: Int = OBS_CACHE_CAP) {
        private val map = object : LinkedHashMap<String, Observation>(cap, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Observation>?): Boolean =
                size > cap
        }

        @Synchronized
        fun put(obs: Observation) {
            map[obs.observationId] = obs
        }

        @Synchronized
        fun get(observationId: String): Observation? = map[observationId]

        @Synchronized
        fun size(): Int = map.size
    }

    private val appContext = context.applicationContext
    private val outDir = File(appContext.filesDir, "agent2/out").apply { mkdirs() }
    private val uiDir = File(appContext.filesDir, "agent2/ui").apply { mkdirs() }

    /** 观察缓存（模块内唯一；device.observe 写入，元素引用动作读取）。 */
    val observations = ObservationStore()

    /** root 可用性惰性探测缓存（null=未探测；避免每个动作都跑一次 `su id`）。 */
    @Volatile
    private var rootAvailable: Boolean? = null

    // ------------------------------------------------------------- 注册（daily/root 差异化）

    /**
     * 注册 v2 动作族 handler 到分发器。Root 独有族（shell/file/logcat）仅
     * root 变体注册——daily 构建这些能力由分发器 fail-closed 回
     * failed/unavailable（§16 不悬挂 received）。
     */
    fun registerTo(dispatcher: ReceiptDispatcherV2) {
        // 两变体共有动作族
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_TAP, ::handleTapLike)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_LONG_PRESS, ::handleTapLike)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_SWIPE, ::handleTapLike)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_KEY, ::handleSimpleShell)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_INPUT_TEXT, ::handleSimpleShell)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_WAIT, ::handleWait)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_OBSERVE, ::handleObserve)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_APP_LAUNCH, ::handleAppLaunch)
        dispatcher.registerHandler(CapabilitiesV2.DEVICE_APP_LIST, ::handleSimpleShell)
        if (rootEdition) {
            // Root 版独有（§5.2：daily 不注册、不声明）
            dispatcher.registerHandler(CapabilitiesV2.DEVICE_SHELL, ::handleShell)
            dispatcher.registerHandler(CapabilitiesV2.DEVICE_FILE_READ, ::handleFileRead)
            dispatcher.registerHandler(CapabilitiesV2.DEVICE_FILE_WRITE, ::handleFileWrite)
            dispatcher.registerHandler(CapabilitiesV2.DEVICE_FILE_LIST, ::handleFileList)
            dispatcher.registerHandler(CapabilitiesV2.DEVICE_LOGCAT, ::handleLogcat)
        }
        Log.i(TAG, "agent2 动作族已注册（rootEdition=$rootEdition）")
    }

    // ------------------------------------------------------------- 公共执行骨架

    /** 一次性动作回复包装（保证恰好 reply 一次）。 */
    private class OnceReply(private val delegate: (ReceiptDispatcherV2.ActionOutcome) -> Unit) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun reply(outcome: ReceiptDispatcherV2.ActionOutcome) {
            if (done.compareAndSet(false, true)) delegate(outcome)
        }
    }

    private fun fail(code: String, summary: String? = null) = ReceiptDispatcherV2.ActionOutcome(
        ReceiptStatus.FAILED, errorCode = code, resultSummary = summary,
    )

    /** estop 前置检查：命中即 failed/estop_active（§15 拒绝码）。 */
    private fun estopActive(): Boolean = try {
        estop()
    } catch (t: Throwable) {
        false
    }

    /** root 可用性探测（惰性 + 缓存；estop 熔断后由 [resetRootProbe] 复位）。 */
    private fun ensureRoot(): Boolean {
        rootAvailable?.let { return it }
        val ok = try {
            val r = shell.execute("id", 10, null) { false }
            !r.timedOut && r.exitCode == 0 && r.outputTail.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
        rootAvailable = ok
        return ok
    }

    /** root 探测复位（estop 解除/会话重建后调用）。 */
    fun resetRootProbe() {
        rootAvailable = null
    }

    /** 执行 shell 命令并把 [AgentShell.Result] 映射为 v2 终态（§7.2/§15）。 */
    private fun runShellOutcome(
        command: String,
        timeoutS: Int,
        sessionId: String? = null,
        successSummary: (AgentShell.Result) -> String,
        outputRefOf: (AgentShell.Result) -> String? = { null },
    ): ReceiptDispatcherV2.ActionOutcome {
        val r = shell.execute(command, timeoutS, sessionId) { estopActive() }
        return shellResultToOutcome(r, successSummary, outputRefOf)
    }

    /** shell 结果 → 五终态映射（超时→timeout；退出码非 0→failed/internal；结果不明→unknown）。 */
    private fun shellResultToOutcome(
        r: AgentShell.Result,
        successSummary: (AgentShell.Result) -> String,
        outputRefOf: (AgentShell.Result) -> String? = { null },
    ): ReceiptDispatcherV2.ActionOutcome {
        val tail = r.outputTail.trim()
        when {
            // estop 熔断（命令未执行/被强杀）——已在执行中则按 cancelled 终结
            estopActive() && (r.exitCode == null) -> return ReceiptDispatcherV2.ActionOutcome(
                ReceiptStatus.CANCELLED, errorCode = ErrorCodeV2.ESTOP_ACTIVE,
                resultSummary = "紧急停止生效，动作已熔断",
            )
            r.timedOut -> return ReceiptDispatcherV2.ActionOutcome(
                ReceiptStatus.TIMEOUT, errorCode = ErrorCodeV2.TIMEOUT,
                resultSummary = Agent2Logic.truncateSummary(
                    (if (tail.isBlank()) "(无输出)" else tail) + "\n[timeout]",
                ),
                outputRef = outputRefOf(r),
            )
            r.exitCode == null -> return ReceiptDispatcherV2.ActionOutcome(
                // 退出码缺失 = 结果不明（v1 同款诚实语义，不谎报）
                ReceiptStatus.UNKNOWN,
                resultSummary = Agent2Logic.truncateSummary(
                    if (tail.isBlank()) "执行结果不明（无退出码）" else tail,
                ),
                outputRef = outputRefOf(r),
            )
            r.exitCode != 0 -> return ReceiptDispatcherV2.ActionOutcome(
                ReceiptStatus.FAILED, errorCode = ErrorCodeV2.INTERNAL,
                resultSummary = Agent2Logic.truncateSummary(
                    (if (tail.isBlank()) "(无输出)" else tail) + "\nexit=${r.exitCode}",
                ),
                providerRef = "exit=${r.exitCode}",
                outputRef = outputRefOf(r),
            )
        }
        val summary = Agent2Logic.truncateSummary(successSummary(r))
        return ReceiptDispatcherV2.ActionOutcome(
            ReceiptStatus.SUCCEEDED,
            resultSummary = summary,
            outputRef = outputRefOf(r),
            verificationStatus = "skipped",
            verificationSummary = "端侧未做执行后状态核对",
        )
    }

    /** 无障碍结果映射 v2 回执；提交后超时/取消按 unknown，禁止自动换 Root 重放。 */
    private fun accessibilityOutcome(r: AccessibilityDeviceControl.Result): ReceiptDispatcherV2.ActionOutcome =
        when (r.status) {
            AccessibilityDeviceControl.Status.SUCCEEDED -> ReceiptDispatcherV2.ActionOutcome(
                ReceiptStatus.SUCCEEDED,
                resultSummary = r.reason.ifBlank { "无障碍动作已完成" },
                providerRef = "accessibility",
                verificationStatus = "skipped",
                verificationSummary = "动作已由系统回调完成；未额外验证界面状态",
            )
            AccessibilityDeviceControl.Status.UNKNOWN -> ReceiptDispatcherV2.ActionOutcome(
                ReceiptStatus.UNKNOWN,
                resultSummary = r.reason.ifBlank { "无障碍动作结果不明，未自动重试" },
                providerRef = "accessibility",
            )
            AccessibilityDeviceControl.Status.UNAVAILABLE ->
                fail(ErrorCodeV2.UNAVAILABLE, r.reason.ifBlank { "无障碍服务不可用" })
            AccessibilityDeviceControl.Status.FAILED ->
                fail(ErrorCodeV2.UNAVAILABLE, r.reason.ifBlank { "无障碍动作未执行" })
        }

    /** 输出尾部超过摘要一半时给 output_ref（大输出经 device.output.read 拉取，§7.3）。 */
    private fun outRefIfLarge(r: AgentShell.Result): String? =
        if (r.outputFile.isNotBlank() && r.outputTail.length > 1000) {
            "agent:out:${File(r.outputFile).name}"
        } else {
            null
        }

    // ------------------------------------------------------------- 坐标/元素落点解析

    /**
     * 解析动作落点：
     * - 坐标参数：直接使用（屏内校验已在 [Agent2Logic.validate] 完成）；
     * - element_id+observation_id：查观察缓存 → §8 稳定键 fresh 重校验
     *   （恰好一个匹配取 fresh bounds 中心；0/≥2 → stale_observation）。
     */
    private fun resolvePoint(
        args: Map<String, Any?>,
        observationId: String?,
        xKey: String = "x",
        yKey: String = "y",
    ): Pair<Pair<Int, Int>?, ReceiptDispatcherV2.ActionOutcome?> {
        if (!args.containsKey("element_id")) {
            val x = (args[xKey] as? Number)?.toInt()
            val y = (args[yKey] as? Number)?.toInt()
            if (x == null || y == null) {
                return null to fail(ErrorCodeV2.INVALID_MESSAGE, "缺少坐标 $xKey/$yKey")
            }
            return Pair(x, y) to null
        }
        val elementId = (args["element_id"] as Number).toInt()
        val obsId = observationId
            ?: return null to fail(ErrorCodeV2.INVALID_MESSAGE, "element_id 缺 observation_id")
        val obs = observations.get(obsId)
            ?: return null to fail(
                ErrorCodeV2.STALE_OBSERVATION,
                "观察 $obsId 不在端侧缓存（过期/跨重启）",
            )
        val target = obs.elements.firstOrNull { it.elementId == elementId }
            ?: return null to fail(
                ErrorCodeV2.STALE_OBSERVATION,
                "element_id=$elementId 不在观察 $obsId 中",
            )
        // §8：fresh 观察中按稳定键重校验，恰好一个匹配
        val xml = uiTreeSource.dump()
            ?: return null to fail(ErrorCodeV2.UNAVAILABLE, "fresh UI 树不可用，无法重校验")
        val fresh = try {
            Agent2Logic.parseUiTree(xml)
        } catch (t: Throwable) {
            return null to fail(ErrorCodeV2.INTERNAL, "fresh UI 树解析失败: ${t.message}")
        }
        return when (val verdict = Agent2Logic.revalidate(target, fresh)) {
            is Agent2Logic.MatchVerdict.Unique -> {
                val center = Agent2Logic.boundsCenter(verdict.fresh.bounds)
                    ?: return null to fail(ErrorCodeV2.INTERNAL, "fresh bounds 非法")
                center to null
            }
            is Agent2Logic.MatchVerdict.Stale -> null to fail(
                ErrorCodeV2.STALE_OBSERVATION,
                "稳定键匹配数=${verdict.matchedCount}（要求恰好 1），判定观察过期",
            )
        }
    }

    // ------------------------------------------------------------- 各能力 handler

    /** tap / long_press / swipe：坐标或元素落点 → input 命令。 */
    private fun handleTapLike(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val args = request.arguments
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(request.capability, args, w, h)) {
                is Agent2Logic.Decision.Reject ->
                    once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    val (point, err) = resolvePoint(args, request.observationId)
                    if (err != null) {
                        once.reply(err)
                        return@execute
                    }
                    if (rootEdition && ensureRoot()) {
                        val cmd = Agent2Logic.buildCommand(request.capability, args, point!!)
                        if (cmd == null) {
                            once.reply(fail(ErrorCodeV2.INVALID_MESSAGE, "命令拼装失败"))
                            return@execute
                        }
                        once.reply(
                            runShellOutcome(cmd, 30, null, { r ->
                                "${request.capability} ok (${point.first},${point.second}) exit=${r.exitCode}"
                            }),
                        )
                    } else if (accessibility.available()) {
                        once.reply(accessibilityOutcome(accessibility.execute(request.capability, args, point)))
                    } else {
                        once.reply(fail(ErrorCodeV2.UNAVAILABLE, "需要启用 Whale 无障碍服务" +
                            (if (rootEdition) "或提供 Root" else "")))
                    }
                }
            }
        }
    }

    /** key / input_text / app.list：无落点的简单 shell 命令。 */
    private fun handleSimpleShell(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(request.capability, request.arguments, w, h)) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    if (!rootEdition || !ensureRoot()) {
                        if (request.capability == CapabilitiesV2.DEVICE_APP_LIST) {
                            val filter = (request.arguments["filter"] as? String) ?: "all"
                            val pkgs = accessibility.listLaunchableApps(filter)
                            if (pkgs == null) {
                                once.reply(fail(ErrorCodeV2.UNAVAILABLE, "应用列表不可查询"))
                                return@execute
                            }
                            val file = File(outDir, "apps-" + System.nanoTime() + ".log")
                            try {
                                file.writeText(pkgs.joinToString("\n") { "package:" + it } + "\n")
                            } catch (t: Throwable) {
                                once.reply(fail(ErrorCodeV2.INTERNAL, "应用列表保存失败"))
                                return@execute
                            }
                            once.reply(ReceiptDispatcherV2.ActionOutcome(
                                ReceiptStatus.SUCCEEDED,
                                resultSummary = "已列出 " + pkgs.size + " 个可启动应用（Android 可见范围，非全量）",
                                outputRef = "agent:out:" + file.name,
                                providerRef = "package_manager",
                                verificationStatus = "skipped",
                                verificationSummary = "应用列表查询",
                            ))
                        } else if (accessibility.available()) {
                            once.reply(accessibilityOutcome(
                                accessibility.execute(request.capability, request.arguments, null),
                            ))
                        } else {
                            once.reply(fail(ErrorCodeV2.UNAVAILABLE, "需要启用 Whale 无障碍服务"))
                        }
                        return@execute
                    }
                    val cmd = Agent2Logic.buildCommand(request.capability, request.arguments, null)
                    if (cmd == null) {
                        once.reply(fail(ErrorCodeV2.INVALID_MESSAGE, "命令拼装失败"))
                        return@execute
                    }
                    once.reply(
                        runShellOutcome(cmd, 30, null, { r ->
                            when (request.capability) {
                                CapabilitiesV2.DEVICE_APP_LIST -> {
                                    val count = File(r.outputFile).takeIf { it.exists() }
                                        ?.readLines()?.count { it.startsWith("package:") } ?: 0
                                    "已列出 $count 个应用包"
                                }
                                else -> "${request.capability} ok exit=${r.exitCode}"
                            }
                        }, ::outRefIfLarge),
                    )
                }
            }
        }
    }

    /** device.wait：本地等待（estop 轮询熔断；不消耗 shell）。 */
    private fun handleWait(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(
                CapabilitiesV2.DEVICE_WAIT, request.arguments, w, h,
            )) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    val durationMs = payloadLongV2(request.arguments, "duration_ms") ?: 0L
                    val deadline = clock() + durationMs
                    while (clock() < deadline) {
                        if (estopActive()) {
                            once.reply(
                                ReceiptDispatcherV2.ActionOutcome(
                                    ReceiptStatus.CANCELLED, errorCode = ErrorCodeV2.ESTOP_ACTIVE,
                                    resultSummary = "等待被紧急停止熔断",
                                ),
                            )
                            return@execute
                        }
                        try {
                            Thread.sleep(minOf(100L, deadline - clock()))
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    once.reply(
                        ReceiptDispatcherV2.ActionOutcome(
                            ReceiptStatus.SUCCEEDED,
                            resultSummary = "已等待 ${durationMs}ms",
                            verificationStatus = "skipped",
                            verificationSummary = "等待类动作无状态可核对",
                        ),
                    )
                }
            }
        }
    }

    /** device.app.launch：am start + 前台包名核对（verification passed/failed）。 */
    private fun handleAppLaunch(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(
                CapabilitiesV2.DEVICE_APP_LAUNCH, request.arguments, w, h,
            )) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    val useRoot = rootEdition && ensureRoot()
                    if (!useRoot && !accessibility.available()) {
                        once.reply(fail(ErrorCodeV2.UNAVAILABLE, "需要启用 Whale 无障碍服务"))
                        return@execute
                    }
                    val pkg = (request.arguments["package"] as String).trim()
                    val base = if (useRoot) {
                        val cmd = Agent2Logic.buildCommand(
                            CapabilitiesV2.DEVICE_APP_LAUNCH, request.arguments, null,
                        )!!
                        runShellOutcome(cmd, 30, null, { r ->
                            "已发起启动 $pkg exit=${r.exitCode}"
                        })
                    } else {
                        accessibilityOutcome(accessibility.execute(request.capability, request.arguments, null))
                    }
                    if (base.status != ReceiptStatus.SUCCEEDED) {
                        once.reply(base)
                        return@execute
                    }
                    // verification：轮询前台包名（≤3s），不可判定则 skipped（诚实）
                    val deadline = clock() + 3_000
                    var foreground: String? = null
                    while (clock() < deadline) {
                        foreground = try {
                            foregroundProbe.current()
                        } catch (t: Throwable) {
                            null
                        }
                        if (foreground == pkg) break
                        try {
                            Thread.sleep(300)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    val (vStatus, vSummary) = when {
                        foreground == pkg -> "passed" to "前台包名=$pkg"
                        foreground != null -> "failed" to "前台包名=$foreground（期望 $pkg）"
                        else -> "skipped" to "前台包名不可判定"
                    }
                    once.reply(
                        ReceiptDispatcherV2.ActionOutcome(
                            ReceiptStatus.SUCCEEDED,
                            resultSummary = "已发起启动 $pkg",
                            providerRef = if (useRoot) "root" else "accessibility",
                            verificationStatus = vStatus, verificationSummary = vSummary,
                        ),
                    )
                }
            }
        }
    }

    /**
     * device.observe：UI 树 + 截图 → 观察 JSON 落盘，output_ref=agent:ui:obs_*。
     * 观察写入 [observations] 缓存（供后续 element_id 引用动作的 §8 重校验）。
     */
    private fun handleObserve(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val options = try { ObserveOptions.parse(request.arguments) }
            catch (e: IllegalArgumentException) {
                once.reply(fail(ErrorCodeV2.INVALID_MESSAGE, "观察参数无效"))
                return@execute
            }
            if (estopActive()) {
                once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝观察"))
                return@execute
            }
            // 灭屏时无障碍读不到窗口内容：先短暂唤醒（WAKE_LOCK +
            // ACQUIRE_CAUSES_WAKEUP），观察完成后由 ON_AFTER_RELEASE 自动还原。
            // 用户设定：允许灭屏、不锁屏；观察即真实使用，故唤醒是忠实执行。
            wakeScreenForObserve()
            val xml = if (options.uiTree) uiTreeSource.dump() else null
            if (options.uiTree && xml == null) {
                once.reply(
                    fail(
                        ErrorCodeV2.UNAVAILABLE,
                        "UI 树不可用（需 root uiautomator dump 或内容可读的无障碍服务）",
                    ),
                )
                return@execute
            }
            val all = try {
                if (options.uiTree) Agent2Logic.parseUiTree(xml!!) else emptyList()
            } catch (t: Throwable) {
                once.reply(fail(ErrorCodeV2.INTERNAL, "UI 树解析失败: ${t.message}"))
                return@execute
            }
            val truncated = all.size > options.maxElements
            val elements = all.take(options.maxElements)
            val obsId = IdsV2.newObservationId()
            // 截图（失败不拖垮观察：摘要注明，verification 仍 skipped）
            val shotFile = try {
                if (options.screenshot) screenshotSource.takeShot() else null
            } catch (t: Throwable) {
                Log.w(TAG, "截图失败（观察继续）: ${t.message}")
                null
            }
            val shotRef = shotFile?.let { "agent:shot:${it.name}" }
            val (w, h) = screenSize()
            observations.put(Observation(obsId, clock(), elements, truncated))
            // 观察负载落盘（§8：负载本体经 output_ref 拉取，不占 256KiB 帧预算）
            val obsFile = File(uiDir, "$obsId.json")
            try {
                obsFile.writeText(buildObservationJson(obsId, elements, truncated, shotRef, w, h))
            } catch (t: Throwable) {
                once.reply(fail(ErrorCodeV2.INTERNAL, "观察落盘失败: ${t.message}"))
                return@execute
            }
            once.reply(
                ReceiptDispatcherV2.ActionOutcome(
                    ReceiptStatus.SUCCEEDED,
                    resultSummary = Agent2Logic.truncateSummary(
                        "观察 $obsId：${elements.size} 个元素" +
                            (if (truncated) "（超上限截断，原 ${all.size}）" else "") +
                            (if (shotRef != null) "，截图 $shotRef" else "，截图不可用"),
                    ),
                    outputRef = "agent:ui:$obsId",
                    verificationStatus = "skipped",
                    verificationSummary = "观察类动作无执行后状态核对",
                ),
            )
        }
    }

    /**
     * 灭屏时短暂点亮屏幕以读取窗口内容（nova 实测：灭屏/Dozing 下
     * 无障碍 rootInActiveWindow 为空 → UI 树不可用）。
     * 用 deprecated 的 SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 是
     * 唯一不需要系统权限的唤醒路径；ON_AFTER_RELEASE 让屏幕随后自然熄灭。
     */
    private fun wakeScreenForObserve() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "whale:agent2-observe",
            )
            lock.acquire(1500)
            lock.release()
            Thread.sleep(300)  // 等窗口就绪
        } catch (t: Throwable) {
            Log.w(TAG, "唤醒失败（继续尝试观察）: ${t.message}")
        }
    }

    /** 观察负载 JSON（element_id 观察内局部；fingerprint 跨观察稳定，§8）。 */
    internal fun buildObservationJson(
        obsId: String,
        elements: List<Agent2Logic.UiElement>,
        truncated: Boolean,
        shotRef: String?,
        screenW: Int,
        screenH: Int,
    ): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"observation_id\":\"").append(obsId).append("\",")
        sb.append("\"captured_at\":").append(clock()).append(',')
        // 前台应用（§5 device.status 语义进观察；模型需要知道自己在哪里）
        val fg = runCatching { foregroundProbe.current() }.getOrNull()
        sb.append("\"foreground_package\":").append(jsonStr(fg ?: "")).append(',')
        sb.append("\"screen\":{\"w\":").append(screenW).append(",\"h\":").append(screenH).append("},")
        if (shotRef != null) sb.append("\"screenshot_ref\":\"").append(shotRef).append("\",")
        sb.append("\"truncated\":").append(truncated).append(',')
        sb.append("\"elements\":[")
        elements.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"element_id\":").append(e.elementId).append(',')
            sb.append("\"class_name\":").append(jsonStr(e.className)).append(',')
            sb.append("\"resource_id\":").append(jsonStr(e.resourceId)).append(',')
            sb.append("\"content_desc\":").append(jsonStr(e.contentDescription)).append(',')
            sb.append("\"text\":").append(jsonStr(e.text)).append(',')
            sb.append("\"bounds\":").append(jsonStr(e.bounds)).append(',')
            sb.append("\"clickable\":").append(e.clickable).append(',')
            sb.append("\"scrollable\":").append(e.scrollable).append(',')
            sb.append("\"editable\":").append(e.editable).append(',')
            sb.append("\"fingerprint\":\"").append(e.fingerprint).append('"')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------- Root 独有族

    /** device.shell（仅 root）：持续会话/超时/退出码/64KB 尾部/estop——AgentShell 移植。 */
    private fun handleShell(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(
                CapabilitiesV2.DEVICE_SHELL, request.arguments, w, h,
            )) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    if (!ensureRoot()) {
                        once.reply(fail(ErrorCodeV2.UNAVAILABLE, "root shell 不可用"))
                        return@execute
                    }
                    val a = request.arguments
                    val cmd = (a["command"] as? String) ?: (a["script"] as? String)!!
                    val timeoutS = (a["timeout_s"] as? Number)?.toInt()
                        ?: Agent2Logic.DEFAULT_TIMEOUT_S
                    val session = (a["session"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    once.reply(
                        runShellOutcome(cmd, timeoutS, session, { r ->
                            val tail = r.outputTail.trim()
                            (if (tail.isBlank()) "(无输出)" else tail) + "\nexit=${r.exitCode}"
                        }, ::outRefIfLarge),
                    )
                }
            }
        }
    }

    /** device.file.read（仅 root）：cat/base64 读出（大文件经 output_ref 拉尾部）。 */
    private fun handleFileRead(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) = handleSimpleRootFile(request, reply, CapabilitiesV2.DEVICE_FILE_READ) { r ->
        val size = File(r.outputFile).takeIf { it.exists() }?.length() ?: 0L
        "已读取 ${request.arguments["path"]}（输出 $size B" +
            (if (r.outputTail.length >= AgentShell.MAX_TAIL_CHARS) "，已截尾 64KB" else "") + "）"
    }

    /** device.file.list（仅 root）：ls -la。 */
    private fun handleFileList(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) = handleSimpleRootFile(request, reply, CapabilitiesV2.DEVICE_FILE_LIST) { _ ->
        "已列出 ${request.arguments["path"]}"
    }

    /** device.logcat（仅 root）：logcat -d 抓取。 */
    private fun handleLogcat(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) = handleSimpleRootFile(request, reply, CapabilitiesV2.DEVICE_LOGCAT) { r ->
        val lines = File(r.outputFile).takeIf { it.exists() }?.readLines()?.size ?: 0
        "logcat 抓取 $lines 行"
    }

    /** file.read/file.list/logcat 公共骨架：校验 → estop → root → 命令 → 终态。 */
    private fun handleSimpleRootFile(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
        capability: String,
        summaryOf: (AgentShell.Result) -> String,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(capability, request.arguments, w, h)) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    if (!ensureRoot()) {
                        once.reply(fail(ErrorCodeV2.UNAVAILABLE, "root shell 不可用"))
                        return@execute
                    }
                    val cmd = Agent2Logic.buildCommand(capability, request.arguments, null)
                    if (cmd == null) {
                        once.reply(fail(ErrorCodeV2.INVALID_MESSAGE, "命令拼装失败"))
                        return@execute
                    }
                    once.reply(runShellOutcome(cmd, 60, null, { r -> summaryOf(r) }, ::outRefIfLarge))
                }
            }
        }
    }

    /**
     * device.file.write（仅 root）：content_base64 先落应用私有临时文件，
     * 再经 root `cat > path` 写入目标；写后 `stat -c %s` 核对大小（verification）。
     */
    private fun handleFileWrite(
        request: ReceiptDispatcherV2.ActionRequestV2,
        reply: (ReceiptDispatcherV2.ActionOutcome) -> Unit,
    ) {
        val once = OnceReply(reply)
        executor.execute {
            val (w, h) = screenSize()
            when (val d = Agent2Logic.validate(
                CapabilitiesV2.DEVICE_FILE_WRITE, request.arguments, w, h,
            )) {
                is Agent2Logic.Decision.Reject -> once.reply(fail(d.errorCode, d.reason))
                is Agent2Logic.Decision.Run -> {
                    if (estopActive()) {
                        once.reply(fail(ErrorCodeV2.ESTOP_ACTIVE, "紧急停止激活，拒绝执行"))
                        return@execute
                    }
                    if (!ensureRoot()) {
                        once.reply(fail(ErrorCodeV2.UNAVAILABLE, "root shell 不可用"))
                        return@execute
                    }
                    val a = request.arguments
                    val path = a["path"] as String
                    val append = a["append"] as? Boolean ?: false
                    val bytes = try {
                        Base64.decode(a["content_base64"] as String, Base64.DEFAULT)
                    } catch (t: Throwable) {
                        once.reply(fail(ErrorCodeV2.INVALID_MESSAGE, "content_base64 解码失败"))
                        return@execute
                    }
                    val tmp = File(outDir, "write-${System.nanoTime()}.bin")
                    try {
                        FileOutputStream(tmp).use { it.write(bytes) }
                    } catch (t: Throwable) {
                        once.reply(fail(ErrorCodeV2.INTERNAL, "临时文件落盘失败: ${t.message}"))
                        return@execute
                    }
                    val redirect = if (append) ">>" else ">"
                    val cmd = "cat ${Agent2Logic.shellQuote(tmp.absolutePath)} " +
                        "$redirect ${Agent2Logic.shellQuote(path)}; rc=\$?; " +
                        "rm -f ${Agent2Logic.shellQuote(tmp.absolutePath)}; exit \$rc"
                    val base = shellResultToOutcome(
                        shell.execute(cmd, 60, null) { estopActive() },
                        { r -> "已写入 $path（${bytes.size} B）exit=${r.exitCode}" },
                    )
                    if (base.status != ReceiptStatus.SUCCEEDED) {
                        once.reply(base)
                        return@execute
                    }
                    // verification：stat 大小核对（不可判定则 skipped，诚实）
                    val statR = shell.execute("stat -c %s ${Agent2Logic.shellQuote(path)}", 15, null) { false }
                    val actual = statR.outputTail.trim().toLongOrNull()
                    val (vStatus, vSummary) = when {
                        actual == null -> "skipped" to "stat 不可判定"
                        append -> "skipped" to "append 模式不做大小核对"
                        actual == bytes.size.toLong() -> "passed" to "文件大小 $actual B 与写入一致"
                        else -> "failed" to "文件大小 $actual B ≠ 写入 ${bytes.size} B"
                    }
                    once.reply(
                        ReceiptDispatcherV2.ActionOutcome(
                            ReceiptStatus.SUCCEEDED,
                            resultSummary = "已写入 $path（${bytes.size} B）",
                            verificationStatus = vStatus, verificationSummary = vSummary,
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- device.output.read 拉取口

    /**
     * output_ref 拉取（供未来 sensor.query device.output.read 路由接线）：
     * - agent:out:<file>（shell/控制命令输出尾部 64KB 文本）
     * - agent:ui:obs_*（观察负载 JSON 全文）
     * - agent:shot:shot-*.jpg（截图 base64）
     * 引用做严格格式校验（§10：拒绝路径形态/畸形引用，绝不泄露本地绝对路径）。
     */
    fun readOutput(ref: String, offset: Long, maxBytes: Int): Map<String, Any?> {
        val error = validateOutputRef(ref)
        if (error != null) return mapOf("error" to error)
        val file = when {
            ref.startsWith("agent:out:") -> File(outDir, ref.removePrefix("agent:out:"))
            ref.startsWith("agent:ui:") -> File(uiDir, ref.removePrefix("agent:ui:") + ".json")
            ref.startsWith("agent:shot:") -> File(File(appContext.filesDir, "agent2/shots"),
                ref.removePrefix("agent:shot:"))
            else -> return mapOf("error" to "bad_ref")
        }
        if (!file.isFile) return mapOf("error" to "not_found")
        return com.wangfanlab.whale.transport.v2.OutputPages.read(file,
            com.wangfanlab.whale.transport.v2.OutputPages.Request(offset, maxBytes))
    }

    fun readOutput(ref: String): Map<String, Any?> {
        val error = validateOutputRef(ref)
        if (error != null) return mapOf("error" to error)
        return when {
            ref.startsWith("agent:out:") -> {
                val f = File(outDir, File(ref.removePrefix("agent:out:")).name)
                if (!f.exists()) return mapOf("error" to "not_found")
                val text = f.readText()
                mapOf(
                    "content_type" to "text",
                    "text" to text.takeLast(AgentShell.MAX_TAIL_CHARS),
                    "truncated" to (text.length > AgentShell.MAX_TAIL_CHARS),
                )
            }
            ref.startsWith("agent:ui:") -> {
                val f = File(uiDir, File(ref.removePrefix("agent:ui:")).name + ".json")
                if (!f.exists()) return mapOf("error" to "not_found")
                val text = f.readText()
                mapOf(
                    "content_type" to "text",
                    "text" to text.takeLast(AgentShell.MAX_TAIL_CHARS),
                    "truncated" to (text.length > AgentShell.MAX_TAIL_CHARS),
                )
            }
            ref.startsWith("agent:shot:") -> {
                val f = File(File(appContext.filesDir, "agent2/shots"),
                    File(ref.removePrefix("agent:shot:")).name)
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

    /** §10 引用白名单校验（未知类别/路径形态一律 bad_ref）。 */
    private fun validateOutputRef(ref: String): String? {
        if (".." in ref || ref.contains('\\')) return "bad_ref"
        val ok = Regex("^agent:out:[A-Za-z0-9_.-]{1,128}$").matches(ref) ||
            Regex("^agent:ui:obs_[0-9A-HJKMNP-TV-Z]{26}$").matches(ref) ||
            Regex("^agent:shot:shot-[0-9]+[.](jpg|png)$").matches(ref)
        return if (ok) null else "bad_ref"
    }

    companion object {
        private const val TAG = "WhaleAgent2"
        const val OBS_CACHE_CAP = 4

        /** UI 树全量读取上限（2MB；防 dump 异常膨胀撑爆内存）。 */
        const val MAX_UI_TREE_CHARS = 2 * 1024 * 1024
    }
}
