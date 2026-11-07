package com.wangfanlab.whale.transport.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.notification2.NotificationInbox
import com.wangfanlab.whale.runtime.v2.NodeRuntimeV2
import com.wangfanlab.whale.transport.BridgeDataQueries
import com.wangfanlab.whale.transport.DeviceIdentity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * v2 桥方法面存在性测试（Robolectric）：
 * - `whale/transport_v2` 控制面方法全部有分支（不存在 notImplemented 漂移）；
 * - 数据面（notificationInbox/capture/favorites/backup/getLaunchRoute）经共享
 *   [BridgeDataQueries] 注册，方法名与 Flutter UI（app/lib/）约定逐字一致；
 * - 未知方法回 notImplemented（负面对照）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransportBridgeV2Test {

    /** 捕获回执的 Result（success/error/notImplemented 三态）。 */
    internal class FakeResult : MethodChannel.Result {
        var called = false
        var successValue: Any? = null
        var errorCode: String? = null
        var notImplementedCalled = false

        override fun success(result: Any?) {
            called = true; successValue = result
        }

        override fun error(code: String, message: String?, details: Any?) {
            called = true; errorCode = code
        }

        override fun notImplemented() {
            called = true; notImplementedCalled = true
        }
    }

    private lateinit var context: Context
    private lateinit var runtime: NodeRuntimeV2
    private lateinit var bridge: TransportBridgeV2

    /** 无操作 BinaryMessenger（方法面测试不需要真实平台回环）。 */
    private val noopMessenger = Proxy.newProxyInstance(
        BinaryMessenger::class.java.classLoader, arrayOf(BinaryMessenger::class.java),
    ) { _, _, _ -> null } as BinaryMessenger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NodeRuntimeV2.resetForTesting()
        context.deleteDatabase("whale_transport_v2.db")
        context.deleteDatabase("whale_action_dedup_v2.db")
        context.deleteDatabase("whale_notifications.db")
        context.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
            .edit().clear().apply()
        runtime = NodeRuntimeV2.installForTesting(
            context, DeviceIdentity(ByteArray(32) { (it + 7).toByte() }),
        )
        runtime.start()
        bridge = TransportBridgeV2(context, noopMessenger)
    }

    @After
    fun tearDown() {
        bridge.dispose()
        runtime.clientForTest().destroy()
        NodeRuntimeV2.resetForTesting()
        com.wangfanlab.whale.modules.notification2.NotificationActionReceiver.unwire()
        context.deleteDatabase("whale_transport_v2.db")
        context.deleteDatabase("whale_action_dedup_v2.db")
        context.deleteDatabase("whale_notifications.db")
    }

    private fun call(method: String, args: Any? = null): FakeResult {
        val r = FakeResult()
        bridge.onMethodCall(MethodCall(method, args), r)
        return r
    }

    // ---- 控制面存在性（缺参走 bad_args 即证明分支存在；无参方法应成功） ----

    @Test
    fun `control surface methods are all implemented`() {
        // 无参/可空参方法：应 success
        for (m in listOf(
            "disconnectV2", "getStateV2", "getIdentityV2", "getPairingInfoV2",
            "getModuleStates", "isAllPaused", "isBgServiceEnabled",
            "setSensorQueryDelegateV2",
        )) {
            val r = call(m)
            assertTrue("$m 应已实现", r.called && !r.notImplementedCalled)
            assertNull("$m 不应报错", r.errorCode)
        }
        // 必填参缺失 → bad_args（证明分支存在）
        for (m in listOf(
            "connectV2", "setStatusV2", "sendExperienceEvent", "sendActionReceiptV2",
            "sendNotificationReceipt", "sendNotificationAction", "sendSensorResultV2",
            "setModuleEnabled", "setAllPaused", "setBgServiceEnabled",
        )) {
            val r = call(m)
            assertEquals("$m 缺参应回 bad_args", "bad_args", r.errorCode)
        }
    }

    @Test
    fun `declared control method set matches implementation`() {
        // 对照组：CONTROL_METHODS 中每个名字都不得 notImplemented
        for (m in TransportBridgeV2.CONTROL_METHODS) {
            val r = call(m)
            assertFalse("$m 不得 notImplemented", r.notImplementedCalled)
            assertTrue("$m 应有回执", r.called)
        }
    }

    // ---- 数据面存在性（Flutter UI 约定，见 inbox_page.dart / screenmemo/data/） ----

    @Test
    fun `data surface methods are all implemented`() {
        // 无参成功类
        for (m in listOf(
            "notificationInbox.history", "notificationInbox.markAllRead",
            "notificationInbox.count", "capture.listCaptures", "capture.listSegments",
            "capture.storageStats", "favorites.list", "favorites.remove",
            "getLaunchRoute",
        )) {
            val r = call(m)
            assertTrue("$m 应已实现", r.called && !r.notImplementedCalled)
            assertNull("$m 不应报错（${r.errorCode}）", r.errorCode)
        }
        // 必填参缺失 → bad_args
        for (m in listOf(
            "notificationInbox.markRead", "capture.search", "capture.getEvidencePath",
            "capture.purgeBefore", "capture.compressImagesBefore",
            "favorites.add", "backup.exportZip", "backup.importZip",
        )) {
            val r = call(m)
            assertEquals("$m 缺参应回 bad_args", "bad_args", r.errorCode)
        }
    }

    @Test
    fun `declared data method set matches implementation`() {
        for (m in BridgeDataQueries.HANDLED_METHODS) {
            val r = call(m)
            assertFalse("$m 不得 notImplemented", r.notImplementedCalled)
        }
    }

    // ---- 行为抽查 ----

    @Test
    fun `notification inbox count starts at zero`() {
        val r = call("notificationInbox.count")
        @Suppress("UNCHECKED_CAST")
        assertEquals(0, (r.successValue as Map<String, Any?>)["count"])
    }

    @Test
    fun `module states roundtrip via v2 bridge`() {
        val r0 = call("getModuleStates")
        @Suppress("UNCHECKED_CAST")
        val states0 = r0.successValue as Map<String, Any?>
        assertEquals(false, states0["voip"]) // voip 默认关
        call("setModuleEnabled", mapOf("module" to "voip", "enabled" to true))
        val r1 = call("getModuleStates")
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (r1.successValue as Map<String, Any?>)["voip"])
    }

    @Test
    fun `all paused flag roundtrip`() {
        assertEquals(false, call("isAllPaused").successValue)
        call("setAllPaused", mapOf("paused" to true))
        assertEquals(true, call("isAllPaused").successValue)
        call("setAllPaused", mapOf("paused" to false))
    }

    @Test
    fun `launch route is null without deep link extras`() {
        val r = call("getLaunchRoute")
        @Suppress("UNCHECKED_CAST")
        assertNull((r.successValue as Map<String, Any?>)["route"])
    }

    @Test
    fun `getStateV2 carries v2 protocol fields`() {
        val r = call("getStateV2")
        @Suppress("UNCHECKED_CAST")
        val state = r.successValue as Map<String, Any?>
        assertEquals("2.0", state["protocol"])
        assertEquals(runtime.identity.getDeviceId(), state["device_id"])
        assertEquals("disconnected", state["state"])
    }

    @Test
    fun `unknown method returns notImplemented`() {
        val r = call("no.such.method")
        assertTrue(r.notImplementedCalled)
    }
}

/**
 * 共享数据查询面的独立行为测试（不经桥，直接构造；v1/v2 两桥共用该实现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BridgeDataQueriesTest {

    private lateinit var context: Context
    private lateinit var store: com.wangfanlab.whale.modules.capture.CaptureStore
    private lateinit var inbox: NotificationInbox
    private lateinit var queries: BridgeDataQueries

    private val storeDb = "test_queries_capture.db"
    private val inboxDb = "test_queries_inbox.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(storeDb)
        context.deleteDatabase(inboxDb)
        store = com.wangfanlab.whale.modules.capture.CaptureStore(context, storeDb)
        inbox = NotificationInbox(context, inboxDb)
        queries = BridgeDataQueries(
            context = context,
            activityProvider = { null },
            storeProvider = { store },
            inboxProvider = { inbox },
        )
    }

    @After
    fun tearDown() {
        store.close()
        inbox.close()
        context.deleteDatabase(storeDb)
        context.deleteDatabase(inboxDb)
    }

    private fun call(method: String, args: Any? = null): TransportBridgeV2Test.FakeResult {
        val r = TransportBridgeV2Test.FakeResult()
        queries.handle(MethodCall(method, args), r)
        return r
    }

    @Test
    fun `favorites add is idempotent and list reflects it`() {
        call("favorites.add", mapOf("segment_id" to "segment_01JAAAAAAAAAAAAAAAAAAAAA", "note" to "n"))
        val dup = call("favorites.add", mapOf("segment_id" to "segment_01JAAAAAAAAAAAAAAAAAAAAA"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (dup.successValue as Map<String, Any?>)["ok"])
        val list = call("favorites.list")
        assertEquals(1, (list.successValue as List<*>).size)
        val removed = call("favorites.remove", mapOf("segment_id" to "segment_01JAAAAAAAAAAAAAAAAAAAAA"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (removed.successValue as Map<String, Any?>)["removed"])
        assertEquals(0, (call("favorites.list").successValue as List<*>).size)
    }

    @Test
    fun `storage stats shape matches flutter contract`() {
        val r = call("capture.storageStats")
        @Suppress("UNCHECKED_CAST")
        val m = r.successValue as Map<String, Any?>
        assertEquals(
            setOf("captures", "segments", "favorites", "notes", "image_files", "image_bytes"),
            m.keys,
        )
    }

    @Test
    fun `inbox history returns inserted notification with contract fields`() {
        val event = com.wangfanlab.whale.modules.notification2.NotificationEvent(
            eventId = "evt_01JBBBBBBBBBBBBBBBBBBBBB",
            notificationId = "notif_01JCCCCCCCCCCCCCCCCCCCC",
            deviceId = "d", category = "chat.message",
            title = "t", body = "b", priority = "normal",
            createdAt = 1L, expiresAt = 4102444800000L,
            target = null, actions = emptyList(), groupKey = null, collapseKey = null,
        )
        inbox.insert(event, NotificationInbox.Status.RECEIVED, 100L)
        val r = call("notificationInbox.history", mapOf("limit" to 10))
        val rows = r.successValue as List<*>
        assertEquals(1, rows.size)
        @Suppress("UNCHECKED_CAST")
        val row = rows[0] as Map<String, Any?>
        assertEquals("evt_01JBBBBBBBBBBBBBBBBBBBBB", row["event_id"])
        assertEquals("chat.message", row["category"])
        assertEquals(false, row["read"])
        // markRead → read=true
        call("notificationInbox.markRead", mapOf("notification_id" to "notif_01JCCCCCCCCCCCCCCCCCCCC"))
        val after = (call("notificationInbox.history").successValue as List<*>)[0] as Map<*, *>
        assertEquals(true, after["read"])
    }

    @Test
    fun `evidence path rejects unknown ref`() {
        val r = call("capture.getEvidencePath", mapOf("ref" to "capture:row_999999"))
        assertEquals("not_found", r.errorCode)
    }
}
