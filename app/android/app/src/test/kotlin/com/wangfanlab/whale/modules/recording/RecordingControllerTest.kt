package com.wangfanlab.whale.modules.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.capture.ExcludedPackagesStore
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * RecordingController 录制状态机单测（contracts/v2 §12.2）。
 *
 * 覆盖：control payload 解析（run_id/op/options 值域）、start/pause/resume/stop
 * 状态迁移与 recording.state 回报组包、幂等重报、异 run 并发拒绝、未知 run 拒绝、
 * max_duration 自动停止、遮罩触发（排除包名 ∪ 本地 ExcludedPackagesStore）与
 * 时长口径（暂停/遮罩区间不计入）、device.screen.record action 适配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingControllerTest {

    private lateinit var context: Context

    /** 假驱动：记录调用序列与遮罩状态，可编程错误码。 */
    private class FakeDriver : RecordingController.RecordingDriver {
        val calls = ConcurrentLinkedQueue<String>()

        @Volatile var maskedFlag: Boolean = false

        /** 非 null 时 start/pause/resume/stop 返回该 errorCode。 */
        @Volatile var failWith: String? = null

        override fun start(runId: String, options: RecordingController.Options): String? {
            calls.add("start:$runId:${options.excludePackages.sorted()}")
            return failWith("start")
        }

        override fun pause(): String? {
            calls.add("pause")
            return failWith("pause")
        }

        override fun resume(): String? {
            calls.add("resume")
            return failWith("resume")
        }

        override fun stop(): String? {
            calls.add("stop")
            return failWith("stop")
        }

        override fun setMasked(masked: Boolean) {
            this.maskedFlag = masked
            calls.add("mask:$masked")
        }

        private fun failWith(op: String): String? = failWith?.takeIf {
            when (op) {
                "start" -> failOnStart
                "pause" -> failOnPause
                "resume" -> failOnResume
                "stop" -> failOnStop
                else -> false
            }
        }

        @Volatile var failOnStart = true
        @Volatile var failOnPause = true
        @Volatile var failOnResume = true
        @Volatile var failOnStop = true
    }

    private class FakeSender : RecordingController.StateSender {
        val reports = ConcurrentLinkedQueue<RecordingController.StateReport>()
        override fun send(
            runId: String, state: String, fileRef: String?, durationMs: Long?, errorCode: String?,
        ) {
            reports.add(RecordingController.StateReport(runId, state, fileRef, durationMs, errorCode))
        }
    }

    private lateinit var driver: FakeDriver
    private lateinit var sender: FakeSender
    private var now = 1_794_052_800_000L
    private var localExcluded = emptySet<String>()

    private fun newController() = RecordingController(
        driver = driver,
        stateSender = sender,
        localExcludedProvider = { localExcluded },
        clock = { now },
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        driver = FakeDriver()
        sender = FakeSender()
        now = 1_794_052_800_000L
        localExcluded = emptySet()
        // 清本地排除包名（Robolectric 跨测试共享 prefs）
        ExcludedPackagesStore(context).set(emptySet())
    }

    @After
    fun tearDown() {
        ExcludedPackagesStore(context).set(emptySet())
    }

    private fun req(
        op: String,
        runId: String = IdsV2.newRunId(),
        options: RecordingController.Options = RecordingController.Options(null, null, emptySet()),
    ) = RecordingController.ControlRequest(runId, op, options)

    // ------------------------------------------------------------- payload 解析

    @Test
    fun `parse control accepts valid start with options`() {
        val runId = IdsV2.newRunId()
        val c = newController()
        val req = c.parseControl(
            mapOf(
                "run_id" to runId, "op" to "start",
                "options" to mapOf(
                    "profile" to "demo",
                    "max_duration_ms" to 600_000L,
                    "exclude_packages" to listOf("com.example.bank"),
                ),
            ),
        )
        assertNotNull(req)
        assertEquals(runId, req!!.runId)
        assertEquals("start", req.op)
        assertEquals(setOf("com.example.bank"), req.options.excludePackages)
        assertEquals(600_000L, req.options.maxDurationMs)
    }

    @Test
    fun `parse control rejects bad run id and op`() {
        val c = newController()
        assertNull(c.parseControl(mapOf("run_id" to "not-a-run", "op" to "start")))
        assertNull(c.parseControl(mapOf("run_id" to IdsV2.newRunId(), "op" to "explode")))
        assertNull(c.parseControl(mapOf("op" to "start")))
        assertNull(c.parseControl(mapOf("run_id" to IdsV2.newRunId())))
    }

    @Test
    fun `parse control rejects out of range options`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        // max_duration_ms 越界
        assertNull(c.parseControl(mapOf("run_id" to runId, "op" to "start",
            "options" to mapOf("max_duration_ms" to 500L))))
        assertNull(c.parseControl(mapOf("run_id" to runId, "op" to "start",
            "options" to mapOf("max_duration_ms" to 3_700_000L))))
        // exclude_packages 超 32 项
        assertNull(c.parseControl(mapOf("run_id" to runId, "op" to "start",
            "options" to mapOf("exclude_packages" to (1..33).map { "com.x$it" }))))
        // profile 超长
        assertNull(c.parseControl(mapOf("run_id" to runId, "op" to "start",
            "options" to mapOf("profile" to "x".repeat(33)))))
    }

    // ------------------------------------------------------------- 状态机

    @Test
    fun `start emits recording state with file ref`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        val report = c.handle(req("start", runId))
        assertEquals("recording", report.state)
        assertEquals("recording:$runId/video", report.fileRef)
        assertEquals(0L, report.durationMs)
        // recording.state 组包键集（schema 对齐）
        val payload = report.toPayload()
        assertEquals(runId, payload["run_id"])
        assertEquals("recording", payload["state"])
        assertEquals("recording:$runId/video", payload["file_ref"])
        assertTrue(payload.containsKey("duration_ms"))
        assertFalse(payload.containsKey("error_code"))
        // sender 收到同一报告
        assertEquals(1, sender.reports.size)
    }

    @Test
    fun `pause resume stop transitions emit correct states`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId))
        now += 2_000
        assertEquals("paused", c.handle(req("pause", runId)).state)
        now += 5_000 // 暂停区间不计入时长
        val resumed = c.handle(req("resume", runId))
        assertEquals("recording", resumed.state)
        assertEquals(2_000L, resumed.durationMs)
        now += 3_000
        val stopped = c.handle(req("stop", runId))
        assertEquals("stopped", stopped.state)
        assertEquals("recording:$runId/video", stopped.fileRef)
        assertEquals(5_000L, stopped.durationMs) // 2000 + 3000，暂停的 5000 不计
        assertEquals(listOf("recording", "paused", "recording", "stopped"),
            sender.reports.map { it.state })
    }

    @Test
    fun `duplicate start of same run is idempotent re-report`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId))
        now += 1_000
        val again = c.handle(req("start", runId))
        assertEquals("recording", again.state)
        // 只起录一次
        assertEquals(1, driver.calls.count { it.startsWith("start:") })
    }

    @Test
    fun `start while another run active is rejected unavailable`() {
        val c = newController()
        val runA = IdsV2.newRunId()
        val runB = IdsV2.newRunId()
        c.handle(req("start", runA))
        val report = c.handle(req("start", runB))
        assertEquals("failed", report.state)
        assertEquals("unavailable", report.errorCode)
        assertEquals(runB, report.runId)
        // 旧 run 不受影响
        assertEquals(runA, c.activeRunId())
    }

    @Test
    fun `pause resume stop on unknown run fail unavailable`() {
        val c = newController()
        for (op in listOf("pause", "resume", "stop")) {
            val report = c.handle(req(op, IdsV2.newRunId()))
            assertEquals("$op 未知 run 应 failed", "failed", report.state)
            assertEquals("unavailable", report.errorCode)
        }
    }

    @Test
    fun `driver start failure maps failed with its error code`() {
        driver.failWith = "permission_missing"
        val c = newController()
        val report = c.handle(req("start"))
        assertEquals("failed", report.state)
        assertEquals("permission_missing", report.errorCode)
        assertNull(c.activeRunId())
    }

    @Test
    fun `pause failure fails the recording`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId))
        driver.failWith = "internal"
        val report = c.handle(req("pause", runId))
        assertEquals("failed", report.state)
        assertEquals("internal", report.errorCode)
        assertNull(c.activeRunId())
        // 失败时驱动已兜底停止（会话清理）
        assertTrue(driver.calls.contains("stop"))
    }

    @Test
    fun `max duration auto stops on tick`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId, RecordingController.Options(null, 10_000L, emptySet())))
        now += 10_001
        val report = c.tick()
        assertNotNull(report)
        assertEquals("stopped", report!!.state)
        assertEquals(runId, report.runId)
        assertTrue(sender.reports.any { it.state == "stopped" })
    }

    // ------------------------------------------------------------- 遮罩

    @Test
    fun `mask triggers on excluded foreground package and excludes masked time`() {
        localExcluded = setOf("com.example.bank")
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId, RecordingController.Options(null, null, setOf("com.example.pay"))))
        now += 1_000
        // 命中 options 排除
        assertTrue(c.onForegroundPackage("com.example.pay"))
        assertTrue(driver.maskedFlag)
        now += 2_000 // 遮罩区间
        // 切到本地排除包名（仍遮罩，无抖动）
        assertTrue(c.onForegroundPackage("com.example.bank"))
        now += 1_000
        // 离开排除集合 → 解除遮罩
        assertFalse(c.onForegroundPackage("com.example.home"))
        assertFalse(driver.maskedFlag)
        now += 1_000
        val stopped = c.handle(req("stop", runId))
        // 总 wall 5s，遮罩 3s 不计 → 2s
        assertEquals(2_000L, stopped.durationMs)
        assertTrue(driver.calls.contains("mask:true"))
        assertTrue(driver.calls.contains("mask:false"))
    }

    @Test
    fun `mask not triggered for unrelated package`() {
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId, RecordingController.Options(null, null, setOf("com.example.bank"))))
        assertFalse(c.onForegroundPackage("com.example.game"))
        assertFalse(c.isMasked())
        assertTrue(driver.calls.none { it.startsWith("mask:") })
    }

    @Test
    fun `mask merges local excluded packages store`() {
        // 本地 store（capture 模块排除包名）在 start 时并入
        ExcludedPackagesStore(context).add("com.local.excluded")
        localExcluded = ExcludedPackagesStore(context).get()
        val c = newController()
        val runId = IdsV2.newRunId()
        c.handle(req("start", runId))
        assertTrue(c.onForegroundPackage("com.local.excluded"))
    }

    // ------------------------------------------------------------- device.screen.record action 适配

    private val dbName = "test_recording_dedup.db"
    private lateinit var store: ActionDedupStoreV2
    private lateinit var scheduler: ScheduledExecutorService
    private val uplink = ConcurrentLinkedQueue<Map<String, Any?>>()

    private fun newDispatcher(): ReceiptDispatcherV2 {
        context.deleteDatabase(dbName)
        store = ActionDedupStoreV2(context, dbName)
        scheduler = Executors.newSingleThreadScheduledExecutor()
        uplink.clear()
        return ReceiptDispatcherV2(
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

    private fun takeReceipt(): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            uplink.poll()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("缺少回执帧")
            Thread.sleep(10)
        }
    }

    @Test
    fun `screen record action start succeeds and emits recording state`() {
        val c = newController()
        val dispatcher = newDispatcher()
        dispatcher.registerHandler(
            CapabilitiesV2.DEVICE_SCREEN_RECORD, c.asActionHandler(),
        )
        val runId = IdsV2.newRunId()
        val p = LinkedHashMap<String, Any?>()
        p["action_id"] = IdsV2.newActionId()
        p["capability"] = "device.screen.record"
        p["arguments"] = mapOf("op" to "start")
        p["run_id"] = runId
        p["timeout_ms"] = 10_000L
        p["expires_at"] = now + 60_000L
        p["idempotency_key"] = "agent:rec:${Ulid.newUlid()}"
        p["authorized_scope"] = listOf("device.screen.record")
        scheduler.submit { dispatcher.handleRequest(Ulid.newUlid(), p) }.get(5, TimeUnit.SECONDS)
        assertEquals("received", takeReceipt()["status"])
        val terminal = takeReceipt()
        assertEquals("succeeded", terminal["status"])
        @Suppress("UNCHECKED_CAST")
        val result = terminal["result"] as Map<String, Any?>
        assertEquals("recording:$runId/video", result["output_ref"])
        // 异步 recording.state 已回报
        assertTrue(sender.reports.any { it.state == "recording" && it.runId == runId })
        scheduler.shutdownNow()
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `screen record action with bad op fails invalid message`() {
        val c = newController()
        val dispatcher = newDispatcher()
        dispatcher.registerHandler(
            CapabilitiesV2.DEVICE_SCREEN_RECORD, c.asActionHandler(),
        )
        val p = LinkedHashMap<String, Any?>()
        p["action_id"] = IdsV2.newActionId()
        p["capability"] = "device.screen.record"
        p["arguments"] = mapOf("op" to "explode")
        p["timeout_ms"] = 10_000L
        p["expires_at"] = now + 60_000L
        p["idempotency_key"] = "agent:rec:${Ulid.newUlid()}"
        p["authorized_scope"] = listOf("device.screen.record")
        scheduler.submit { dispatcher.handleRequest(Ulid.newUlid(), p) }.get(5, TimeUnit.SECONDS)
        assertEquals("received", takeReceipt()["status"])
        val terminal = takeReceipt()
        assertEquals("failed", terminal["status"])
        assertEquals("invalid_message", terminal["error_code"])
        scheduler.shutdownNow()
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `screen record action stop without active run fails unavailable`() {
        val c = newController()
        val dispatcher = newDispatcher()
        dispatcher.registerHandler(
            CapabilitiesV2.DEVICE_SCREEN_RECORD, c.asActionHandler(),
        )
        val p = LinkedHashMap<String, Any?>()
        p["action_id"] = IdsV2.newActionId()
        p["capability"] = "device.screen.record"
        p["arguments"] = mapOf("op" to "stop", "run_id" to IdsV2.newRunId())
        p["timeout_ms"] = 10_000L
        p["expires_at"] = now + 60_000L
        p["idempotency_key"] = "agent:rec:${Ulid.newUlid()}"
        p["authorized_scope"] = listOf("device.screen.record")
        scheduler.submit { dispatcher.handleRequest(Ulid.newUlid(), p) }.get(5, TimeUnit.SECONDS)
        assertEquals("received", takeReceipt()["status"])
        val terminal = takeReceipt()
        assertEquals("failed", terminal["status"])
        assertEquals("unavailable", terminal["error_code"])
        scheduler.shutdownNow()
        store.close()
        context.deleteDatabase(dbName)
    }
}
