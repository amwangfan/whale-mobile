package com.wangfanlab.whale.transport.v2

import android.app.Activity
import android.content.Context
import android.util.Log
import com.wangfanlab.whale.CapabilityRegistry
import com.wangfanlab.whale.modules.capture.CaptureModule
import com.wangfanlab.whale.modules.glasses.GlassesModule
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.runtime.v2.NodeRuntimeV2
import com.wangfanlab.whale.runtime.v2.NodeServiceV2
import com.wangfanlab.whale.transport.BridgeDataQueries
import com.wangfanlab.whale.transport.TransportBridge
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 桥 V2：MethodChannel `whale/transport_v2` +
 * EventChannel `whale/transport_v2/events`。
 *
 * 与 v1 [TransportBridge] 同纪律：本桥不拥有运行时，只把 MethodChannel 调用
 * 代理到 [NodeRuntimeV2]（Application 单例），并把运行时事件经 EventChannel
 * 转发给 Dart；Activity/Engine 销毁 = [dispose] 注销监听与 sensor.query
 * 委托，连接与执行器继续存活。
 *
 * 方法面（控制面无 Dart 消费者先行，命名对齐 [TransportV2Api] 计划方法面）：
 * - connectV2({url, app_version?}) → {device_id, public_key_hex}
 *     （capabilities=CapabilityRegistry.current、app_flavor=BuildConfig.FLAVOR、
 *     features 实测，均由 NodeRuntimeV2.buildHelloConfig 组装；前台调用按开关
 *     启动 NodeServiceV2 保持后台连接）
 * - disconnectV2() → null（同时停止前台服务）
 * - getStateV2() → WssClientV2.getStateInfo + runtime_started/node_service_running 等
 * - getIdentityV2() → {device_id, public_key_hex}
 * - getPairingInfoV2() → 配对码展示数据（device_id/public_key_hex/app_flavor/
 *     protocol_version/capabilities，§1 设备上屏配对）
 * - setStatusV2({status: online|connected_only|paused}) → true
 * - sendExperienceEvent({kind, occurred_at?, source?, summary, privacy_class?,
 *     evidence_ref?, task_id?, run_id?, extras?, drop_on_pause?, event_id?}) → {event_id}
 * - sendActionReceiptV2({action_id, status, error_code?, result_summary?,
 *     output_ref?, verification_status?, verification_summary?, provider_ref?}) → true
 *     （status 为五终态之一；first-terminal-wins 由 ReceiptDispatcherV2 保证）
 * - sendNotificationReceipt({event_id, notification_id, status, received_at?}) → true
 * - sendNotificationAction({event_id, notification_id, action_id}) → true
 * - sendSensorResultV2({request_id, status, data[], truncated, source}) → true
 * - setSensorQueryDelegateV2({enabled}) → true（Dart 声明接管 sensor.query）
 * - setModuleEnabled({module, enabled}) / getModuleStates()：模块开关读写
 *     （与 v1 同一 whale_modules 存储，UI 无感切换）
 * - setAllPaused({paused}) / isAllPaused()：全局暂停（与 v1 同一 all_paused 键）
 * - setBgServiceEnabled({enabled}) / isBgServiceEnabled()（与 v1 同一键）
 * - 本地数据面（与 `whale/transport` 通道同一约定，经 [BridgeDataQueries]）：
 *   notificationInbox.history/markRead/markAllRead/count、capture.listSegments/
 *   listCaptures/search/getEvidencePath/storageStats/purgeBefore/compressImagesBefore、
 *   favorites.list/add/remove、backup.exportZip/importZip、getLaunchRoute
 *
 * EventChannel（原生 → Dart）：
 * - {kind: "state", state, ...getStateV2 字段}
 * - {kind: "action_request", action_id, capability, arguments, ...}
 * - {kind: "action_receipt", action_id, capability, status, error_code?}
 * - {kind: "sensor_query", request_id, operation, filters, max_items, deadline}
 * - {kind: "notification_event" / "notification_revoke", ...payload}
 * - {kind: "recording_control", run_id, op, options?}
 * - {kind: "confirm_request", challenge_id, channel, scopes, ...}
 * - {kind: "error", code, message, ref_id}
 *
 * 所有回调在主线程执行（Flutter channel 要求）。
 */
class TransportBridgeV2(
    context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private val appContext = context.applicationContext

    /** 仅用于运行时权限申请与 getLaunchRoute（读启动 intent extras）。 */
    private val activity: Activity? = context as? Activity
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)

    /** v2 运行时（Application 单例；UI 销毁不随之销毁）。 */
    private val runtime: NodeRuntimeV2 = NodeRuntimeV2.get(context)

    /** 本地数据查询面（与 v1 桥共享同一实现与通道约定）。 */
    private val dataQueries = BridgeDataQueries(
        context = appContext,
        activityProvider = { activity },
        storeProvider = { runtime.captureStore },
        inboxProvider = { NodeRuntimeV2.peek()?.inbox },
    )

    // WssClientV2 executor 线程经 NodeRuntimeV2 读 eventSink（onSensorQuery 同步分发），需 @Volatile。
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    /** Dart 侧是否接管 sensor.query。 */
    @Volatile
    private var sensorQueryDelegateEnabled: Boolean = false

    /** 运行时 → EventChannel 的 UI 监听（dispose 时注销）。 */
    private val uiListener = NodeRuntimeV2.UiListener { event ->
        eventSink?.success(event)
    }

    /** 已注册到运行时的 sensor.query 委托（dispose 时清除，恢复 fail-closed）。 */
    private val sensorQueryHandler = NodeRuntimeV2.SensorQueryHandler { payload ->
        onSensorQueryFromRuntime(payload)
    }

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        runtime.addUiListener(uiListener)
    }

    /**
     * UI 解绑：注销监听与 sensor.query 委托。**不**断开连接、不销毁运行时——
     * 连接生命周期归 NodeRuntimeV2/NodeServiceV2。
     */
    fun dispose() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        if (sensorQueryDelegateEnabled) {
            sensorQueryDelegateEnabled = false
            runtime.setSensorQueryHandler(null)
        }
        runtime.removeUiListener(uiListener)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            // 本地数据查询面（notificationInbox.*/capture.*/favorites.*/backup.*/getLaunchRoute）
            if (dataQueries.supports(call.method)) {
                dataQueries.handle(call, result)
                return
            }
            when (call.method) {
                "connectV2" -> {
                    val url = call.argument<String>("url")
                        ?: return result.error("bad_args", "missing url", null)
                    val appVersion = call.argument<String>("app_version")
                    if (appVersion.isNullOrBlank()) {
                        runtime.connect(url)
                    } else {
                        runtime.connect(url, appVersion)
                    }
                    // 前台发起的连接：按用户开关启动前台服务保持后台连接。
                    if (TransportBridge.isBgServiceEnabled(appContext)) {
                        NodeServiceV2.start(appContext)
                    }
                    result.success(
                        mapOf(
                            "device_id" to runtime.identity.getDeviceId(),
                            "public_key_hex" to runtime.identity.getPublicKeyHex(),
                        ),
                    )
                }

                "disconnectV2" -> {
                    runtime.disconnect()
                    NodeServiceV2.stop(appContext)
                    result.success(null)
                }

                "getStateV2" -> result.success(runtime.getStateInfo())

                "getIdentityV2" -> result.success(
                    mapOf(
                        "device_id" to runtime.identity.getDeviceId(),
                        "public_key_hex" to runtime.identity.getPublicKeyHex(),
                    ),
                )

                /** 配对码展示数据（§1：设备上屏 → core 可信管理端登记）。 */
                "getPairingInfoV2" -> result.success(runtime.getPairingInfo())

                "setStatusV2" -> {
                    val status = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    if (status !in setOf("online", "connected_only", "paused")) {
                        return result.error("bad_args", "unknown status $status", null)
                    }
                    runtime.setStatus(status)
                    result.success(true)
                }

                "sendExperienceEvent" -> {
                    val kind = call.argument<String>("kind")
                        ?: return result.error("bad_args", "missing kind", null)
                    val summary = call.argument<String>("summary")
                        ?: return result.error("bad_args", "missing summary", null)
                    @Suppress("UNCHECKED_CAST")
                    val extras = call.argument<Map<String, Any?>>("extras")
                    val eventId = runtime.sendExperienceEvent(
                        kind = kind,
                        occurredAt = call.argument<Number>("occurred_at")?.toLong()
                            ?: System.currentTimeMillis(),
                        source = call.argument<String>("source") ?: "flutter",
                        summary = summary,
                        privacyClass = call.argument<String>("privacy_class") ?: "owner_private",
                        evidenceRef = call.argument<String>("evidence_ref"),
                        taskId = call.argument<String>("task_id"),
                        runId = call.argument<String>("run_id"),
                        extras = extras,
                        dropOnPause = call.argument<Boolean>("drop_on_pause") ?: false,
                        eventId = call.argument<String>("event_id") ?: IdsV2.newEventId(),
                    )
                    result.success(mapOf("event_id" to eventId))
                }

                "sendActionReceiptV2" -> {
                    val actionId = call.argument<String>("action_id")
                        ?: return result.error("bad_args", "missing action_id", null)
                    val statusWire = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    val status = ReceiptStatus.of(statusWire)
                    if (status == null || !status.terminal) {
                        return result.error(
                            "bad_args", "status 须为五终态之一: $statusWire", null,
                        )
                    }
                    runtime.sendActionReceipt(
                        actionId = actionId,
                        status = status,
                        errorCode = call.argument<String>("error_code"),
                        resultSummary = call.argument<String>("result_summary"),
                        outputRef = call.argument<String>("output_ref"),
                        verificationStatus = call.argument<String>("verification_status"),
                        verificationSummary = call.argument<String>("verification_summary"),
                        providerRef = call.argument<String>("provider_ref"),
                    )
                    result.success(true)
                }

                "sendNotificationReceipt" -> {
                    val eventId = call.argument<String>("event_id")
                        ?: return result.error("bad_args", "missing event_id", null)
                    val notificationId = call.argument<String>("notification_id")
                        ?: return result.error("bad_args", "missing notification_id", null)
                    val status = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    if (status !in WssClientV2.NOTIFICATION_RECEIPT_STATUSES) {
                        return result.error("bad_args", "unknown status $status", null)
                    }
                    runtime.sendNotificationReceipt(eventId, notificationId, status)
                    result.success(true)
                }

                "sendNotificationAction" -> {
                    val eventId = call.argument<String>("event_id")
                        ?: return result.error("bad_args", "missing event_id", null)
                    val notificationId = call.argument<String>("notification_id")
                        ?: return result.error("bad_args", "missing notification_id", null)
                    val actionId = call.argument<String>("action_id")
                        ?: return result.error("bad_args", "missing action_id", null)
                    runtime.sendNotificationAction(eventId, notificationId, actionId)
                    result.success(true)
                }

                "sendSensorResultV2" -> {
                    val requestId = call.argument<String>("request_id")
                        ?: return result.error("bad_args", "missing request_id", null)
                    val status = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    if (status !in setOf("ok", "denied", "unavailable", "error", "paused")) {
                        return result.error("bad_args", "unknown status $status", null)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val data = call.argument<List<Any?>>("data") ?: emptyList()
                    runtime.sendSensorResult(
                        requestId = requestId,
                        status = status,
                        data = data,
                        truncated = call.argument<Boolean>("truncated") ?: false,
                        source = call.argument<String>("source") ?: "screenmemo.local",
                    )
                    result.success(true)
                }

                "setSensorQueryDelegateV2" -> {
                    sensorQueryDelegateEnabled = call.argument<Boolean>("enabled") ?: false
                    runtime.setSensorQueryHandler(
                        if (sensorQueryDelegateEnabled) sensorQueryHandler else null,
                    )
                    Log.i(TAG, "v2 sensor.query Dart 接管开关: $sensorQueryDelegateEnabled")
                    result.success(true)
                }

                // ---- 模块开关（与 v1 同一 whale_modules 存储，两桥读写等价） ----

                "setModuleEnabled" -> {
                    val module = call.argument<String>("module")
                    val enabled = call.argument<Boolean>("enabled")
                    if (module == null || enabled == null || !MODULE_NAME_REGEX.matches(module)) {
                        return result.error("bad_args", "非法 module/enabled 参数", null)
                    }
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("${module}_enabled", enabled).apply()
                    result.success(true)
                }

                "getModuleStates" -> {
                    val prefs = appContext.getSharedPreferences(
                        NotificationModule.PREFS_NAME, Context.MODE_PRIVATE,
                    )
                    result.success(
                        mapOf(
                            "notification_post" to
                                prefs.getBoolean(NotificationModule.KEY_ENABLED, true),
                            "voip" to prefs.getBoolean(VoipModule.KEY_ENABLED, false),
                            "notification_read" to
                                prefs.getBoolean(NotificationReadModule.KEY_ENABLED, false),
                            "safe_action" to
                                prefs.getBoolean(SafeActionModule.KEY_ENABLED, false),
                            "glasses" to prefs.getBoolean(GlassesModule.KEY_ENABLED, false),
                            "capture" to CaptureModule.isEnabled(appContext),
                        ),
                    )
                }

                // ---- 全局暂停（A1；与 v1 同一 whale_modules/all_paused 键） ----

                "setAllPaused" -> {
                    val paused = call.argument<Boolean>("paused")
                        ?: return result.error("bad_args", "missing paused", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PauseFlags.KEY_ALL_PAUSED, paused).apply()
                    Log.i(TAG, "全局暂停(v2 桥): $paused")
                    result.success(true)
                }

                "isAllPaused" -> result.success(PauseFlags.isAllPaused(appContext))

                // ---- 后台前台服务开关（与 v1 同一键；v2 面停/起 v2 服务） ----

                "setBgServiceEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(TransportBridge.KEY_BG_SERVICE_ENABLED, enabled).apply()
                    if (!enabled) NodeServiceV2.stop(appContext)
                    result.success(true)
                }

                "isBgServiceEnabled" ->
                    result.success(TransportBridge.isBgServiceEnabled(appContext))

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("internal", e.message, null)
        }
    }

    // ---- EventChannel.StreamHandler ----

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        // UiEventHub 已在 add 时补发最近状态（v2 桥不重复补发）
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    // ---- sensor.query 委托（NodeRuntimeV2 回调；WssClientV2 executor 线程） ----

    /**
     * 转发 sensor.query 到 Dart（异步）。返回 true 后客户端不再自动回包，
     * Dart 侧必须经 sendSensorResultV2 回复；若事件在投递前丢失（Dart 退订），
     * 兜底 fail-closed 回 unavailable，避免云端空等到 deadline。
     */
    private fun onSensorQueryFromRuntime(payload: Map<String, Any?>): Boolean {
        val requestId = payload["request_id"] as? String ?: return false
        if (eventSink == null) return false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val current = eventSink
            if (current != null) {
                val event = HashMap<String, Any?>()
                event["kind"] = "sensor_query"
                event.putAll(payload)
                current.success(event)
            } else {
                Log.w(TAG, "v2 sensor.query 投递时 Dart 已退订，回 unavailable request_id=$requestId")
                runtime.sendSensorResult(
                    requestId, "unavailable", emptyList(), false, "screenmemo.local",
                )
            }
        }
        return true
    }

    companion object {
        private const val TAG = "TransportBridgeV2"
        const val METHOD_CHANNEL = "whale/transport_v2"
        const val EVENT_CHANNEL = "whale/transport_v2/events"

        /** 模块开关名白名单格式（防任意键写入 whale_modules）。 */
        private val MODULE_NAME_REGEX = Regex("^[a-z0-9_]{1,32}$")

        /** 控制面方法名（数据面见 [BridgeDataQueries.HANDLED_METHODS]；桥存在性测试用）。 */
        val CONTROL_METHODS: Set<String> = setOf(
            "connectV2", "disconnectV2", "getStateV2", "getIdentityV2",
            "getPairingInfoV2", "setStatusV2", "sendExperienceEvent",
            "sendActionReceiptV2", "sendNotificationReceipt", "sendNotificationAction",
            "sendSensorResultV2", "setSensorQueryDelegateV2",
            "setModuleEnabled", "getModuleStates",
            "setAllPaused", "isAllPaused",
            "setBgServiceEnabled", "isBgServiceEnabled",
        )
    }
}
