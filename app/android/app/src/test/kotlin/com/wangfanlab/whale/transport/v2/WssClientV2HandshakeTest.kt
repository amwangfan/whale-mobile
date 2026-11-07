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
 * WssClientV2 握手与帧分发测试（contracts/v2 §4/§6）。
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

    private fun inbound(type: String, payload: Map<String, Any?>, seq: Long = 1L, v: String = "2.0",
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
        assertEquals("2.0", hello.v)
        val p = hello.payload
        assertEquals("2.0", p["protocol_version"])
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

    // ---- 违例与前向兼容（§4） ----

    @Test
    fun `unknown message type is silently ignored`() {
        feed(inbound("future.thing", mapOf("x" to 1L)))
        assertNull("未知类型不得产生任何上行帧", frames.poll(300, TimeUnit.MILLISECONDS))
        assertTrue(!socketClosed)
    }

    @Test
    fun `foreign major version gets protocol version error and close`() {
        feed(inbound(MsgTypeV2.DEVICE_HELLO_ACK, emptyMap(), v = "3.0"))
        val err = takeFrame()
        assertEquals(MsgTypeV2.ERROR, err.type)
        assertEquals("protocol_version", err.payload["code"])
        assertTrue(socketClosed)
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
    fun `notification event auto receipts received and dedups by event id`() {
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
        // 同 event_id 重发（多通道送达）：重发回执但不再分发
        feed(inbound(MsgTypeV2.NOTIFICATION_EVENT, payload, seq = 2L))
        val dupReceipt = takeFrame()
        assertEquals("received", dupReceipt.payload["status"])
        drainMain()
        assertNull(listenerEvents.poll())
    }
}
