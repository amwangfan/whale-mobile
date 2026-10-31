package com.wangfanlab.whale.transport

import android.app.Activity
import android.content.Context
import android.util.Log
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.safeaction.SafeActionValidator
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.runtime.NodeRuntime
import com.wangfanlab.whale.runtime.NodeService
import com.wangfanlab.whale.voip.BridgePrefs
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 桥（BG-01 瘦身为 UI 客户端）：MethodChannel `whale/transport` +
 * EventChannel `whale/transport/events`。
 *
 * **本类不再拥有运行时**：WssClient/DeviceIdentity/OfflineQueue/模块执行器
 * 由 [NodeRuntime]（Application 单例）持有；本桥只把 MethodChannel 调用
 * 代理到运行时，并把运行时事件经 EventChannel 转发给 Dart。
 * Activity/Engine 销毁 = [dispose] 注销 UI 监听与 sensor.query 委托，
 * 连接与执行器继续存活；UI 重建后 [addUiListener] 补发最近状态。
 *
 * MethodChannel（Dart → 原生，返回值见各分支）：
 * - connect({url, capabilities?, app_version?}) → {device_id, public_key_hex}
 *     （前台调用：同时启动 NodeService 前台服务保持后台连接，常驻通知可停止）
 * - disconnect() → null（同时停止前台服务）
 * - sendSensorEvent({kind, occurred_at?, privacy_class?, summary, evidence_ref?,
 *     schema_version?, drop_on_pause?, event_id?}) → {event_id}
 * - sendActionReceipt({action_id, state, executed_at?, error_code?}) → true
 * - setStatus({status: online|connected_only|paused}) → true（paused 时清除 drop_on_pause 队列）
 * - getState() → {state, device_id, ..., runtime_started, node_service_running}
 * - getIdentity() → {device_id, public_key_hex}（配对时把 public_key_hex 报给 core 登记）
 * - sendSensorResult({request_id, status, data[], truncated, source}) → true
 * - setSensorQueryDelegate({enabled}) → true：Dart 声明接管 sensor.query；
 *     未声明或 UI 销毁时 NodeRuntime fail-closed（立即回 unavailable）
 * - setMcpToken({token}) / getMcpToken()：ScreenMemo MCP Bearer token 的 Keystore
 *     加密存取（AES/GCM 主密钥与设备身份同款，密文落 SharedPreferences whale_mcp）
 * - setModuleEnabled({module, enabled}) / getModuleStates()：模块开关通用读写
 *     （SharedPreferences whale_modules，键 `<module>_enabled`，默认见各模块）
 * - setAllPaused({paused}) / isAllPaused()：全局暂停标志（whale_modules/all_paused，
 *     A1 一键暂停）。暂停期间执行器校验链前置拦截：action 回 failed/unavailable，
 *     sensor.query 直接回 status="paused" 且不再分发 Dart。
 * - setBgServiceEnabled({enabled}) / isBgServiceEnabled()：后台前台服务开关
 *     （whale_modules/bg_service_enabled，默认 true；关闭后 connect 不启动 FGS）
 * - getNotificationPermissionStatus() / requestNotificationPermission()
 * - getVoipPermissionStatus() / requestVoipPermissions()
 * - getVoipBridgeConfig() / setVoipBridgeConfig({host, port})
 * - getNotificationReadPermissionStatus() / openNotificationListenerSettings()
 * - getNotificationReadAllowedApps() / setNotificationReadAllowedApps({apps})
 * - getListenableApps()：白名单多选页数据源（带 LAUNCHER 入口的已安装 App）
 *
 * EventChannel（原生 → Dart），事件为 Map（由 NodeRuntime 扇出）：
 * - {kind: "state", state, ...getState 字段}
 * - {kind: "action_request", action_id, capability, args, authorized_scope, ...}
 * - {kind: "action_receipt", action_id, capability, state, error_code?}
 * - {kind: "sensor_query", request_id, operation, filters, max_items, deadline}
 * - {kind: "call_invite" / "call_hangup", session_id, ...}
 * - {kind: "error", code, message, ref_id}
 *
 * 所有回调在主线程执行（Flutter channel 要求）。
 */
class TransportBridge(
    context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private val appContext = context.applicationContext

    /** 仅用于运行时权限申请（requestPermissions 需 Activity）；bridge 与 Activity 同生命周期。 */
    private val activity: Activity? = context as? Activity
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)

    /** 运行时（Application 单例；UI 销毁不随之销毁）。 */
    private val runtime: NodeRuntime = NodeRuntime.get(context)

    // WssClient 在 executor 线程上经 NodeRuntime 读 eventSink（onSensorQuery 同步分发），需 @Volatile。
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    /** Dart 侧是否接管 sensor.query（screenmemo 模块）。 */
    @Volatile
    private var sensorQueryDelegateEnabled: Boolean = false

    /** 运行时 → EventChannel 的 UI 监听（dispose 时注销）。 */
    private val uiListener = NodeRuntime.UiListener { event ->
        if ("state" == event["kind"]) lastStateEvent = event
        eventSink?.success(event)
    }

    /** 已注册到运行时的 sensor.query 委托（dispose 时清除，恢复 fail-closed）。 */
    private val sensorQueryHandler = NodeRuntime.SensorQueryHandler { payload ->
        onSensorQueryFromRuntime(payload)
    }

    private var lastStateEvent: Map<String, Any?>? = null

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        runtime.addUiListener(uiListener)
    }

    /**
     * UI 解绑：注销监听与 sensor.query 委托。**不**断开连接、不销毁运行时——
     * 连接生命周期归 NodeRuntime/NodeService。
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
            when (call.method) {
                "connect" -> {
                    val url = call.argument<String>("url")
                        ?: return result.error("bad_args", "missing url", null)
                    @Suppress("UNCHECKED_CAST")
                    val caps = call.argument<List<String>>("capabilities")
                        ?: WssClient.V1_CAPABILITIES.toList()
                    val appVersion = call.argument<String>("app_version") ?: detectAppVersion()
                    runtime.connect(url, caps, appVersion)
                    // 前台发起的连接：按用户开关启动前台服务保持后台连接（Android 8+ 合规入口）。
                    if (isBgServiceEnabled(appContext)) {
                        NodeService.start(appContext)
                    }
                    result.success(
                        mapOf(
                            "device_id" to runtime.identity.getDeviceId(),
                            "public_key_hex" to runtime.identity.getPublicKeyHex(),
                        )
                    )
                }

                "disconnect" -> {
                    runtime.disconnect()
                    NodeService.stop(appContext)
                    result.success(null)
                }

                "sendSensorEvent" -> {
                    val kind = call.argument<String>("kind")
                        ?: return result.error("bad_args", "missing kind", null)
                    val summary = call.argument<String>("summary")
                        ?: return result.error("bad_args", "missing summary", null)
                    val eventId = runtime.sendSensorEvent(
                        kind = kind,
                        occurredAt = call.argument<Number>("occurred_at")?.toLong()
                            ?: System.currentTimeMillis(),
                        privacyClass = call.argument<String>("privacy_class") ?: "owner_private",
                        summary = summary,
                        evidenceRef = call.argument<String>("evidence_ref"),
                        schemaVersion = call.argument<String>("schema_version") ?: "1.0",
                        dropOnPause = call.argument<Boolean>("drop_on_pause") ?: false,
                        eventId = call.argument<String>("event_id") ?: Ids.newEventId(),
                    )
                    result.success(mapOf("event_id" to eventId))
                }

                "sendActionReceipt" -> {
                    val actionId = call.argument<String>("action_id")
                        ?: return result.error("bad_args", "missing action_id", null)
                    val state = call.argument<String>("state")
                        ?: return result.error("bad_args", "missing state", null)
                    runtime.sendActionReceipt(
                        actionId = actionId,
                        state = state,
                        executedAt = call.argument<Number>("executed_at")?.toLong(),
                        errorCode = call.argument<String>("error_code"),
                    )
                    result.success(true)
                }

                "setStatus" -> {
                    val status = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    if (status !in setOf("online", "connected_only", "paused")) {
                        return result.error("bad_args", "unknown status $status", null)
                    }
                    runtime.setStatus(status)
                    result.success(true)
                }

                "sendSensorResult" -> {
                    val requestId = call.argument<String>("request_id")
                        ?: return result.error("bad_args", "missing request_id", null)
                    val status = call.argument<String>("status")
                        ?: return result.error("bad_args", "missing status", null)
                    // paused：A1 全局暂停期间 sensor.query 的终态回包（原生分发链拦截使用）。
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
                        source = call.argument<String>("source") ?: MCP_RESULT_SOURCE,
                    )
                    result.success(true)
                }

                "setSensorQueryDelegate" -> {
                    sensorQueryDelegateEnabled = call.argument<Boolean>("enabled") ?: false
                    runtime.setSensorQueryHandler(
                        if (sensorQueryDelegateEnabled) sensorQueryHandler else null,
                    )
                    Log.i(TAG, "sensor.query Dart 接管开关: $sensorQueryDelegateEnabled")
                    result.success(true)
                }

                "setMcpToken" -> {
                    // 空白 token 视为清除；密文落盘，token 不记日志。
                    val token = call.argument<String>("token")?.trim().orEmpty()
                    val prefs = appContext.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
                    if (token.isEmpty()) {
                        prefs.edit().remove(KEY_MCP_TOKEN_BLOB).apply()
                    } else {
                        prefs.edit()
                            .putString(KEY_MCP_TOKEN_BLOB, runtime.identity.encryptString(token))
                            .apply()
                    }
                    result.success(true)
                }

                "getMcpToken" -> {
                    val blob = appContext
                        .getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_MCP_TOKEN_BLOB, null)
                    val token = blob?.let {
                        try {
                            runtime.identity.decryptString(it)
                        } catch (e: Exception) {
                            Log.w(TAG, "MCP token 解密失败（主密钥可能已失效），按未配置处理", e)
                            null
                        }
                    }
                    result.success(token)
                }

                "getState" -> result.success(runtime.getStateInfo())

                "getIdentity" -> result.success(
                    mapOf(
                        "device_id" to runtime.identity.getDeviceId(),
                        "public_key_hex" to runtime.identity.getPublicKeyHex(),
                    )
                ).also {
                    // 配对引导：debug 包把设备身份打到 logcat（公钥非秘密；release 不打）。
                    val dbg = (appContext.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (dbg) {
                        Log.i("WhaleBridge",
                            "device_id=${runtime.identity.getDeviceId()} " +
                                "pubkey=${runtime.identity.getPublicKeyHex()}")
                    }
                }

                // ---- 模块开关（通用；whale_modules/<module>_enabled，默认 true）----

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
                    // 模块开关登记（默认：notification_post true；其余 false，需显式开启）。
                    val prefs = appContext.getSharedPreferences(
                        NotificationModule.PREFS_NAME, Context.MODE_PRIVATE,
                    )
                    result.success(
                        mapOf(
                            "notification_post" to
                                prefs.getBoolean(NotificationModule.KEY_ENABLED, true),
                            // voip 默认 false（B4：首次需显式开启）
                            "voip" to
                                prefs.getBoolean(VoipModule.KEY_ENABLED, false),
                            // 通知监听默认 false（B3：独立可选，一个 App 都不读）
                            "notification_read" to
                                prefs.getBoolean(NotificationReadModule.KEY_ENABLED, false),
                            // 受控设备动作默认 false（B3）
                            "safe_action" to
                                prefs.getBoolean(SafeActionModule.KEY_ENABLED, false),
                        )
                    )
                }

                // ---- 全局暂停（A1；whale_modules/all_paused）----

                "setAllPaused" -> {
                    val paused = call.argument<Boolean>("paused")
                        ?: return result.error("bad_args", "missing paused", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(NodeRuntime.KEY_ALL_PAUSED, paused).apply()
                    Log.i(TAG, "全局暂停: $paused")
                    result.success(true)
                }

                "isAllPaused" -> result.success(NodeRuntime.isAllPaused(appContext))

                // ---- 后台前台服务开关（BG-01；whale_modules/bg_service_enabled，默认 true）----

                "setBgServiceEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_BG_SERVICE_ENABLED, enabled).apply()
                    if (!enabled) NodeService.stop(appContext)
                    result.success(true)
                }

                "isBgServiceEnabled" -> result.success(isBgServiceEnabled(appContext))

                "getNotificationPermissionStatus" ->
                    result.success(NotificationModule.permissionStatus(appContext))

                "requestNotificationPermission" ->
                    result.success(NotificationModule.requestPermission(activity, appContext))

                // ---- VoIP 模块（B4：phone.call.ring + call.* 信令）----

                "getVoipPermissionStatus" ->
                    result.success(VoipModule.permissionStatus(appContext))

                "requestVoipPermissions" ->
                    result.success(VoipModule.requestPermissions(activity, appContext))

                "getVoipBridgeConfig" -> result.success(
                    mapOf(
                        "host" to BridgePrefs.getHost(appContext),
                        "port" to BridgePrefs.getPort(appContext),
                    )
                )

                "setVoipBridgeConfig" -> {
                    val host = call.argument<String>("host") ?: BridgePrefs.DEFAULT_HOST
                    val port = call.argument<Number>("port")?.toInt()
                        ?: BridgePrefs.DEFAULT_PORT
                    if (host.isBlank() || host.length > 255 || port !in 1..65535) {
                        return result.error("bad_args", "非法 bridge host/port", null)
                    }
                    BridgePrefs.set(appContext, host.trim(), port)
                    result.success(true)
                }

                // ---- 通知监听模块（B3 前半：phone.notification.read）----

                "getNotificationReadPermissionStatus" ->
                    result.success(NotificationReadModule.permissionStatus(appContext))

                /**
                 * SEC-02：授权（granted/denied）与系统实际绑定（bound）分开观测，
                 * 附带缓冲/溢出计数与最近绑定时间。
                 */
                "getNotificationReadStatus" -> result.success(
                    com.wangfanlab.whale.modules.notificationread
                        .WhaleNotificationListenerService.bindingStatus(appContext)
                )

                /** 「去授权」：跳系统通知监听设置页（特殊访问权限无运行时弹窗）。 */
                "openNotificationListenerSettings" -> {
                    NotificationReadModule.openListenerSettings(appContext)
                    result.success(true)
                }

                "getNotificationReadAllowedApps" ->
                    result.success(NotificationReadModule.allowedApps(appContext).toList())

                "setNotificationReadAllowedApps" -> {
                    @Suppress("UNCHECKED_CAST")
                    val apps = call.argument<List<String>>("apps")
                        ?: return result.error("bad_args", "missing apps", null)
                    // 包名格式白名单（复用 safe_action 的包名正则；防任意字符串写入）
                    val valid = apps.filter { SafeActionValidator.PACKAGE_REGEX.matches(it) }
                    NotificationReadModule.setAllowedApps(appContext, valid.toSet())
                    result.success(true)
                }

                /** 白名单多选页数据源：带 LAUNCHER 入口的已安装 App（label+package）。 */
                "getListenableApps" ->
                    result.success(NotificationReadModule.listListenableApps(appContext))

                // ---- 影子采集模块（SM-01；默认关；影子统计不含内容数据） ----

                "getCaptureStatus" -> result.success(
                    com.wangfanlab.whale.modules.capture.CaptureModule.status(appContext)
                )

                "setCaptureEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    com.wangfanlab.whale.modules.capture.CaptureModule.setEnabled(appContext, enabled)
                    Log.i(TAG, "影子采集模块开关: $enabled")
                    result.success(true)
                }

                "openAccessibilitySettings" -> {
                    com.wangfanlab.whale.modules.capture.CaptureModule
                        .openAccessibilitySettings(appContext)
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("internal", e.message, null)
        }
    }

    // ---- EventChannel.StreamHandler ----

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        // 补发最近一次状态，保证 Dart 订阅即可得当前状态
        lastStateEvent?.let { events?.success(it) }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    // ---- sensor.query 委托（NodeRuntime 回调；WssClient executor 线程） ----

    /**
     * 转发 sensor.query 到 Dart（异步）。返回 true 后 WssClient 不再自动回包，
     * Dart 侧必须经 sendSensorResult 回复；若事件在投递前丢失（Dart 退订），
     * 兜底 fail-closed 回 unavailable，避免云端空等到 deadline。
     *
     * 全局暂停拦截在 NodeRuntime 处理器链前置完成（无需 Dart 参与）。
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
                Log.w(TAG, "sensor.query 投递时 Dart 已退订，回 unavailable request_id=$requestId")
                runtime.sendSensorResult(
                    requestId, "unavailable", emptyList(), false, MCP_RESULT_SOURCE,
                )
            }
        }
        return true
    }

    private fun detectAppVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    companion object {
        private const val TAG = "TransportBridge"
        const val METHOD_CHANNEL = "whale/transport"
        const val EVENT_CHANNEL = "whale/transport/events"

        /** ScreenMemo MCP token 加密存储（与身份 Keystore 主密钥同款 AES/GCM）。 */
        private const val MCP_PREFS_NAME = "whale_mcp"
        private const val KEY_MCP_TOKEN_BLOB = "mcp_token_enc_v1"
        private const val MCP_RESULT_SOURCE = "screenmemo.local"

        /** 模块开关名白名单格式（防任意键写入 whale_modules）。 */
        private val MODULE_NAME_REGEX = Regex("^[a-z0-9_]{1,32}$")

        /** 后台前台服务开关键（whale_modules/bg_service_enabled；默认 true）。 */
        const val KEY_BG_SERVICE_ENABLED = "bg_service_enabled"

        /** 后台前台服务开关（BG-01）：false 时 connect 不启动 NodeService。 */
        fun isBgServiceEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG_SERVICE_ENABLED, true)
    }
}
