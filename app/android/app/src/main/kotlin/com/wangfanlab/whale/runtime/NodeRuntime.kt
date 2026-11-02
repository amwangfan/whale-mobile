package com.wangfanlab.whale.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.transport.DeviceIdentity
import com.wangfanlab.whale.transport.IdentityQueueCipher
import com.wangfanlab.whale.transport.Ids
import com.wangfanlab.whale.transport.OfflineQueue
import com.wangfanlab.whale.transport.WssClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 节点运行时（BG-01）：WSS 长连 / 设备身份 / 离线队列 / 模块执行器的唯一所有者。
 *
 * 生命周期归属 Application 进程而非 Flutter Activity：
 * - [WhaleApp.onCreate] 经 [get] 创建单例并 [start]；此后 Flutter Engine 重建
 *   只重新绑定 UI 监听（[addUiListener]/[removeUiListener]），不重建运行时。
 * - 同一进程内只允许一个 NodeRuntime（[get] 幂等），因此同一设备身份
 *   不可能出现两个心跳发送者；[NodeService] 只是本运行时的前台载体。
 * - sensor.query 有明确处理器链：全局暂停 → paused；已注册 Dart 委托 → 转发；
 *   否则 fail-closed 回 unavailable（不会把请求丢到不存在的 EventChannel）。
 * - 通知/来电/受控动作执行器在 init 即注册到唯一的 [WssClient]，
 *   UI 不存在时仍能处理或拒绝（无静默 accepted）。
 *
 * kill switch：[shutdown] 断开连接并销毁客户端（模块钩子一并注销）；
 * 之后可再次 [connect]（内部重建客户端）。设备撤销由 WssClient 自身处理
 * （清队列 + 终态断线，需重新配对）。
 */
class NodeRuntime private constructor(
    private val appContext: Context,
    private val mainHandler: Handler,
) : WssClient.Listener {

    // ------------------------------------------------------------- UI 监听扇出

    /**
     * UI 侧监听（TransportBridge 注册）。回调已在主线程。
     * 事件为通用 Map（kind=state/action_request/action_receipt/sensor_query/
     * call_invite/call_hangup/error），与原 EventChannel 载荷一致。
     */
    fun interface UiListener {
        fun onEvent(event: Map<String, Any?>)
    }

    /**
     * sensor.query 处理器（Dart 侧 screenmemo 模块经 TransportBridge 注册）。
     * 返回 true 表示接管（稍后经 [sendSensorResult] 回复）；false 表示不接管。
     */
    fun interface SensorQueryHandler {
        fun onSensorQuery(payload: Map<String, Any?>): Boolean
    }

    private val uiEventHub = UiEventHub()

    /** Dart 侧 sensor.query 委托；null = 无处理器，fail-closed 回 unavailable。 */
    @Volatile
    private var sensorQueryHandler: SensorQueryHandler? = null

    @Volatile
    private var deviceStatus: String = "online"

    // ------------------------------------------------------------- 运行时组件（唯一实例）

    val identity: DeviceIdentity = DeviceIdentity(appContext)

    /** SEC-01：队列 payload 以身份主密钥（Keystore AES/GCM）应用层加密。 */
    val queue: OfflineQueue = OfflineQueue(appContext, cipher = IdentityQueueCipher(identity))

    /** SM-02：内置采集存储（截图元数据+OCR 索引）与持久化管线。 */
    val captureStore: com.wangfanlab.whale.modules.capture.CaptureStore =
        com.wangfanlab.whale.modules.capture.CaptureStore(appContext)
    val capturePipeline: com.wangfanlab.whale.modules.capture.CapturePipeline =
        com.wangfanlab.whale.modules.capture.CapturePipeline(
            appContext, captureStore,
            com.wangfanlab.whale.modules.capture.ocr.OcrEngine.mlKit,
        )

    val notificationModule: NotificationModule = NotificationModule(appContext)

    /** 通知监听模块：init 即触碰，注册 NotificationListenerService 静态钩子。 */
    val notificationReadModule: NotificationReadModule = NotificationReadModule(
        appContext,
        eventSender = { summary, occurredAt ->
            sendSensorEvent(
                kind = "notification.posted",
                occurredAt = occurredAt,
                privacyClass = "owner_private",
                summary = summary,
                dropOnPause = true,
            )
        },
    )

    val safeActionModule: SafeActionModule = SafeActionModule(appContext)

    /** VoIP 模块：init 即触碰，注册 FakeConnectionService.CallStateListener 钩子。 */
    val voipModule: VoipModule = VoipModule(
        appContext,
        eventSender = { ev ->
            sendSensorEvent(
                kind = ev.kind,
                occurredAt = System.currentTimeMillis(),
                privacyClass = ev.privacyClass,
                summary = ev.summary,
            )
        },
    )

    @Volatile
    private var client: WssClient = newClient()

    @Volatile
    private var started = false

    @Volatile
    private var shutdown = false

    private fun newClient(): WssClient = WssClient(identity, queue, this, mainHandler).apply {
        heartbeatStatusProvider = { WssClient.HeartbeatStatus(status = deviceStatus) }
        // action 执行器注册表：通知/VoIP/safe_action 模块在 Kotlin 侧直接执行（DESIGN §4）
        registerActionExecutor(NotificationModule.CAPABILITY, notificationModule)
        registerActionExecutor(VoipModule.CAPABILITY_RING, voipModule)
        registerActionExecutor(SafeActionModule.CAPABILITY, safeActionModule)
    }

    // ------------------------------------------------------------- 生命周期

    /**
     * 启动运行时（Application.onCreate 调用一次；幂等）。
     * 触碰各模块完成系统钩子注册，保证 UI 未启动时事件也能入队。
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        shutdown = false
        // 模块成员为构造期初始化的 val：voip/notificationRead 的系统钩子
        // （FakeConnectionService.CallStateListener / NotificationListenerService
        // 静态钩子）在 NodeRuntime 创建时即注册，UI 未启动时事件也能入队。
        registerNetworkCallback()
        Log.i(TAG, "NodeRuntime 已启动 device_id=${identity.getDeviceId()}")
    }

    /**
     * 连接恢复（BG-02）：进程新建（开机广播/服务拉起/更新后启动）时按
     * [RecoveryPolicy] 决策是否重连。尊重一键暂停与设备撤销；前台服务
     * 能否拉起由系统决定（NodeService.start 内部降级，不绕过）。
     * @param why 触发来源（boot/service_restart/process_start），仅日志用
     * @return true=已发起重连
     */
    @Synchronized
    fun recoverIfNeeded(why: String): Boolean {
        val prefs = appContext.getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE)
        val wasConnected = prefs.getBoolean(KEY_WAS_CONNECTED, false)
        val revoked = prefs.getBoolean(KEY_REVOKED, false)
        val paused = isAllPaused(appContext)
        if (!RecoveryPolicy.shouldRecover(wasConnected, paused, revoked)) {
            Log.i(TAG, "恢复评估[$why]：不恢复（wasConnected=$wasConnected paused=$paused revoked=$revoked）")
            return false
        }
        val url = prefs.getString(KEY_GATEWAY_URL, null)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "恢复评估[$why]：缺少已存网关地址，放弃")
            return false
        }
        val caps = prefs.getString(KEY_GATEWAY_CAPS, null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: WssClient.V1_CAPABILITIES.toList()
        Log.i(TAG, "恢复评估[$why]：发起重连 url=$url")
        if (com.wangfanlab.whale.transport.TransportBridge.isBgServiceEnabled(appContext)) {
            NodeService.start(appContext)
        }
        connect(url, caps, detectAppVersion())
        return true
    }

    // ---- 网络恢复回调（BG-02）：默认网络可用时立即重连（重置退避） ----

    @Volatile
    private var networkAvailable: Boolean = true

    private var networkCallbackRegistered = false

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            networkAvailable = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    networkAvailable = true
                    client.reconnectNow()
                }

                override fun onLost(network: android.net.Network) {
                    networkAvailable = cm.activeNetwork != null
                }
            })
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "网络回调注册失败（降级为纯心跳看门狗）: ${t.message}")
        }
    }

    /**
     * kill switch：断开连接并销毁 WSS 客户端（执行器注册表随之失效）。
     * 幂等；之后 [connect] 会重建客户端。
     */
    @Synchronized
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        client.destroy()
        emitEvent(mapOf("kind" to "state", "state" to "disconnected"))
        Log.i(TAG, "NodeRuntime 已关闭（kill switch）")
    }

    val isShutdown: Boolean get() = shutdown

    // ------------------------------------------------------------- 连接与状态

    fun connect(url: String, capabilities: List<String>, appVersion: String) {
        if (shutdown) {
            // kill switch 后允许显式重连（用户主动操作）
            shutdown = false
            client = newClient()
        }
        // 持久化恢复上下文（BG-02）：重启/进程重建后可按策略恢复。
        appContext.getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAS_CONNECTED, true)
            .putBoolean(KEY_REVOKED, false)
            .putString(KEY_GATEWAY_URL, url)
            .putString(KEY_GATEWAY_CAPS, capabilities.joinToString(","))
            .apply()
        client.connect(url, capabilities, appVersion)
    }

    fun disconnect() {
        // 用户主动断开：清除恢复标志，重启/开机不再自动重连。
        appContext.getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAS_CONNECTED, false)
            .apply()
        client.disconnect()
    }

    fun setStatus(status: String) {
        deviceStatus = status
        if (status == "paused") {
            val dropped = queue.purgeDropOnPause()
            if (dropped > 0) Log.i(TAG, "进入 paused，丢弃 drop_on_pause 事件 $dropped 条")
        }
    }

    fun getStateInfo(): Map<String, Any?> {
        val info = LinkedHashMap<String, Any?>(client.getStateInfo())
        info["runtime_started"] = started
        info["node_service_running"] = NodeService.isRunning
        // BG-02 诊断：网络可用性、电池优化白名单状态、恢复上下文（均脱敏）。
        info["network_available"] = networkAvailable
        val prefs = appContext.getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE)
        info["was_connected"] = prefs.getBoolean(KEY_WAS_CONNECTED, false)
        info["battery_opt_ignored"] = isBatteryOptimizationIgnored()
        return info
    }

    /** 电池优化白名单状态（仅观测展示，不申请、不绕过）。 */
    private fun isBatteryOptimizationIgnored(): Boolean = try {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    } catch (t: Throwable) {
        false
    }

    private fun detectAppVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    // ------------------------------------------------------------- 上行 API（代理到唯一 client）

    fun sendSensorEvent(
        kind: String,
        occurredAt: Long,
        privacyClass: String,
        summary: String,
        evidenceRef: String? = null,
        schemaVersion: String = "1.0",
        dropOnPause: Boolean = false,
        eventId: String = Ids.newEventId(),
    ): String = client.sendSensorEvent(
        kind, occurredAt, privacyClass, summary, evidenceRef, schemaVersion, dropOnPause, eventId,
    )

    fun sendActionReceipt(
        actionId: String,
        state: String,
        executedAt: Long? = null,
        errorCode: String? = null,
    ) = client.sendActionReceipt(actionId, state, executedAt, errorCode)

    fun sendSensorResult(
        requestId: String,
        status: String,
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    ) = client.sendSensorResult(requestId, status, data, truncated, source)

    // ------------------------------------------------------------- UI 绑定

    fun addUiListener(listener: UiListener) {
        val wrapper = UiEventHub.Listener { event -> listener.onEvent(event) }
        uiListenerWrappers[listener] = wrapper
        uiEventHub.add(wrapper)
    }

    fun removeUiListener(listener: UiListener) {
        // UiEventHub 按引用移除；UiListener 包装见 add 的映射表
        uiListenerWrappers[listener]?.let { uiEventHub.remove(it) }
        uiListenerWrappers.remove(listener)
    }

    /** add 时建立的 UiListener → Hub 包装映射（remove 按原包装注销）。 */
    private val uiListenerWrappers =
        java.util.concurrent.ConcurrentHashMap<UiListener, UiEventHub.Listener>()

    /** UI 是否已绑定（诊断用）。 */
    val hasUiListener: Boolean get() = uiEventHub.hasListeners

    fun setSensorQueryHandler(handler: SensorQueryHandler?) {
        sensorQueryHandler = handler
        Log.i(TAG, "sensor.query Dart 委托: ${handler != null}")
    }

    private fun emitEvent(event: Map<String, Any?>) {
        uiEventHub.emit(event)
    }

    // ------------------------------------------------------------- WssClient.Listener（主线程回调）

    override fun onStateChanged(state: WssClient.State, info: Map<String, Any?>) {
        val event = HashMap<String, Any?>(info)
        event["kind"] = "state"
        event["state"] = WssClient.stateName(state)
        emitEvent(event)
    }

    override fun onActionRequest(payload: Map<String, Any?>) {
        val event = HashMap<String, Any?>()
        event["kind"] = "action_request"
        event.putAll(payload)
        emitEvent(event)
    }

    override fun onActionTerminal(
        actionId: String,
        capability: String,
        state: String,
        errorCode: String?,
    ) {
        emitEvent(
            mapOf(
                "kind" to "action_receipt",
                "action_id" to actionId,
                "capability" to capability,
                "state" to state,
                "error_code" to errorCode,
            )
        )
    }

    /**
     * sensor.query 处理器链（BG-01：UI 不在时有明确终态，不投递到不存在的 UI）：
     * 1. 全局暂停 → paused；2. Dart 委托接管 → 转发；3. 否则 false →
     * WssClient fail-closed 回 unavailable。
     */
    /** SM-03：内置数据源时 screen.* 查询走本机 CaptureStore（无 UI 也可答）。 */
    private val nativeScreenQuery by lazy {
        com.wangfanlab.whale.modules.capture.NativeScreenQueryHandler(appContext, captureStore) { requestId, status, data, truncated, source ->
            client.sendSensorResult(requestId, status, data, truncated, source)
        }
    }

    override fun onSensorQuery(payload: Map<String, Any?>): Boolean {
        val requestId = payload["request_id"] as? String ?: return false
        // SM-03：数据源为 builtin 且操作受支持 → 原生直接回答（先于 Dart 委托）
        val operation = payload["operation"] as? String ?: ""
        if (!isAllPaused(appContext) &&
            com.wangfanlab.whale.modules.capture.CaptureModule.dataSource(appContext) ==
            com.wangfanlab.whale.modules.capture.CaptureModule.SOURCE_BUILTIN &&
            com.wangfanlab.whale.modules.capture.NativeScreenQueryHandler.supports(operation)
        ) {
            return nativeScreenQuery.handle(payload)
        }
        return when (SensorQueryDispatch.decide(
            allPaused = isAllPaused(appContext),
            hasDelegate = sensorQueryHandler != null,
        )) {
            SensorQueryDispatch.Decision.PAUSED -> {
                Log.i(TAG, "全局暂停中，sensor.query 回 paused request_id=$requestId")
                client.sendSensorResult(requestId, "paused", emptyList(), false, MCP_RESULT_SOURCE)
                true
            }
            SensorQueryDispatch.Decision.DELEGATE -> try {
                sensorQueryHandler!!.onSensorQuery(payload)
            } catch (t: Throwable) {
                Log.e(TAG, "sensor.query 处理器异常 request_id=$requestId", t)
                false
            }
            SensorQueryDispatch.Decision.UNAVAILABLE -> false
        }
    }

    override fun onCallInvite(payload: Map<String, Any?>) {
        voipModule.onCallInvite(payload)
        emitEvent(
            mapOf(
                "kind" to "call_invite",
                "session_id" to payload["session_id"],
                "expires_at" to payload["expires_at"],
            )
        )
    }

    override fun onCallHangup(payload: Map<String, Any?>) {
        voipModule.onCallHangup(payload)
        emitEvent(
            mapOf(
                "kind" to "call_hangup",
                "session_id" to payload["session_id"],
                "reason" to payload["reason"],
            )
        )
    }

    /** SEC-03：无原生执行器的 action 只有在 UI 在时才转发 Dart，否则客户端回 unavailable。 */
    override fun hasUiActionHandler(): Boolean = uiEventHub.hasListeners

    override fun onProtocolError(code: String, message: String, refId: String) {
        // 撤销/认证失败为终态：标记 revoked，清除恢复上下文（BG-02 不擅自重连）。
        if (code == "device_revoked" || code == "auth_failed") {
            appContext.getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REVOKED, code == "device_revoked")
                .putBoolean(KEY_WAS_CONNECTED, false)
                .apply()
        }
        emitEvent(
            mapOf(
                "kind" to "error",
                "code" to code,
                "message" to message,
                "ref_id" to refId,
            )
        )
    }

    companion object {
        private const val TAG = "WhaleNodeRuntime"
        private const val MCP_RESULT_SOURCE = "screenmemo.local"

        /** 节点恢复上下文（BG-02）：重连所需的最小持久化状态。 */
        private const val NODE_PREFS_NAME = "whale_node"
        private const val KEY_WAS_CONNECTED = "was_connected"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_CAPS = "gateway_caps"
        private const val KEY_REVOKED = "revoked"

        /** 配对/重配对成功后调用：清除撤销标记。 */
        fun clearRevoked(context: Context) {
            context.applicationContext
                .getSharedPreferences(NODE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REVOKED, false).apply()
        }

        private val created = AtomicBoolean(false)

        @Volatile
        private var instance: NodeRuntime? = null

        /**
         * 获取进程内唯一运行时（首次调用创建）。二次创建直接抛错——
         * 同一设备身份绝不允许两个心跳发送者。
         */
        fun get(context: Context): NodeRuntime {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return@synchronized it }
                check(created.compareAndSet(false, true)) {
                    "NodeRuntime 已创建过，禁止第二实例"
                }
                NodeRuntime(
                    context.applicationContext,
                    Handler(Looper.getMainLooper()),
                ).also { instance = it }
            }
        }

        /** 已创建则返回，否则 null（Service/系统组件避免隐式创建）。 */
        fun peek(): NodeRuntime? = instance

        /** 全局暂停键（whale_modules/all_paused，A1 一键暂停；默认 false）。 */
        const val KEY_ALL_PAUSED = "all_paused"

        /** 全局暂停标志读取（执行器校验链前置检查）。 */
        fun isAllPaused(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALL_PAUSED, false)

        /** 仅供单元测试重置单例（不要在生产代码调用）。 */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance = null
            created.set(false)
        }
    }
}
