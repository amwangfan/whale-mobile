package com.wangfanlab.whale.transport.v2

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.transport.DeviceIdentity
import okhttp3.WebSocket
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * WssClientV2 握手与帧分发测试（contracts/device §4/§6）。
 * 以代理 WebSocket 注入假连接，不走真实网络；Ed25519 签名用公钥真实验签。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WssClientV2HandshakeTest {

    private lateinit var context: Context
    private lateinit var client: WssClientV2
    private lateinit var outbox: EventOutboxV2
    private lateinit var store: ActionDedupStoreV2
    private lateinit var identity: DeviceIdentity
    private val outboxDb = "test_wss_v2_outbox.db"
    private val dedupDb = "test_wss_v2_dedup.db"
    private val frames = LinkedBlockingQueue<EnvelopeV2>()
    private var socketClosed = false
    private val listenerEvents = LinkedBlockingQueue<Map<String, Any?>>()

    private val challenge = "a".repeat(64)
    private val helloConfig = WssClientV2.HelloConfig(
        capabilities = setOf("device.tap", "screen.context.read", "device.status"),
        features = WssClientV2.FeatureSet(
            rootShell = false, accessibility = true, mediaProjection = false,
            telecom = true, ocrMlkit = true,
        ),
        deviceModel = "Leaf5", androidRelease = "13", apiLevel = 33,
        appVersion = "2.0.0", appFlavor = "daily",
    )

    private val listener = object : WssClientV2.Listener {
        override fun onStateChanged(state: WssClientV2.State, info: Map<String, Any?>) {
            listenerEvents.add(mapOf("kind" to "state", "state" to WssClientV2.stateName(state)))
        }
        override fun onNotificationEvent(payload: Map<String, Any?>) {
            listenerEvents.add(mapOf("kind" to "notification_event") + payload)
        }
        override fun onProtocolError(code: String, message: String, refId: String) {
            listenerEvents.add(
                mapOf("kind" to "error", "code" to code, "message" to message, "ref_id" to refId),
            )
        }

        /** 冒烟测试钩：device.status 查询由本测试 listener 接管并如实回 ok。 */
        var sensorQueryHandler: ((Map<String, Any?>) -> Boolean)? = null

        override fun onSensorQuery(payload: Map<String, Any?>): Boolean =
            sensorQueryHandler?.invoke(payload) ?: false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(outboxDb)
        context.deleteDatabase(dedupDb)
        identity = DeviceIdentity(ByteArray(32) { it.toByte() })
        outbox = EventOutboxV2(context, null, deviceIdProvider = { identity.getDeviceId() }, dbName = outboxDb)
        store = ActionDedupStoreV2(context, dedupDb)
        socketClosed = false
        listenerEvents.clear()
        client = WssClientV2(identity, outbox, store, listener, Handler(Looper.getMainLooper()))
        val socket = Proxy.newProxyInstance(
            WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java),
        ) { _, method, args ->
            when (method.name) {
                "send" -> {
                    frames.add(EnvelopeCodecV2.decode(args!![0] as String))
                    true
                }
                "close" -> { socketClosed = true; true }
                "cancel" -> null
                else -> error("Unexpected WebSocket method: ${method.name}")
            }
        } as WebSocket
        client.injectSocketForTest(socket)
        client.forceStateForTest(WssClientV2.State.AWAITING_AUTH)
        // connect() 设置 helloConfig（走 executor，同步等待完成）
        client.executorForTest().submit {
            val f = WssClientV2::class.java.getDeclaredField("helloConfig")
            f.isAccessible = true
            f.set(client, helloConfig)
        }.get(5, TimeUnit.SECONDS)
        // 预热 SQLite（Robolectric 沙箱首次加载原生库较慢，避免 destroy 路径在
        // executor 线程上承担首载成本导致 teardown 超时误判）。
        outbox.stats()
    }

    @After
    fun tearDown() {
        drainMain() // 落掉 transition 派发的 main-looper runnable
        client.destroy()
        assertTrue(
            "executor 应及时终止（destroy 内含 shutdownNow 兜底）",
            client.executorForTest().awaitTermination(10, TimeUnit.SECONDS),
        )
        outbox.close()
        store.close()
        context.deleteDatabase(outboxDb)
        context.deleteDatabase(dedupDb)
    }

    private fun inbound(type: String, payload: Map<String, Any?>, seq: Long = 1L, v: String = "1.0",
                        dir: String = DIR_DOWNLINK): String =
        EnvelopeV2(v = v, id = Ulid.newUlid(), type = type, ts = 1794052800000L, seq = seq,
            dir = dir, payload = payload).toJson()

    private fun feed(text: String) = client.onInboundTextForTest(text)

    private fun takeFrame(): EnvelopeV2 = frames.poll(5, TimeUnit.SECONDS) ?: error("缺少上行帧")

    private fun drainMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    // ---- 握手 ----

    @Test
    fun `challenge produces hello with full v2 fields and valid ed25519 signature`() {
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        val hello = takeFrame()
        assertEquals(MsgTypeV2.DEVICE_HELLO, hello.type)
        assertEquals(DIR_UPLINK, hello.dir)
        assertEquals(1L, hello.seq) // 上行 seq 从 1 开始
        assertEquals("1.0", hello.v) // 2026-11-23 clean break：信封 1.x
        val p = hello.payload
        assertEquals("1.0", p["protocol_version"])
        assertEquals(identity.getDeviceId(), p["device_id"])
        assertEquals(listOf("device.tap", "screen.context.read", "device.status"), p["capabilities"])
        @Suppress("UNCHECKED_CAST")
        val features = p["features"] as Map<String, Any?>
        assertEquals(setOf("root_shell", "accessibility", "media_projection", "telecom", "ocr_mlkit"), features.keys)
        @Suppress("UNCHECKED_CAST")
        val device = p["device"] as Map<String, Any?>
        assertEquals("Leaf5", device["model"])
        assertEquals("13", device["android_release"])
        assertEquals(33L, device["api_level"])
        assertEquals("2.0.0", p["app_version"])
        assertEquals("daily", p["app_flavor"])
        assertEquals(challenge, p["challenge"])
        // §4：signature = Ed25519(challenge 字符串的 UTF-8 字节)，公钥验签
        val sigHex = p["signature"] as String
        assertTrue(Regex("^[0-9a-f]{128}$").matches(sigHex))
        val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pub = Ed25519PublicKeyParameters(
            identity.getPublicKeyHex().chunked(2).map { it.toInt(16).toByte() }.toByteArray(), 0,
        )
        val verifier = Ed25519Signer()
        verifier.init(false, pub)
        val msg = challenge.toByteArray(Charsets.UTF_8)
        verifier.update(msg, 0, msg.size)
        assertTrue("hello 签名验签失败", verifier.verifySignature(sigBytes))
    }

    @Test
    fun `hello ack brings client online flushes outbox and sends notification sync`() {
        // 预置一条待发事件
        val eventId = outbox.enqueueExperienceEvent(
            kind = "screen.segment_ready", occurredAt = 1L, source = "screen_memo",
            summary = "s", privacyClass = "owner_private",
        )
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        takeFrame() // hello
        feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, mapOf(
            "session_id" to Ulid.newUlid(),
            "server_time" to 1794052800100L,
            "heartbeat_interval" to 30L,
            "accepted_capabilities" to listOf("device.tap"),
        ), seq = 2L))
        val event = takeFrame()
        assertEquals(MsgTypeV2.EXPERIENCE_EVENT, event.type)
        assertEquals(eventId, event.payload["event_id"])
        val sync = takeFrame()
        assertEquals(MsgTypeV2.NOTIFICATION_SYNC, sync.type)
        assertEquals(identity.getDeviceId(), sync.payload["device_id"])
        assertEquals(setOf("device.tap"), client.dispatcher.acceptedCapabilitiesProvider())
        drainMain()
        val states = generateSequence { listenerEvents.poll() }.toList()
        assertTrue(states.any { it["state"] == "online" })
    }

    @Test
    fun `event ack stops outbox retransmission`() {
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        takeFrame()
        feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, mapOf(
            "session_id" to Ulid.newUlid(), "server_time" to 1L,
            "heartbeat_interval" to 30L, "accepted_capabilities" to emptyList<String>(),
        ), seq = 2L))
        val eventId = outbox.enqueueExperienceEvent(
            kind = "k", occurredAt = 1L, source = "user", summary = "s", privacyClass = "public",
        )
        client.executorForTest().submit {
            val m = WssClientV2::class.java.getDeclaredMethod("flushOutboxLocked")
            m.isAccessible = true
            m.invoke(client)
        }.get(5, TimeUnit.SECONDS)
        takeFrame() // experience.event
        assertEquals(1, outbox.stats().sent)
        feed(inbound(MsgTypeV2.EVENT_ACK, mapOf("event_ids" to listOf(eventId)), seq = 3L))
        assertEquals(1, outbox.stats().acked)
        assertEquals(0, outbox.stats().sent)
    }

    @Test
    fun `capability smoke - device tap observe actions and device status query`() {
        // 清单 §5.3 全能力冒烟（假 core 等效）：action.request(device.tap /
        // device.observe) 经注册执行器走 收到→终态 回执链；sensor.query
        // (device.status) 经监听器接管回 sensor.result ok。
        val executed = mutableListOf<String>()
        for (cap in listOf("device.tap", "device.observe")) {
            client.dispatcher.registerHandler(cap) { request, reply ->
                executed.add(request.capability)
                reply(
                    ReceiptDispatcherV2.ActionOutcome(
                        status = ReceiptStatus.SUCCEEDED,
                        resultSummary = "fake-core smoke ok",
                        verificationStatus = "skipped",
                    ),
                )
            }
        }
        listener.sensorQueryHandler = { payload ->
            val requestId = payload["request_id"] as String
            client.sendSensorResult(
                requestId, "ok",
                listOf(mapOf("battery" to 87L, "network" to "wifi")), false, "fake-core",
            )
            true
        }
        // 握手上线（收窄能力含本用例三项）
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        takeFrame() // hello
        feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, mapOf(
            "session_id" to Ulid.newUlid(),
            "server_time" to 1794052800100L,
            "heartbeat_interval" to 30L,
            "accepted_capabilities" to listOf("device.tap", "device.observe", "device.status"),
        ), seq = 2L))
        takeFrame() // notification.sync

        // device.tap / device.observe：received（非终态）→ succeeded（终态）
        for ((i, cap) in listOf("device.tap", "device.observe").withIndex()) {
            feed(inbound(MsgTypeV2.ACTION_REQUEST, mapOf(
                "action_id" to IdsV2.newActionId(),
                "capability" to cap,
                "arguments" to emptyMap<String, Any?>(),
                "idempotency_key" to "smoke:$i:${Ulid.newUlid()}",
                "timeout_ms" to 5000L,
                "expires_at" to 4102444800000L,
                "authorized_scope" to listOf(cap),
            ), seq = 10L + i))
            val received = takeFrame()
            assertEquals(MsgTypeV2.ACTION_RECEIPT, received.type)
            assertEquals("received", received.payload["status"])
            val terminal = takeFrame()
            assertEquals(MsgTypeV2.ACTION_RECEIPT, terminal.type)
            assertEquals("succeeded", terminal.payload["status"])
        }
        assertEquals(listOf("device.tap", "device.observe"), executed)

        // device.status：sensor.query → sensor.result ok
        val requestId = Ulid.newUlid()
        feed(inbound(MsgTypeV2.SENSOR_QUERY, mapOf(
            "request_id" to requestId,
            "operation" to "device.status",
            "filters" to emptyMap<String, Any?>(),
            "max_items" to 5L,
        ), seq = 20L))
        val result = takeFrame()
        assertEquals(MsgTypeV2.SENSOR_RESULT, result.type)
        assertEquals(requestId, result.payload["request_id"])
        assertEquals("ok", result.payload["status"])
    }

    @Test
    fun `heartbeat frames follow hello ack interval`() {
        // 清单 §5.2 心跳：hello_ack 的 heartbeat_interval=1s → 上行 device.heartbeat
        // 按约到来（device_id/observed_at/status 齐备）。
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        takeFrame()
        feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, mapOf(
            "session_id" to Ulid.newUlid(),
            "server_time" to 1794052800100L,
            "heartbeat_interval" to 1L,
            "accepted_capabilities" to emptyList<String>(),
        ), seq = 2L))
        takeFrame() // notification.sync
        val deadline = System.currentTimeMillis() + 5000
        var heartbeat: EnvelopeV2? = null
        while (System.currentTimeMillis() < deadline && heartbeat == null) {
            val f = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f.type == MsgTypeV2.DEVICE_HEARTBEAT) heartbeat = f
        }
        assertNotNull("hello_ack 后应按 heartbeat_interval 上行心跳", heartbeat)
        heartbeat!!
        assertEquals(identity.getDeviceId(), heartbeat.payload["device_id"])
        assertEquals("online", heartbeat.payload["status"])
        assertNotNull(heartbeat.payload["observed_at"])
    }

    // ---- 违例与前向兼容（§4） ----

    @Test
    fun `unknown message type is silently ignored`() {
        feed(inbound("future.thing", mapOf("x" to 1L)))
        assertNull("未知类型不得产生任何上行帧", frames.poll(300, TimeUnit.MILLISECONDS))
        assertTrue(!socketClosed)
    }

    @Test
    fun `foreign major version gets protocol version error and close`() {
        // 2026-11-23 clean break 负例（清单 §5.5）：v=2.0（旧 V2）与更高 major
        // 一律回 error.protocol_version 并关闭（端侧对 core 负例的对称行为）。
        for (foreign in listOf("2.0", "3.0")) {
            socketClosed = false
            feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, emptyMap(), v = foreign))
            val err = takeFrame()
            assertEquals(MsgTypeV2.ERROR, err.type)
            assertEquals("protocol_version", err.payload["code"])
            assertTrue("v=$foreign 必须触发关闭", socketClosed)
        }
    }

    @Test
    fun `protocol version error from core is terminal with explicit event`() {
        // 版本 floor（清单 §5.6）：hello 被 core 以 error.protocol_version 拒绝时，
        // 端侧关连、事件上行、且不再重连（voluntaryClose 拦截 onClosed 的重连路径）。
        feed(inbound(MsgTypeV2.AUTH_CHALLENGE, mapOf("challenge" to challenge, "expires_in" to 60L)))
        takeFrame() // hello
        feed(inbound(MsgTypeV2.ERROR, mapOf(
            "ref_id" to WssClientV2.ZERO_ULID,
            "code" to "protocol_version",
            "message" to "unsupported protocol major: 1",
        ), seq = 2L))
        assertTrue("protocol_version 后必须关连", socketClosed)
        drainMain()
        val events = generateSequence { listenerEvents.poll() }.toList()
        val errEvent = events.firstOrNull { it["kind"] == "error" }
        assertNotNull("protocol_version 必须派发错误事件（UI 明示升级）", errEvent)
        assertEquals("protocol_version", errEvent!!["code"])
        assertTrue(
            "终态后必须落到 disconnected（不得残留 connecting/awaiting_auth）",
            events.any { it["kind"] == "state" && it["state"] == "disconnected" },
        )
    }

    @Test
    fun `inbound frame with wrong dir gets invalid message`() {
        feed(inbound(MsgTypeV2.DEVICE_HEARTBEAT, emptyMap(), dir = DIR_UPLINK))
        val err = takeFrame()
        assertEquals(MsgTypeV2.ERROR, err.type)
        assertEquals("invalid_message", err.payload["code"])
        assertTrue(!socketClosed) // 保持连接（§4）
    }

    @Test
    fun `malformed frame gets invalid message without close`() {
        feed("{\"not\":\"envelope\"}")
        val err = takeFrame()
        assertEquals(MsgTypeV2.ERROR, err.type)
        assertEquals("invalid_message", err.payload["code"])
        assertTrue(!socketClosed)
    }

    // ---- 通知子协议（§13） ----

    @Test
    fun `notification event auto receipts received and redispatches duplicate for terminal recovery`() {
        val eventId = IdsV2.newEventId()
        val notifId = IdsV2.newNotificationId()
        val payload = mapOf<String, Any?>(
            "event_id" to eventId, "notification_id" to notifId,
            "device_id" to identity.getDeviceId(), "category" to "chat.message",
            "title" to "t", "body" to "b", "priority" to "normal",
            "created_at" to 1L, "expires_at" to 4102444800000L,
        )
        feed(inbound(MsgTypeV2.NOTIFICATION_EVENT, payload, seq = 1L))
        val receipt = takeFrame()
        assertEquals(MsgTypeV2.NOTIFICATION_RECEIPT, receipt.type)
        assertEquals("received", receipt.payload["status"])
        assertEquals(eventId, receipt.payload["event_id"])
        assertEquals(notifId, receipt.payload["notification_id"])
        drainMain()
        val first = listenerEvents.poll()
        assertNotNull(first)
        assertEquals("notification_event", first!!["kind"])
        // 同 event_id 重发（多通道送达/core 补传）：重发 received（无业务处理监听器
        // 的兼容语义），且仍分发给监听器——业务层依持久 Inbox 去重不重复展示，
        // 并按落库状态重发 displayed/failed 等终态回执（§13 收敛）。
        feed(inbound(MsgTypeV2.NOTIFICATION_EVENT, payload, seq = 2L))
        val dupReceipt = takeFrame()
        assertEquals(MsgTypeV2.NOTIFICATION_RECEIPT, dupReceipt.type)
        assertEquals("received", dupReceipt.payload["status"])
        assertEquals(eventId, dupReceipt.payload["event_id"])
        drainMain()
        val dup = listenerEvents.poll()
        assertNotNull("重复事件必须仍分发给监听器（业务层终态恢复入口）", dup)
        assertEquals("notification_event", dup!!["kind"])
        assertEquals(eventId, dup["event_id"])
        assertNull("同一次重复不得多次分发", listenerEvents.poll())
    }
}
