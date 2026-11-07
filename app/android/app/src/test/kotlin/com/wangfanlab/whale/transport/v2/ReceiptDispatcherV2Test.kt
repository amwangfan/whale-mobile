package com.wangfanlab.whale.transport.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * action.receipt 状态机与下行幂等（contracts/v2 §7/§16）单元测试。
 * Robolectric 提供真实 SQLite；分发器共用真实单线程调度器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptDispatcherV2Test {

    private lateinit var context: Context
    private val dbName = "test_dedup_v2.db"
    private lateinit var store: ActionDedupStoreV2
    private lateinit var scheduler: ScheduledExecutorService
    private val uplink = ConcurrentLinkedQueue<Map<String, Any?>>()
    private val protocolErrors = ConcurrentLinkedQueue<Triple<String, String, String>>()
    private lateinit var dispatcher: ReceiptDispatcherV2
    private var now = 1_794_052_800_000L
    private var paused = false
    private var uiHandler = false
    private var accepted: Set<String> = CapabilitiesV2.ALL

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        store = ActionDedupStoreV2(context, dbName)
        scheduler = Executors.newSingleThreadScheduledExecutor()
        uplink.clear()
        protocolErrors.clear()
        now = 1_794_052_800_000L
        paused = false
        uiHandler = false
        accepted = CapabilitiesV2.ALL
        dispatcher = newDispatcher()
    }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS))
        store.close()
        context.deleteDatabase(dbName)
    }

    private fun newDispatcher(): ReceiptDispatcherV2 = ReceiptDispatcherV2(
        store = store,
        sendUplink = { uplink.add(it) },
        scheduler = scheduler,
        clock = { now },
    ).apply {
        acceptedCapabilitiesProvider = { accepted }
        isPaused = { paused }
        hasUiHandler = { uiHandler }
        sendProtocolError = { ref, code, msg -> protocolErrors.add(Triple(ref, code, msg)) }
    }

    // ---- 工具 ----

    private fun runOnScheduler(block: () -> Unit) {
        scheduler.submit(block).get(5, TimeUnit.SECONDS)
    }

    private fun request(
        actionId: String = IdsV2.newActionId(),
        capability: String = "device.tap",
        idemKey: String = "agent:run_x:step1",
        timeoutMs: Long = 10_000L,
        expiresAt: Long = now + 60_000L,
        scope: List<String> = listOf("device.tap"),
        arguments: Map<String, Any?> = mapOf("x" to 100L, "y" to 200L),
        observationId: String? = null,
    ): MutableMap<String, Any?> { // MutableMap：调用点会 remove/set 字段（notification2 任务代修，并行任务可复核）
        val p = LinkedHashMap<String, Any?>()
        p["action_id"] = actionId
        p["capability"] = capability
        p["arguments"] = arguments
        observationId?.let { p["observation_id"] = it }
        p["timeout_ms"] = timeoutMs
        p["expires_at"] = expiresAt
        p["idempotency_key"] = idemKey
        p["authorized_scope"] = scope
        return p
    }

    private fun handle(payload: Map<String, Any?>) = runOnScheduler {
        dispatcher.handleRequest(Ulid.newUlid(), payload)
    }

    /** 取回执帧（带超时轮询：执行器回复经 scheduler marshal，存在异步一跳）。 */
    private fun takeReceipt(): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            uplink.poll()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("缺少回执帧")
            Thread.sleep(10)
        }
    }

    /** 排空 scheduler 上已排队的任务（marshal 一跳 + 本任务一跳，两跳保证兑现）。 */
    private fun drainScheduler() {
        runOnScheduler {}
        runOnScheduler {}
    }

    private fun assertNoMoreReceipts() {
        assertTrue("不应再有回执: $uplink", uplink.isEmpty())
    }

    // ---- 用例 ----

    @Test
    fun `happy path received then succeeded with terminal persisted`() {
        val executions = AtomicInteger(0)
        dispatcher.registerHandler("device.tap") { req, reply ->
            executions.incrementAndGet()
            reply(
                ReceiptDispatcherV2.ActionOutcome(
                    ReceiptStatus.SUCCEEDED,
                    resultSummary = "已点击搜索按钮",
                    verificationStatus = "passed", verificationSummary = "搜索页已打开",
                )
            )
        }
        handle(request())
        val received = takeReceipt()
        assertEquals("received", received["status"])
        val terminal = takeReceipt()
        assertEquals("succeeded", terminal["status"])
        @Suppress("UNCHECKED_CAST")
        val result = terminal["result"] as Map<String, Any?>
        assertEquals("已点击搜索按钮", result["summary"])
        @Suppress("UNCHECKED_CAST")
        val verification = result["verification"] as Map<String, Any?>
        assertEquals("passed", verification["status"])
        assertNotNull(terminal["completed_at"])
        assertNotNull(terminal["started_at"])
        assertEquals(1, executions.get())
        // 终态已落库
        val record = store.get("agent:run_x:step1")
        assertEquals(ReceiptStatus.SUCCEEDED, record?.terminalState)
        assertNoMoreReceipts()
    }

    @Test
    fun `first terminal wins - late reply after terminal is dropped`() {
        lateinit var savedReply: (ReceiptDispatcherV2.ActionOutcome) -> Unit
        dispatcher.registerHandler("device.tap") { _, reply -> savedReply = reply }
        handle(request())
        assertEquals("received", takeReceipt()["status"])
        runOnScheduler {
            savedReply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED, resultSummary = "第一次"))
            savedReply( // 重复回复（实现 bug 兜底）：必须被丢弃
                ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.FAILED, ErrorCodeV2.INTERNAL),
            )
        }
        assertEquals("succeeded", takeReceipt()["status"])
        drainScheduler() // 让第二跳 settle 落地后再断言无新帧
        assertNoMoreReceipts()
        assertEquals(ReceiptStatus.SUCCEEDED, store.get("agent:run_x:step1")?.terminalState)
    }

    @Test
    fun `duplicate idempotency key in flight replays received without re executing`() {
        val executions = AtomicInteger(0)
        val gate = CountDownLatch(1)
        dispatcher.registerHandler("device.tap") { _, reply ->
            executions.incrementAndGet()
            Thread { gate.await(5, TimeUnit.SECONDS); reply(
                ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED, resultSummary = "done"),
            ) }.start()
        }
        val key = "agent:run_x:dup1"
        handle(request(actionId = IdsV2.newActionId(), idemKey = key))
        assertEquals("received", takeReceipt()["status"])
        // 在飞期间同键重发（新 action_id）→ 重发 received，绝不重复执行
        handle(request(actionId = IdsV2.newActionId(), idemKey = key))
        assertEquals("received", takeReceipt()["status"])
        assertEquals(1, executions.get())
        gate.countDown()
        val terminal = takeReceipt() // 轮询等待后台线程 reply 兑现
        assertEquals("succeeded", terminal["status"])
        assertEquals(1, executions.get())
    }

    @Test
    fun `duplicate idempotency key after terminal replays recorded receipt with recorded action id`() {
        dispatcher.registerHandler("device.tap") { _, reply ->
            reply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED, resultSummary = "已点击"))
        }
        val key = "agent:run_x:dup2"
        val firstActionId = IdsV2.newActionId()
        handle(request(actionId = firstActionId, idemKey = key))
        takeReceipt() // received
        val terminal = takeReceipt()
        assertEquals("succeeded", terminal["status"])
        // 终结后同键重发（新 action_id）→ 回放已记录 action_id 的真实终态（§7.1）
        handle(request(actionId = IdsV2.newActionId(), idemKey = key))
        val replay = takeReceipt()
        assertEquals(firstActionId, replay["action_id"])
        assertEquals("succeeded", replay["status"])
        assertNoMoreReceipts()
    }

    @Test
    fun `cross restart unfinished record settles unknown and never replays`() {
        // 模拟跨重启遗留：记录存在但无终态（进程死在执行中）。
        val key = "agent:run_x:restart"
        val oldActionId = IdsV2.newActionId()
        assertTrue(store.recordIfAbsent(key, oldActionId, null, "device.tap"))
        // 新分发器（inFlight 为空，模拟重启）再次收到同键请求
        val fresh = newDispatcher()
        fresh.registerHandler("device.tap") { _, _ ->
            throw AssertionError("跨重启未终结动作绝不重放副作用")
        }
        scheduler.submit {
            fresh.handleRequest(Ulid.newUlid(), request(actionId = IdsV2.newActionId(), idemKey = key))
        }.get(5, TimeUnit.SECONDS)
        val receipt = takeReceipt()
        assertEquals(oldActionId, receipt["action_id"]) // recorded action_id
        assertEquals("unknown", receipt["status"])
        // unknown 落库后同键再重发 → 回放 unknown
        scheduler.submit {
            fresh.handleRequest(Ulid.newUlid(), request(actionId = IdsV2.newActionId(), idemKey = key))
        }.get(5, TimeUnit.SECONDS)
        assertEquals("unknown", takeReceipt()["status"])
        assertNoMoreReceipts()
    }

    @Test
    fun `no handler and no ui fails immediately with unavailable`() {
        uiHandler = false
        handle(request())
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("unavailable", receipt["error_code"])
        assertNoMoreReceipts()
    }

    @Test
    fun `expired action fails with expired and is not executed`() {
        val executions = AtomicInteger(0)
        dispatcher.registerHandler("device.tap") { _, reply ->
            executions.incrementAndGet()
            reply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED))
        }
        handle(request(expiresAt = now)) // 已到点（now >= expires_at）
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("expired", receipt["error_code"])
        assertEquals(0, executions.get())
    }

    @Test
    fun `capability outside action subset is rejected as capability unknown`() {
        // device.output.read 仅允许 sensor.query（§5.3），不得作为 action 执行
        handle(request(capability = "device.output.read", scope = listOf("device.output.read")))
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("capability_unknown", receipt["error_code"])
    }

    @Test
    fun `capability outside authorized scope is denied`() {
        dispatcher.registerHandler("device.tap") { _, reply ->
            reply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED))
        }
        handle(request(scope = listOf("device.swipe"))) // scope 不含 device.tap
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("scope_denied", receipt["error_code"])
    }

    @Test
    fun `capability narrowed by hello ack is denied`() {
        accepted = setOf("device.swipe") // core 收窄后不含 device.tap
        dispatcher.registerHandler("device.tap") { _, reply ->
            reply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED))
        }
        handle(request())
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("scope_denied", receipt["error_code"])
    }

    @Test
    fun `paused device rejects with unavailable`() {
        paused = true
        handle(request())
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("unavailable", receipt["error_code"])
    }

    @Test
    fun `timeout produces timeout terminal and late result is dropped`() {
        lateinit var savedReply: (ReceiptDispatcherV2.ActionOutcome) -> Unit
        dispatcher.registerHandler("device.tap") { _, reply -> savedReply = reply }
        handle(request(timeoutMs = 100L)) // schema 下限 100ms
        assertEquals("received", takeReceipt()["status"])
        // 等待超时兜底触发
        val deadline = System.currentTimeMillis() + 5000
        var terminal: Map<String, Any?>? = null
        while (System.currentTimeMillis() < deadline) {
            terminal = uplink.poll()
            if (terminal != null) break
            Thread.sleep(20)
        }
        assertNotNull("timeout 终态应被发出", terminal)
        assertEquals("timeout", terminal!!["status"])
        assertEquals(ReceiptStatus.TIMEOUT, store.get("agent:run_x:step1")?.terminalState)
        // 迟到的执行结果被丢弃（first terminal wins，§7.2 超时不等于未执行）
        runOnScheduler {
            savedReply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED, resultSummary = "迟到"))
        }
        assertNoMoreReceipts()
    }

    @Test
    fun `ui delegated action completes via business entry`() {
        uiHandler = true
        var dispatched: Map<String, Any?>? = null
        dispatcher.uiDispatch = { dispatched = it }
        val actionId = IdsV2.newActionId()
        handle(request(actionId = actionId)) // 无原生执行器 → received + UI 委托
        assertEquals("received", takeReceipt()["status"])
        assertNotNull(dispatched)
        // 业务层回终态
        dispatcher.completeFromBusiness(
            actionId, ReceiptStatus.SUCCEEDED, resultSummary = "UI 完成",
        )
        runOnScheduler {}
        assertEquals("succeeded", takeReceipt()["status"])
        // first-wins：业务层重复回执被丢弃
        dispatcher.completeFromBusiness(actionId, ReceiptStatus.FAILED, ErrorCodeV2.INTERNAL)
        runOnScheduler {}
        assertNoMoreReceipts()
    }

    @Test
    fun `malformed request with valid action id fails with invalid message`() {
        val p = request()
        p.remove("timeout_ms") // 缺必填
        handle(p)
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("invalid_message", receipt["error_code"])
    }

    @Test
    fun `malformed request without action id yields protocol error`() {
        val p = request()
        p["action_id"] = "not-an-action-id"
        handle(p)
        val err = protocolErrors.poll()
        assertNotNull(err)
        assertEquals("invalid_message", err!!.second)
        assertTrue(uplink.isEmpty())
    }

    @Test
    fun `element id reference without observation id is rejected`() {
        handle(request(arguments = mapOf("element_id" to 23L), observationId = null))
        val receipt = takeReceipt()
        assertEquals("failed", receipt["status"])
        assertEquals("invalid_message", receipt["error_code"])
    }

    @Test
    fun `task id is echoed in receipts when present`() {
        dispatcher.registerHandler("device.tap") { _, reply ->
            reply(ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.SUCCEEDED))
        }
        val taskId = IdsV2.newTaskId()
        val p = request().also { it["task_id"] = taskId; it["run_id"] = IdsV2.newRunId() }
        handle(p)
        assertEquals(taskId, takeReceipt()["task_id"])
        assertEquals(taskId, takeReceipt()["task_id"])
    }

    @Test
    fun `dedup store survives recreation across restarts`() {
        val key = "agent:run_x:persist"
        val actionId = IdsV2.newActionId()
        assertTrue(store.recordIfAbsent(key, actionId, null, "device.tap"))
        assertFalse(store.recordIfAbsent(key, IdsV2.newActionId(), null, "device.tap"))
        store.close()
        store = ActionDedupStoreV2(context, dbName) // 重开同一 DB（模拟重启）
        val record = store.get(key)
        assertNotNull(record)
        assertEquals(actionId, record!!.actionId)
        assertEquals(null, record.terminalState)
    }

    @Test
    fun `failed terminal requires error code`() {
        try {
            ReceiptDispatcherV2.ActionOutcome(ReceiptStatus.FAILED)
            throw AssertionError("failed 无 error_code 应抛异常")
        } catch (_: IllegalArgumentException) {
        }
    }
}
