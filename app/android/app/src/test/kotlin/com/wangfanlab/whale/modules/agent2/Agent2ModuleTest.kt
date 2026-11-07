package com.wangfanlab.whale.modules.agent2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.agent.AgentModule
import com.wangfanlab.whale.modules.agent.AgentShell
import com.wangfanlab.whale.transport.v2.ActionDedupStoreV2
import com.wangfanlab.whale.transport.v2.CapabilitiesV2
import com.wangfanlab.whale.transport.v2.IdsV2
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2
import com.wangfanlab.whale.transport.v2.Ulid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Agent2Module 执行器单测（Robolectric）：经真实 [ReceiptDispatcherV2] 走完整
 * 回执状态机（received → 终态），RootShell/UiTreeSource/ScreenshotSource 注入假实现。
 *
 * 覆盖：参数校验映射、坐标越界、§8 stale 三判据（缓存未命中/0 匹配/≥2 匹配）、
 * shell 超时/退出码/estop 映射、root 门控（daily 无 shell handler）、
 * observe output_ref、file.write verification。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Agent2ModuleTest {

    private lateinit var context: Context
    private val dbName = "test_agent2_dedup.db"
    private lateinit var store: ActionDedupStoreV2
    private lateinit var scheduler: ScheduledExecutorService
    private val uplink = ConcurrentLinkedQueue<Map<String, Any?>>()
    private lateinit var dispatcher: ReceiptDispatcherV2
    private var now = 1_794_052_800_000L

    /** 假 RootShell：按命令前缀路由到可编程响应。 */
    private class FakeShell {
        data class Call(val command: String, val timeoutS: Int, val sessionId: String?)

        val calls = ConcurrentLinkedQueue<Call>()

        /** 命令前缀 → 响应（先匹配前缀长者优先）。 */
        val routes = java.util.concurrent.ConcurrentHashMap<String, AgentShell.Result>()

        /** 命令前缀 → 副作用（返回结果前执行；用于模拟执行中置位 estop）。 */
        val sideEffects = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

        var defaultResult: AgentShell.Result =
            AgentShell.Result(0, "ok", "", timedOut = false)

        fun route(prefix: String, result: AgentShell.Result) {
            routes[prefix] = result
        }

        fun toRunner(): Agent2Module.RootShell = Agent2Module.RootShell { cmd, t, s, _ ->
            calls.add(Call(cmd, t, s))
            sideEffects.entries.firstOrNull { cmd.startsWith(it.key) }?.value?.invoke()
            val hit = routes.entries.filter { cmd.startsWith(it.key) }.maxByOrNull { it.key.length }
            hit?.value ?: defaultResult
        }
    }

    private class FakeAccessibility : AccessibilityDeviceControl {
        var enabled = true
        var nextResult = AccessibilityDeviceControl.Result(
            AccessibilityDeviceControl.Status.SUCCEEDED, "模拟无障碍完成",
        )
        val calls = ConcurrentLinkedQueue<String>()
        override fun available() = enabled
        override fun dumpUiTree(): String? = null
        override fun foregroundPackage(): String? = null
        override fun listLaunchableApps(filter: String): List<String>? =
            listOf("com.example.launcher", "com.example.gallery")
        override fun execute(
            capability: String, args: Map<String, Any?>, point: Pair<Int, Int>?,
        ): AccessibilityDeviceControl.Result {
            calls.add(capability)
            return nextResult
        }
    }

    private lateinit var fakeAccessibility: FakeAccessibility
    private lateinit var fakeShell: FakeShell
    private var estop = false
    private var uiTree: String? = null
    private var shotFile: File? = null
    private var foreground: String? = null
    private var screenSize = Pair(1080, 2400)

    private fun newModule(
        rootEdition: Boolean = true,
        realClock: Boolean = false,
    ): Agent2Module = Agent2Module(
        context = context,
        shell = fakeShell.toRunner(),
        accessibility = fakeAccessibility,
        uiTreeSource = Agent2Module.UiTreeSource { uiTree },
        screenshotSource = Agent2Module.ScreenshotSource { shotFile },
        foregroundProbe = Agent2Module.ForegroundProbe { foreground },
        screenSize = { screenSize },
        estop = { estop },
        rootEdition = rootEdition,
        // 含时间轮询回路的用例（wait/app.launch 验证）须用真实时钟；其余用冻结时钟
        clock = if (realClock) System::currentTimeMillis else ({ now }),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        store = ActionDedupStoreV2(context, dbName)
        scheduler = Executors.newSingleThreadScheduledExecutor()
        uplink.clear()
        now = 1_794_052_800_000L
        fakeShell = FakeShell()
        fakeAccessibility = FakeAccessibility()
        // 默认 root 可用（ensureRoot 探测 `id` → uid=0）
        fakeShell.route("id", AgentShell.Result(0, "uid=0(root) gid=0(root)", "", false))
        estop = false
        uiTree = null
        shotFile = null
        foreground = null
        screenSize = Pair(1080, 2400)
        // estop 开关落 SharedPreferences（模块默认读 AgentModule.isEstop；测试注入覆盖）
        AgentModule.clearEstop(context)
        dispatcher = ReceiptDispatcherV2(
            store = store,
            sendUplink = { uplink.add(it) },
            scheduler = scheduler,
            clock = { now },
        ).apply {
            acceptedCapabilitiesProvider = { CapabilitiesV2.ALL }
            isPaused = { false }
            hasUiHandler = { false }
        }
    }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(5, TimeUnit.SECONDS)
        store.close()
        context.deleteDatabase(dbName)
    }

    // ---- 工具 ----

    private fun runOnScheduler(block: () -> Unit) {
        scheduler.submit(block).get(5, TimeUnit.SECONDS)
    }

    private fun request(
        capability: String,
        arguments: Map<String, Any?>,
        actionId: String = IdsV2.newActionId(),
        idemKey: String = "agent2:test:${Ulid.newUlid()}",
        observationId: String? = null,
    ): Map<String, Any?> {
        val p = LinkedHashMap<String, Any?>()
        p["action_id"] = actionId
        p["capability"] = capability
        p["arguments"] = arguments
        observationId?.let { p["observation_id"] = it }
        p["timeout_ms"] = 10_000L
        p["expires_at"] = now + 60_000L
        p["idempotency_key"] = idemKey
        p["authorized_scope"] = listOf(capability)
        return p
    }

    private fun handle(payload: Map<String, Any?>) = runOnScheduler {
        dispatcher.handleRequest(Ulid.newUlid(), payload)
    }

    /** 取回执帧（执行器在线程池异步回复，轮询等待）。 */
    private fun takeReceipt(): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + 8_000
        while (true) {
            uplink.poll()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("缺少回执帧")
            Thread.sleep(10)
        }
    }

    /** received + 终态两帧（终态返回）。 */
    private fun handleAndTerminal(payload: Map<String, Any?>): Map<String, Any?> {
        handle(payload)
        val received = takeReceipt()
        assertEquals("received", received["status"])
        return takeReceipt()
    }

    private val treeA = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node text="" resource-id="" class="android.widget.FrameLayout" content-desc=""
                clickable="false" scrollable="false" bounds="[0,0][1080,2400]">
            <node text="搜索" resource-id="com.x:id/search" class="android.widget.Button"
                  content-desc="搜索按钮" clickable="true" scrollable="false"
                  bounds="[100,200][300,280]"/>
          </node>
        </hierarchy>
    """.trimIndent()

    // ------------------------------------------------------------- 坐标类动作

    @Test
    fun `tap happy path received then succeeded`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 100L, "y" to 200L)))
        assertEquals("succeeded", t["status"])
        val cmd = fakeShell.calls.last().command
        assertEquals("input tap 100 200", cmd)
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        assertNotNull(result["summary"])
        // 坐标类动作端侧未做验证 → skipped（§7.3 禁止伪报 passed）
        @Suppress("UNCHECKED_CAST")
        val v = result["verification"] as Map<String, Any?>
        assertEquals("skipped", v["status"])
    }

    @Test
    fun `tap out of screen fails invalid message and never executes`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 5000L, "y" to 200L)))
        assertEquals("failed", t["status"])
        assertEquals("invalid_message", t["error_code"])
        assertTrue("越界坐标不得执行 shell", fakeShell.calls.isEmpty())
    }

    @Test
    fun `swipe end out of screen fails before execution`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.swipe", mapOf("x" to 1L, "y" to 2L, "x2" to 3L, "y2" to 9999L)),
        )
        assertEquals("failed", t["status"])
        assertEquals("invalid_message", t["error_code"])
        assertTrue(fakeShell.calls.isEmpty())
    }

    @Test
    fun `long press maps to in-place swipe`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.long_press", mapOf("x" to 10L, "y" to 20L, "duration_ms" to 1500L)),
        )
        assertEquals("succeeded", t["status"])
        assertEquals("input swipe 10 20 10 20 1500", fakeShell.calls.last().command)
    }

    @Test
    fun `key not in whitelist rejected`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.key", mapOf("key" to "HACK")))
        assertEquals("failed", t["status"])
        assertEquals("invalid_message", t["error_code"])
        assertTrue(fakeShell.calls.isEmpty())
    }

    @Test
    fun `input text with newline rejected`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.input_text", mapOf("text" to "a\nb")))
        assertEquals("failed", t["status"])
        assertEquals("invalid_message", t["error_code"])
    }

    // ------------------------------------------------------------- estop

    @Test
    fun `estop active fails tap with estop code before execution`() {
        estop = true
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 1L, "y" to 1L)))
        assertEquals("failed", t["status"])
        assertEquals("estop_active", t["error_code"])
        assertTrue("estop 激活时不得执行 shell", fakeShell.calls.isEmpty())
    }

    @Test
    fun `estop mid wait cancels the wait`() {
        newModule(realClock = true).registerTo(dispatcher)
        // wait 执行 3s；300ms 后置 estop
        Thread {
            Thread.sleep(300)
            estop = true
        }.start()
        val t = handleAndTerminal(request("device.wait", mapOf("duration_ms" to 3_000L)))
        assertEquals("cancelled", t["status"])
        assertEquals("estop_active", t["error_code"])
    }

    // ------------------------------------------------------------- §8 元素引用与 stale

    /** 先做一次 observe 写入观察缓存，返回 observation_id。 */
    private fun observeOnce(xml: String): String {
        uiTree = xml
        val t = handleAndTerminal(request("device.observe", emptyMap()))
        assertEquals("succeeded", t["status"])
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        val ref = result["output_ref"] as String
        return ref.removePrefix("agent:ui:")
    }

    @Test
    fun `observe succeeds with output ref and caches observation`() {
        val module = newModule()
        module.registerTo(dispatcher)
        val obsId = observeOnce(treeA)
        assertTrue(obsId.startsWith("obs_"))
        val obs = module.observations.get(obsId)
        assertNotNull(obs)
        assertEquals(2, obs!!.elements.size)
        // 观察负载落盘（§8 负载本体经 output_ref 拉取）
        val f = File(context.filesDir, "agent2/ui/$obsId.json")
        assertTrue(f.exists())
        val json = f.readText()
        assertTrue(json.contains("\"element_id\":2"))
        assertTrue(json.contains("\"fingerprint\""))
    }

    @Test
    fun `tap by element passes when fresh tree has exactly one stable match`() {
        val module = newModule()
        module.registerTo(dispatcher)
        val obsId = observeOnce(treeA)
        // fresh 树中文本变化（稳定键不含 text，仍应唯一匹配）
        uiTree = treeA.replace(">搜索<", ">搜索中<").replace("text=\"搜索\"", "text=\"搜索中\"")
        val t = handleAndTerminal(
            request(
                "device.tap", mapOf("element_id" to 2L),
                observationId = obsId,
            ),
        )
        assertEquals("succeeded", t["status"])
        // 落点 = fresh bounds 中心 (200,240)
        assertEquals("input tap 200 240", fakeShell.calls.last().command)
    }

    @Test
    fun `tap by element fails stale when observation id unknown`() {
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request(
                "device.tap", mapOf("element_id" to 2L),
                observationId = IdsV2.newObservationId(),
            ),
        )
        assertEquals("failed", t["status"])
        assertEquals("stale_observation", t["error_code"])
        assertTrue(fakeShell.calls.isEmpty())
    }

    @Test
    fun `tap by element fails stale when fresh tree has zero match`() {
        newModule().registerTo(dispatcher)
        val obsId = observeOnce(treeA)
        // fresh 树中目标元素消失
        uiTree = treeA.replace("com.x:id/search", "com.x:id/other")
        val t = handleAndTerminal(
            request("device.tap", mapOf("element_id" to 2L), observationId = obsId),
        )
        assertEquals("failed", t["status"])
        assertEquals("stale_observation", t["error_code"])
        assertTrue("stale 判定后不得沿用过期坐标执行", fakeShell.calls.isEmpty())
    }

    @Test
    fun `tap by element fails stale when fresh tree has two matches`() {
        newModule().registerTo(dispatcher)
        val obsId = observeOnce(treeA)
        // fresh 树中两个稳定键相同的节点（text 不同——稳定键不含 text）
        uiTree = treeA.replace(
            "</hierarchy>",
            "<node text=\"另一个\" resource-id=\"com.x:id/search\" class=\"android.widget.Button\"" +
                " content-desc=\"搜索按钮\" clickable=\"true\" bounds=\"[100,200][300,280]\"/></hierarchy>",
        )
        val t = handleAndTerminal(
            request("device.tap", mapOf("element_id" to 2L), observationId = obsId),
        )
        assertEquals("failed", t["status"])
        assertEquals("stale_observation", t["error_code"])
    }

    @Test
    fun `observe fails unavailable when ui tree source unavailable`() {
        newModule().registerTo(dispatcher)
        uiTree = null
        val t = handleAndTerminal(request("device.observe", emptyMap()))
        assertEquals("failed", t["status"])
        assertEquals("unavailable", t["error_code"])
    }

    // ------------------------------------------------------------- shell 族（root）

    @Test
    fun `shell success maps exit code and summary`() {
        fakeShell.route("echo hello", AgentShell.Result(0, "hello\n", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "echo hello")))
        assertEquals("succeeded", t["status"])
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        assertTrue((result["summary"] as String).contains("exit=0"))
    }

    @Test
    fun `shell non zero exit maps failed internal with provider ref`() {
        fakeShell.route("false", AgentShell.Result(1, "boom", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "false")))
        assertEquals("failed", t["status"])
        assertEquals("internal", t["error_code"])
        assertEquals("exit=1", t["provider_ref"])
    }

    @Test
    fun `shell timeout maps timeout terminal`() {
        fakeShell.route("sleep 99", AgentShell.Result(null, "partial", "", true))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.shell", mapOf("command" to "sleep 99", "timeout_s" to 5L)),
        )
        assertEquals("timeout", t["status"])
    }

    @Test
    fun `shell missing exit code maps unknown (never lie)`() {
        fakeShell.route("weird", AgentShell.Result(null, "", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "weird")))
        assertEquals("unknown", t["status"])
    }

    @Test
    fun `shell estop refusal maps cancelled`() {
        // 模拟 AgentShell 熔断：命令执行期间 estop 激活，返回 exitCode=null（结果不明）
        // 且模块侧再查 estop → true ⇒ cancelled/estop_active（而非 unknown）
        estop = false
        val module = newModule()
        module.registerTo(dispatcher)
        fakeShell.route("long", AgentShell.Result(null, "紧急停止生效", "", false))
        fakeShell.sideEffects["long"] = { estop = true }
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "long")))
        assertEquals("cancelled", t["status"])
        assertEquals("estop_active", t["error_code"])
    }

    @Test
    fun `shell big output gets output ref`() {
        val big = "x".repeat(5000)
        fakeShell.route("big", AgentShell.Result(0, big, "/tmp/once-1.log", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "big")))
        assertEquals("succeeded", t["status"])
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        assertEquals("agent:out:once-1.log", result["output_ref"])
        // summary 有界
        assertTrue((result["summary"] as String).length <= 2000)
    }

    // ------------------------------------------------------------- root 门控

    @Test
    fun `daily edition does not register shell handler (fail closed unavailable)`() {
        // rootEdition=false 模拟 daily 构建（测试任务跑在 daily 变体，
        // CapabilityRegistry.isRootEdition()=false；显式注入保证语义明确）。
        // 无 handler → 分发器 fail-closed 立即 failed/unavailable（§16 不悬挂 received，
        // 因此只有一帧终态，无 received）。
        newModule(rootEdition = false).registerTo(dispatcher)
        handle(request("device.shell", mapOf("command" to "id")))
        val t = takeReceipt()
        assertEquals("failed", t["status"])
        assertEquals("unavailable", t["error_code"])
        assertTrue("daily 构建不得执行 shell", fakeShell.calls.isEmpty())
    }

    @Test
    fun `daily edition does not register file and logcat handlers`() {
        newModule(rootEdition = false).registerTo(dispatcher)
        for ((cap, args) in listOf(
            "device.file.read" to mapOf<String, Any?>("path" to "/etc/hosts"),
            "device.file.write" to mapOf<String, Any?>(
                "path" to "/sdcard/a",
                "content_base64" to "aGk=",
            ),
            "device.file.list" to mapOf<String, Any?>("path" to "/sdcard"),
            "device.logcat" to emptyMap<String, Any?>(),
        )) {
            // 无 handler → 立即终态（无 received 帧）
            handle(request(cap, args))
            val t = takeReceipt()
            assertEquals("$cap 应为 failed", "failed", t["status"])
            assertEquals("$cap 应为 unavailable", "unavailable", t["error_code"])
        }
    }

    @Test
    fun `daily edition taps via accessibility without calling su`() {
        newModule(rootEdition = false).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 1L, "y" to 1L)))
        assertEquals("succeeded", t["status"])
        assertTrue(fakeShell.calls.isEmpty())
        assertEquals(listOf("device.tap"), fakeAccessibility.calls.toList())
        assertEquals("accessibility", t["provider_ref"])
    }

    @Test
    fun `daily edition returns unavailable when accessibility is disabled`() {
        fakeAccessibility.enabled = false
        newModule(rootEdition = false).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.swipe",
            mapOf("x" to 1L, "y" to 1L, "x2" to 2L, "y2" to 2L)))
        assertEquals("failed", t["status"])
        assertEquals("unavailable", t["error_code"])
        assertTrue(fakeShell.calls.isEmpty())
    }

    @Test
    fun `root edition keeps root as priority for common UI actions`() {
        newModule(rootEdition = true).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 4L, "y" to 9L)))
        assertEquals("succeeded", t["status"])
        assertTrue(fakeShell.calls.any { it.command == "input tap 4 9" })
        assertTrue(fakeAccessibility.calls.isEmpty())
    }

    @Test
    fun `root unavailable falls back to accessibility before dispatch`() {
        fakeShell.route("id", AgentShell.Result(1, "su unavailable", "", false))
        newModule(rootEdition = true).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 4L, "y" to 9L)))
        assertEquals("succeeded", t["status"])
        assertEquals("accessibility", t["provider_ref"])
        assertTrue(fakeShell.calls.none { it.command.startsWith("input tap") })
    }

    @Test
    fun `accessibility ambiguous result is unknown and not replayed on root`() {
        fakeShell.route("id", AgentShell.Result(1, "su unavailable", "", false))
        fakeAccessibility.nextResult = AccessibilityDeviceControl.Result(
            AccessibilityDeviceControl.Status.UNKNOWN, "dispatchGesture 超时",
        )
        newModule(rootEdition = true).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.tap", mapOf("x" to 5L, "y" to 5L)))
        assertEquals("unknown", t["status"])
        assertEquals("accessibility", t["provider_ref"])
        assertEquals(1, fakeAccessibility.calls.size)
        assertTrue(fakeShell.calls.none { it.command.startsWith("input tap") })
    }

    @Test
    fun `root unavailable fails shell with unavailable`() {
        fakeShell.route("id", AgentShell.Result(1, "su: not found", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(request("device.shell", mapOf("command" to "id")))
        assertEquals("failed", t["status"])
        assertEquals("unavailable", t["error_code"])
    }

    // ------------------------------------------------------------- file.write verification

    @Test
    fun `file write verifies size when stat matches`() {
        val content = android.util.Base64.encodeToString("abc".toByteArray(), android.util.Base64.NO_WRAP)
        fakeShell.route("stat -c %s", AgentShell.Result(0, "3\n", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.file.write", mapOf("path" to "/sdcard/a.txt", "content_base64" to content)),
        )
        assertEquals("succeeded", t["status"])
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val v = result["verification"] as Map<String, Any?>
        assertEquals("passed", v["status"])
    }

    @Test
    fun `file write verification failed when size mismatches`() {
        val content = android.util.Base64.encodeToString("abc".toByteArray(), android.util.Base64.NO_WRAP)
        fakeShell.route("stat -c %s", AgentShell.Result(0, "99\n", "", false))
        newModule().registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.file.write", mapOf("path" to "/sdcard/a.txt", "content_base64" to content)),
        )
        assertEquals("succeeded", t["status"]) // 动作本身执行成功，验证失败诚实上报
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val v = result["verification"] as Map<String, Any?>
        assertEquals("failed", v["status"])
    }

    // ------------------------------------------------------------- app.launch verification

    @Test
    fun `app launch verification passed when foreground matches`() {
        foreground = "com.example.app"
        newModule(realClock = true).registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.app.launch", mapOf("package" to "com.example.app")),
        )
        assertEquals("succeeded", t["status"])
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val v = result["verification"] as Map<String, Any?>
        assertEquals("passed", v["status"])
        assertTrue(fakeShell.calls.any { it.command.contains("am start") })
    }

    @Test
    fun `app launch verification failed on different foreground`() {
        foreground = "com.other.app"
        newModule(realClock = true).registerTo(dispatcher)
        val t = handleAndTerminal(
            request("device.app.launch", mapOf("package" to "com.example.app")),
        )
        @Suppress("UNCHECKED_CAST")
        val result = t["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val v = result["verification"] as Map<String, Any?>
        assertEquals("failed", v["status"])
    }

    // ------------------------------------------------------------- wait / 幂等经由分发器

    @Test
    fun `wait happy path`() {
        newModule(realClock = true).registerTo(dispatcher)
        val t = handleAndTerminal(request("device.wait", mapOf("duration_ms" to 150L)))
        assertEquals("succeeded", t["status"])
    }

    @Test
    fun `idempotent replay does not re-execute tap`() {
        newModule().registerTo(dispatcher)
        val key = "agent2:test:idem"
        val t1 = handleAndTerminal(request("device.tap", mapOf("x" to 1L, "y" to 1L), idemKey = key))
        assertEquals("succeeded", t1["status"])
        val executions = fakeShell.calls.size
        // 同幂等键重发（新 action_id）→ 回放已记录终态，不重复执行（§7.1）
        handle(request("device.tap", mapOf("x" to 2L, "y" to 2L), idemKey = key))
        val replay = takeReceipt()
        assertEquals("succeeded", replay["status"])
        assertEquals(executions, fakeShell.calls.size)
    }
}
