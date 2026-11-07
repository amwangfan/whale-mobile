package com.wangfanlab.whale.runtime.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.glasses.GlassesModule
import com.wangfanlab.whale.modules.notification2.NotificationInbox
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.transport.DeviceIdentity
import com.wangfanlab.whale.transport.v2.DIR_DOWNLINK
import com.wangfanlab.whale.transport.v2.EnvelopeV2
import com.wangfanlab.whale.transport.v2.IdsV2
import com.wangfanlab.whale.transport.v2.MsgTypeV2
import com.wangfanlab.whale.transport.v2.Ulid
import com.wangfanlab.whale.transport.v2.WssClientV2
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * NodeRuntimeV2 组装测试（Robolectric，fake WssClientV2 socket 注入帧）：
 * - 单例纪律（进程唯一、get 幂等）；
 * - v1 执行器经适配层注册进 ReceiptDispatcherV2（glasses.card.show 全链回执）；
 * - 幂等回放 / 全局暂停 unavailable；
 * - notification.event 落库 + 回执上行；
 * - sensor.query：builtin 数据源原生应答 / device.output.read 路由；
 * - paused 清除 drop_on_pause 事件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NodeRuntimeV2Test {

    private lateinit var context: Context
    private lateinit var runtime: NodeRuntimeV2
    private val frames = LinkedBlockingQueue<EnvelopeV2>()
    private var socketClosed = false

    private val challenge = "b".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NodeRuntimeV2.resetForTesting()
        // 运行时使用默认 DB 名：每个用例前清库，保证互不影响
        context.deleteDatabase("whale_transport_v2.db")
        context.deleteDatabase("whale_action_dedup_v2.db")
        context.deleteDatabase("whale_notifications.db")
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("whale_capture", Context.MODE_PRIVATE)
            .edit().clear().apply()
        frames.clear()
        socketClosed = false
        runtime = NodeRuntimeV2.installForTesting(
            context,
            DeviceIdentity(ByteArray(32) { (it + 1).toByte() }),
        )
        runtime.start()
        injectSocketAndHandshake()
    }

    @After
    fun tearDown() {
        drainMain()
        runtime.clientForTest().destroy()
        assertTrue(
            "v2 executor 应及时终止",
            runtime.clientForTest().executorForTest().awaitTermination(10, TimeUnit.SECONDS),
        )
        NodeRuntimeV2.resetForTesting()
        com.wangfanlab.whale.modules.notification2.NotificationActionReceiver.unwire()
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("whale_capture", Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.deleteDatabase("whale_transport_v2.db")
        context.deleteDatabase("whale_action_dedup_v2.db")
        context.deleteDatabase("whale_notifications.db")
    }

    // ------------------------------------------------------------- 装配辅助

    /** 注入代理 socket 并完成 challenge/hello_ack 握手到 ONLINE。 */
    private fun injectSocketAndHandshake(
        accepted: List<String> = listOf(
            "glasses.card.show", "glasses.card.update", "glasses.card.dismiss",
            "phone.notification.post", "phone.device.safe_action", "phone.call.ring",
        ),
    ) {
        val client = runtime.clientForTest()
        val socket = Proxy.newProxyInstance(
            WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java),
        ) { _, method, args ->
            when (method.name) {
                "send" -> {
                    frames.add(com.wangfanlab.whale.transport.v2.EnvelopeCodecV2.decode(args!![0] as String))
                    true
                }
                "close" -> { socketClosed = true; true }
                "cancel" -> null
                else -> error("Unexpected WebSocket method: ${method.name}")
            }
        } as WebSocket
        client.injectSocketForTest(socket)
        client.forceStateForTest(WssClientV2.State.AWAITING_AUTH)
        // connect() 才会设置 helloConfig；测试经反射直接写入（与握手测试同手法）
        client.executorForTest().submit {
            val f = WssClientV2::class.java.getDeclaredField("helloConfig")
            f.isAccessible = true
            f.set(client, runtime.buildHelloConfig("2.0.0-test"))
        }.get(5, TimeUnit.SECONDS)
        feed(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L))
        takeFrame() // device.hello
        feed(
            MsgTypeV2.DEVICE_HELLO_ACK,
            mapOf(
                "session_id" to Ulid.newUlid(),
                "server_time" to 1794052800100L,
                "heartbeat_interval" to 30L,
                "accepted_capabilities" to accepted,
            ),
            seq = 2L,
        )
        drainMain()
        // hello_ack 后有两个 sync 来源：客户端自动 sync（executor 同步）+
        // 运行时 ONLINE 钩子的 buildSyncMessage 补发（IO 协程，异步）。全部排空，
        // 避免污染后续用例的帧断言。
        val syncDeadline = System.currentTimeMillis() + 5000
        var syncFrames = 0
        while (syncFrames < 2 && System.currentTimeMillis() < syncDeadline) {
            drainMain()
            val f = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f.type == MsgTypeV2.NOTIFICATION_SYNC) syncFrames++
        }
        assertEquals("握手后应收齐两路 notification.sync", 2, syncFrames)
    }

    private fun feed(type: String, payload: Map<String, Any?>, seq: Long = 10L) =
        runtime.clientForTest().onInboundTextForTest(
            EnvelopeV2(
                v = "2.0", id = Ulid.newUlid(), type = type, ts = 1794052800000L,
                seq = seq, dir = DIR_DOWNLINK, payload = payload,
            ).toJson(),
        )

    private fun takeFrame(): EnvelopeV2 =
        frames.poll(5, TimeUnit.SECONDS) ?: error("缺少上行帧")

    private fun drainMain() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun actionRequest(
        capability: String,
        args: Map<String, Any?>,
        idemKey: String = "idem-" + Ulid.newUlid(),
    ): Map<String, Any?> = mapOf(
        "action_id" to IdsV2.newActionId(),
        "capability" to capability,
        "arguments" to args,
        "idempotency_key" to idemKey,
        "timeout_ms" to 5000L,
        "expires_at" to 4102444800000L,
        "authorized_scope" to listOf(capability),
    )

    // ------------------------------------------------------------- 单例纪律

    @Test
    fun `singleton get returns the installed instance`() {
        assertSame(runtime, NodeRuntimeV2.peek())
        assertSame(runtime, NodeRuntimeV2.get(context))
    }

    @Test
    fun `pairing info exposes identity and flavor`() {
        val info = runtime.getPairingInfo()
        assertEquals(runtime.identity.getDeviceId(), info["device_id"])
        assertEquals(runtime.identity.getPublicKeyHex(), info["public_key_hex"])
        assertEquals("2.0", info["protocol_version"])
        assertTrue((info["capabilities"] as List<*>).isNotEmpty())
    }

    // ------------------------------------------------------------- 执行器注册与回执

    @Test
    fun `glasses show action is executed by registered v1 executor with v2 receipt`() {
        // glasses 模块默认关：开启后经降级路径（无眼镜桥 → 手机通知）执行成功
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().putBoolean(GlassesModule.KEY_ENABLED, true).apply()
        feed(MsgTypeV2.ACTION_REQUEST, actionRequest(
            "glasses.card.show",
            mapOf("card_id" to "card-0001", "title" to "t", "body" to "b"),
        ))
        val received = takeFrame()
        assertEquals(MsgTypeV2.ACTION_RECEIPT, received.type)
        assertEquals("received", received.payload["status"])
        val terminal = takeFrame()
        assertEquals(MsgTypeV2.ACTION_RECEIPT, terminal.type)
        assertEquals("succeeded", terminal.payload["status"])
        // v1 降级路径的 provider_ref 透传（degraded:phone_notification）
        assertEquals("degraded:phone_notification", terminal.payload["provider_ref"])
    }

    @Test
    fun `duplicate idempotency key replays terminal receipt without re-execution`() {
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().putBoolean(GlassesModule.KEY_ENABLED, true).apply()
        val req = actionRequest(
            "glasses.card.show",
            mapOf("card_id" to "card-0002", "title" to "t", "body" to "b"),
            idemKey = "idem-dup-0001",
        )
        feed(MsgTypeV2.ACTION_REQUEST, req, seq = 10L)
        takeFrame() // received
        val first = takeFrame() // succeeded
        // 重复下发同幂等键：回放已记录终态，不产生第二组 received+succeeded
        feed(MsgTypeV2.ACTION_REQUEST, req, seq = 11L)
        val replay = takeFrame()
        assertEquals(MsgTypeV2.ACTION_RECEIPT, replay.type)
        assertEquals(first.payload["status"], replay.payload["status"])
        assertEquals(first.payload["action_id"], replay.payload["action_id"])
        assertNull("回放后不应再有更多上行帧", frames.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `global pause fails action with unavailable`() {
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().putBoolean(PauseFlags.KEY_ALL_PAUSED, true).apply()
        feed(MsgTypeV2.ACTION_REQUEST, actionRequest(
            "glasses.card.show",
            mapOf("card_id" to "card-0003", "title" to "t", "body" to "b"),
        ))
        val receipt = takeFrame()
        assertEquals(MsgTypeV2.ACTION_RECEIPT, receipt.type)
        assertEquals("failed", receipt.payload["status"])
        assertEquals("unavailable", receipt.payload["error_code"])
        assertNull(frames.poll(300, TimeUnit.MILLISECONDS))
    }

    // ------------------------------------------------------------- 通知链

    @Test
    fun `notification event lands in inbox and receipts go uplink`() {
        val eventId = IdsV2.newEventId()
        val notificationId = IdsV2.newNotificationId()
        feed(
            MsgTypeV2.NOTIFICATION_EVENT,
            mapOf<String, Any?>(
                "event_id" to eventId, "notification_id" to notificationId,
                "device_id" to runtime.identity.getDeviceId(),
                "category" to "chat.message",
                "title" to "你好", "body" to "正文",
                "priority" to "normal",
                "created_at" to 1794052800000L, "expires_at" to 4102444800000L,
            ),
        )
        // 客户端自动回 received
        val autoReceipt = takeFrame()
        assertEquals(MsgTypeV2.NOTIFICATION_RECEIPT, autoReceipt.type)
        assertEquals("received", autoReceipt.payload["status"])
        // 监听器回调经 mainHandler 派发（需驱动主 Looper），handler 再在 IO 协程
        // 落库（received）→ 渲染/提交 → 状态迁移到 displayed/failed。
        // 轮询直至越过 received（诚实回执链跑完），或超时。
        val deadline = System.currentTimeMillis() + 5000
        var record: NotificationInbox.Record? = null
        while (System.currentTimeMillis() < deadline) {
            drainMain()
            record = runtime.inbox.findByEventId(eventId)
            if (record != null && record.status != NotificationInbox.Status.RECEIVED) break
            Thread.sleep(50)
        }
        assertNotNull("notification.event 应落入 Inbox", record)
        assertEquals(notificationId, record!!.notificationId)
        assertTrue(
            "落库状态应为 displayed/failed 之一（诚实回执，无伪报），实际=${record.status}",
            record.status == NotificationInbox.Status.DISPLAYED ||
                record.status == NotificationInbox.Status.FAILED,
        )
    }

    // ------------------------------------------------------------- sensor.query

    @Test
    fun `screen context read answered natively when builtin data source`() {
        // 直连写数据源（绕过 setDataSource 的前置校验，仅测试分发路由）
        context.getSharedPreferences("whale_capture", Context.MODE_PRIVATE)
            .edit().putString("data_source", "builtin").apply()
        val requestId = Ulid.newUlid()
        feed(
            MsgTypeV2.SENSOR_QUERY,
            mapOf(
                "request_id" to requestId,
                "operation" to "screen.context.read",
                "filters" to emptyMap<String, Any?>(),
                "max_items" to 5L,
            ),
        )
        val result = takeFrame()
        assertEquals(MsgTypeV2.SENSOR_RESULT, result.type)
        assertEquals(requestId, result.payload["request_id"])
        assertEquals("ok", result.payload["status"])
        assertEquals("capture.local", result.payload["source"])
    }

    @Test
    fun `device output read routes to agent output store`() {
        val requestId = Ulid.newUlid()
        feed(
            MsgTypeV2.SENSOR_QUERY,
            mapOf(
                "request_id" to requestId,
                "operation" to "device.output.read",
                "filters" to mapOf("output_ref" to "missing-ref"),
            ),
        )
        val result = takeFrame()
        assertEquals(MsgTypeV2.SENSOR_RESULT, result.type)
        assertEquals(requestId, result.payload["request_id"])
        // 引用不存在：模块返回 error 映射（agent 模块默认关 → denied/error 之一，绝不悬挂）
        assertTrue(result.payload["status"] in setOf("error", "denied", "unavailable"))
    }

    @Test
    fun `paused sensor query replies paused without dispatch`() {
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().putBoolean(PauseFlags.KEY_ALL_PAUSED, true).apply()
        val requestId = Ulid.newUlid()
        feed(
            MsgTypeV2.SENSOR_QUERY,
            mapOf(
                "request_id" to requestId,
                "operation" to "screen.context.read",
                "filters" to emptyMap<String, Any?>(),
            ),
        )
        val result = takeFrame()
        assertEquals("paused", result.payload["status"])
    }

    // ------------------------------------------------------------- 暂停语义（§16）

    @Test
    fun `entering paused purges drop-on-pause events`() {
        // 先断开连接：在线时入队会立即冲刷成 SENT（不是 PENDING），干扰断言
        runtime.clientForTest().disconnect()
        drainMain()
        runtime.sendExperienceEvent(
            kind = "notification.posted", occurredAt = 1L, source = "notification_read",
            summary = "s", privacyClass = "owner_private", dropOnPause = true,
        )
        runtime.sendExperienceEvent(
            kind = "screen.segment_ready", occurredAt = 1L, source = "screen_memo",
            summary = "keep", privacyClass = "owner_private", dropOnPause = false,
        )
        assertEquals(2, runtime.outbox.stats().pending)
        runtime.setStatus("paused")
        assertEquals("drop_on_pause 事件应被清除", 1, runtime.outbox.stats().pending)
    }
}
