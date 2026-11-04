package com.wangfanlab.whale.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Exercise the real WssClient dispatch/receipt paths, with no network or device identity access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WssClientReceiptDedupTest {
    private lateinit var context: Context
    private lateinit var queue: OfflineQueue
    private lateinit var client: WssClient
    private lateinit var executor: ScheduledExecutorService
    private val frames = LinkedBlockingQueue<Map<String, Any?>>()
    private val dbName = "test_wss_receipt_dedup.db"
    private var executions = 0
    private val result = mapOf<String, Any?>("exit_code" to 0L,
        "observation" to mapOf("ok" to true, "nodes" to listOf("button")))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        queue = OfflineQueue(context, dbName = dbName)
        // This test never connects/authenticates. Use the test-only identity
        // constructor (no Android Keystore in unit-test environments).
        val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
        client = WssClient(identity, queue, object : WssClient.Listener {
            override fun onStateChanged(state: WssClient.State, info: Map<String, Any?>) {}
            override fun onActionRequest(payload: Map<String, Any?>) {}
            override fun onProtocolError(code: String, message: String, refId: String) {}
            override fun hasUiActionHandler() = true
        }, Handler(Looper.getMainLooper()))
        executor = field("executor").get(client) as ScheduledExecutorService
        field("acceptedCapabilities").set(client, listOf("device.agent.shell"))
        val socket = Proxy.newProxyInstance(WebSocket::class.java.classLoader,
            arrayOf(WebSocket::class.java)) { _, method, args ->
            when (method.name) {
                "send" -> { frames.add(EnvelopeCodec.decode(args!![0] as String).payload); true }
                "close" -> true
                "cancel" -> null
                else -> error("Unexpected WebSocket method: ${method.name}")
            }
        } as WebSocket
        field("ws").set(client, socket)
    }

    @After
    fun tearDown() {
        client.destroy()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        queue.close()
        context.deleteDatabase(dbName)
    }

    private fun field(name: String) = WssClient::class.java.getDeclaredField(name).also {
        it.isAccessible = true
    }

    private fun request(actionId: String, key: String = "key") {
        val method = WssClient::class.java.getDeclaredMethod("onActionRequestLocked", Envelope::class.java)
            .also { it.isAccessible = true }
        executor.submit {
            method.invoke(client, Envelope.uplink("action.request", 1L, mapOf(
                "action_id" to actionId, "capability" to "device.agent.shell",
                "idempotency_key" to key, "expires_at" to System.currentTimeMillis() + 60000L,
            ), System.currentTimeMillis()))
        }.get(5, TimeUnit.SECONDS)
    }

    private fun receipt(): Map<String, Any?> =
        frames.poll(5, TimeUnit.SECONDS) ?: error("Missing receipt")

    @Test
    fun `native duplicate replays full terminal without executing again`() {
        client.registerActionExecutor("device.agent.shell", object : WssClient.ActionExecutor {
            override fun execute(payload: Map<String, Any?>,
                reply: (String, String?, String?, Map<String, Any?>?) -> Unit) {
                executions++
                reply("executed", null, "native-ref", result)
            }
        })
        request("first")
        assertEquals("received", receipt()["state"])
        val terminal = receipt()
        assertEquals("executed", terminal["state"])
        assertEquals(result, terminal["result"])
        request("retry")
        assertEquals(terminal + ("action_id" to "retry"), receipt())
        assertEquals(1, executions)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `Dart failed receipt preserves provider result and original timestamp`() {
        request("dart-first")
        assertEquals("received", receipt()["state"])
        client.sendActionReceipt("dart-first", "failed", executedAt = 123L,
            errorCode = "internal", providerRef = "dart-ref", result = result)
        val terminal = receipt()
        assertEquals("internal", terminal["error_code"])
        assertEquals(123L, terminal["executed_at"])
        request("dart-retry")
        assertEquals(terminal + ("action_id" to "dart-retry"), receipt())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `legacy terminal receipt remains replayable without inventing fields`() {
        queue.recordActionIfAbsent("key", "legacy", "device.agent.shell")
        queue.setActionTerminal("key", "failed", "expired")
        request("legacy-retry")
        assertEquals(mapOf("action_id" to "legacy-retry", "state" to "failed",
            "error_code" to "expired"), receipt())
        assertEquals(0, executions)
    }
}
