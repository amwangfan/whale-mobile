package com.wangfanlab.whale.transport

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.safeaction.SafeActionValidator
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.voip.BridgePrefs
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 桥：MethodChannel `whale/transport` + EventChannel `whale/transport/events`。
 *
 * MethodChannel（Dart → 原生，返回值见各分支）：
 * - connect({url, capabilities?, app_version?}) → {device_id, public_key_hex}
 * - disconnect() → null
 * - sendSensorEvent({kind, occurred_at?, privacy_class?, summary, evidence_ref?,
 *     schema_version?, drop_on_pause?, event_id?}) → {event_id}
 * - sendActionReceipt({action_id, state, executed_at?, error_code?}) → true
 * - setStatus({status: online|connected_only|paused}) → true（paused 时清除 drop_on_pause 队列）
 * - getState() → {state, device_id, public_key_hex, session_id?, heartbeat_interval,
 *     reconnect_attempts, queue_pending/sent/acked/dropped}
 * - getIdentity() → {device_id, public_key_hex}（配对时把 public_key_hex 报给 core 登记）
 * - sendSensorResult({request_id, status, data[], truncated, source}) → true
 *     （Dart 侧感知模块回 sensor.result 的统一入口，contracts/v1 §4）
 * - setSensorQueryDelegate({enabled}) → true：Dart 声明接管 sensor.query；
 *     未声明时保持 WssClient 默认 fail-closed（立即回 unavailable）
 * - setMcpToken({token}) / getMcpToken()：ScreenMemo MCP Bearer token 的 Keystore
 *     加密存取（AES/GCM 主密钥与设备身份同款，密文落 SharedPreferences whale_mcp）
 * - setModuleEnabled({module, enabled}) / getModuleStates()：模块开关通用读写
 *     （SharedPreferences whale_modules，键 `<module>_enabled`，默认 true）
 * - setAllPaused({paused}) / isAllPaused()：全局暂停标志（whale_modules/all_paused，
 *     A1 一键暂停）。暂停期间执行器校验链前置拦截：action 回 failed/unavailable，
 *     sensor.query 直接回 status="paused" 且不再分发 Dart。
 * - getNotificationPermissionStatus() → "granted"|"denied"
 * - requestNotificationPermission() → 发起 Android 13+ POST_NOTIFICATIONS 申请，
 *     返回发起时刻状态（结果异步，Dart 侧延迟重查）
 * - getVoipPermissionStatus() / requestVoipPermissions()：VoIP 模块权限
 *     （RECORD_AUDIO + READ_PHONE_STATE 运行时；MANAGE_OWN_CALLS 声明即授予）
 * - getVoipBridgeConfig() / setVoipBridgeConfig({host, port})：PCM 桥端点配置
 *     （SharedPreferences nova_bridge；call.invite 不携带桥参数，走此端侧配置）
 * - getNotificationReadPermissionStatus() / openNotificationListenerSettings()：
 *     通知监听特殊访问权限（NotificationManagerCompat.getEnabledListenerPackages）
 * - getNotificationReadAllowedApps() / setNotificationReadAllowedApps({apps})：
 *     通知监听白名单（whale_notif_read/allowed_apps，默认空 = 一个都不读）
 * - getListenableApps()：白名单多选页数据源（带 LAUNCHER 入口的已安装 App）
 *
 * action 分发（注册表模式）：phone.notification.post 由 NotificationModule 在
 * Kotlin 侧直接执行并回终态（DESIGN §4）；无执行器的 capability 仍走
 * {kind:"action_request"} 事件交 Dart 展示。
 *
 * EventChannel（原生 → Dart），事件为 Map：
 * - {kind: "state", state, ...getState 字段}   状态变化
 * - {kind: "action_request", action_id, capability, args, authorized_scope, ...}
 * - {kind: "action_receipt", action_id, capability, state, error_code?}
 *     本地执行器终态回执（仅展示用；上行 receipt 由 WssClient 直接发）
 * - {kind: "sensor_query", request_id, operation, filters, max_items, deadline}
 * - {kind: "error", code, message, ref_id}     协议级错误
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)

    // WssClient 在 executor 线程上读 eventSink（onSensorQuery 同步分发），需 @Volatile。
    @Volatile
    private var eventSink: EventChannel.EventSink? = null
    private var lastStateEvent: Map<String, Any?>? = null
    private var deviceStatus: String = "online"

    /** Dart 侧是否接管 sensor.query（screenmemo 模块）；false 时 WssClient fail-closed。 */
    @Volatile
    private var sensorQueryDelegateEnabled: Boolean = false

    private val identity: DeviceIdentity by lazy { DeviceIdentity(appContext) }
    private val queue: OfflineQueue by lazy { OfflineQueue(appContext) }
    private val notificationModule: NotificationModule by lazy { NotificationModule(appContext) }

    /**
     * 通知监听模块（B3 前半，phone.notification.read；独立可选，默认关）。
     * 事件出口经 clientOrCreate().sendSensorEvent（离线入队；drop_on_pause=true，
     * 私人历史暂停不补传，§6）。lazy 但在 init 中触碰，注册 NotificationListenerService
     * 静态钩子（服务被系统绑定后即可走完整校验链）。
     */
    private val notificationReadModule: NotificationReadModule by lazy {
        NotificationReadModule(appContext, eventSender = { summary, occurredAt ->
            clientOrCreate().sendSensorEvent(
                kind = "notification.posted",
                occurredAt = occurredAt,
                privacyClass = "owner_private",
                summary = summary,
                dropOnPause = true,
            )
        })
    }

    /** 受控设备动作模块（B3 前半，phone.device.safe_action；默认关）。 */
    private val safeActionModule: SafeActionModule by lazy { SafeActionModule(appContext) }

    /**
     * VoIP 模块（B4 前半）：事件出口经 clientOrCreate().sendSensorEvent（离线入队，
     * §6 至少一次送达）。lazy 但在 init 中触碰注册 Telecom 状态钩子，保证
     * 来电事件在首次连接前也能入队。
     */
    private val voipModule: VoipModule by lazy {
        VoipModule(appContext, eventSender = { ev ->
            clientOrCreate().sendSensorEvent(
                kind = ev.kind,
                occurredAt = System.currentTimeMillis(),
                privacyClass = ev.privacyClass,
                summary = ev.summary,
            )
        })
    }
    private var client: WssClient? = null

    private fun clientOrCreate(): WssClient = client ?: WssClient(
        identity, queue, clientListener, mainHandler,
    ).apply {
        heartbeatStatusProvider = { WssClient.HeartbeatStatus(status = deviceStatus) }
        // action 执行器注册表：通知/VoIP/safe_action 模块在 Kotlin 侧直接执行（DESIGN §4）
        registerActionExecutor(NotificationModule.CAPABILITY, notificationModule)
        registerActionExecutor(VoipModule.CAPABILITY_RING, voipModule)
        registerActionExecutor(SafeActionModule.CAPABILITY, safeActionModule)
    }.also { client = it }

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        voipModule // 触碰 lazy：注册 FakeConnectionService.CallStateListener 钩子
        notificationReadModule // 触碰 lazy：注册 NotificationListenerService 静态钩子
    }

    fun dispose() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        client?.destroy()
        client = null
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
                    clientOrCreate().connect(url, caps, appVersion)
                    result.success(
                        mapOf(
                            "device_id" to identity.getDeviceId(),
                            "public_key_hex" to identity.getPublicKeyHex(),
                        )
                    )
                }

                "disconnect" -> {
                    clientOrCreate().disconnect()
                    result.success(null)
                }

                "sendSensorEvent" -> {
                    val kind = call.argument<String>("kind")
                        ?: return result.error("bad_args", "missing kind", null)
                    val summary = call.argument<String>("summary")
                        ?: return result.error("bad_args", "missing summary", null)
                    val eventId = clientOrCreate().sendSensorEvent(
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
                    clientOrCreate().sendActionReceipt(
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
                    deviceStatus = status
                    if (status == "paused") {
                        val dropped = queue.purgeDropOnPause()
                        if (dropped > 0) Log.i(TAG, "进入 paused，丢弃 drop_on_pause 事件 $dropped 条")
                    }
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
                    clientOrCreate().sendSensorResult(
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
                            .putString(KEY_MCP_TOKEN_BLOB, identity.encryptString(token))
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
                            identity.decryptString(it)
                        } catch (e: Exception) {
                            Log.w(TAG, "MCP token 解密失败（主密钥可能已失效），按未配置处理", e)
                            null
                        }
                    }
                    result.success(token)
                }

                "getState" -> result.success(clientOrCreate().getStateInfo())

                "getIdentity" -> result.success(
                    mapOf(
                        "device_id" to identity.getDeviceId(),
                        "public_key_hex" to identity.getPublicKeyHex(),
                    )
                ).also {
                    // 配对引导：debug 包把设备身份打到 logcat（公钥非秘密；release 不打）。
                    val dbg = (appContext.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (dbg) {
                        android.util.Log.i("WhaleBridge",
                            "device_id=${identity.getDeviceId()} pubkey=${identity.getPublicKeyHex()}")
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
                        .edit().putBoolean(KEY_ALL_PAUSED, paused).apply()
                    Log.i(TAG, "全局暂停: $paused")
                    result.success(true)
                }

                "isAllPaused" -> result.success(isAllPaused(appContext))

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

    // ---- WssClient.Listener（已在主线程） ----

    private val clientListener = object : WssClient.Listener {
        override fun onStateChanged(state: WssClient.State, info: Map<String, Any?>) {
            val event = HashMap<String, Any?>(info)
            event["kind"] = "state"
            event["state"] = WssClient.stateName(state)
            lastStateEvent = event
            eventSink?.success(event)
        }

        override fun onActionRequest(payload: Map<String, Any?>) {
            val event = HashMap<String, Any?>()
            event["kind"] = "action_request"
            event.putAll(payload)
            eventSink?.success(event)
        }

        /** 本地执行器终态 → Dart 展示（如通知模块「最近回执」）。 */
        override fun onActionTerminal(
            actionId: String,
            capability: String,
            state: String,
            errorCode: String?,
        ) {
            eventSink?.success(
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
         * sensor.query 转发给 Dart（异步）。返回 true 后 WssClient 不再自动回包，
         * Dart 侧必须经 sendSensorResult 回复；若事件在投递前丢失（Dart 退订），
         * 这里兜底 fail-closed 回 unavailable，避免云端空等到 deadline。
         *
         * 全局暂停（A1）为分发链前置拦截：暂停时不分发 Dart，直接回 paused。
         */
        override fun onSensorQuery(payload: Map<String, Any?>): Boolean {
            val requestId = payload["request_id"] as? String ?: return false
            if (isAllPaused(appContext)) {
                Log.i(TAG, "全局暂停中，sensor.query 回 paused request_id=$requestId")
                client?.sendSensorResult(
                    requestId, "paused", emptyList(), false, MCP_RESULT_SOURCE,
                )
                return true
            }
            if (!sensorQueryDelegateEnabled) return false
            val sink = eventSink ?: return false
            mainHandler.post {
                val current = eventSink
                if (current != null) {
                    val event = HashMap<String, Any?>()
                    event["kind"] = "sensor_query"
                    event.putAll(payload)
                    current.success(event)
                } else {
                    Log.w(TAG, "sensor.query 投递时 Dart 已退订，回 unavailable request_id=$requestId")
                    client?.sendSensorResult(
                        requestId, "unavailable", emptyList(), false, MCP_RESULT_SOURCE,
                    )
                }
            }
            return true
        }

        /** 下行 call.invite → VoipModule 消费；同时回显 Dart 日志（主线程）。 */
        override fun onCallInvite(payload: Map<String, Any?>) {
            voipModule.onCallInvite(payload)
            eventSink?.success(
                mapOf(
                    "kind" to "call_invite",
                    "session_id" to payload["session_id"],
                    "expires_at" to payload["expires_at"],
                )
            )
        }

        /** 下行 call.hangup → VoipModule 挂断；同时通知 Dart 日志。 */
        override fun onCallHangup(payload: Map<String, Any?>) {
            voipModule.onCallHangup(payload)
            eventSink?.success(
                mapOf(
                    "kind" to "call_hangup",
                    "session_id" to payload["session_id"],
                    "reason" to payload["reason"],
                )
            )
        }

        override fun onProtocolError(code: String, message: String, refId: String) {
            eventSink?.success(
                mapOf(
                    "kind" to "error",
                    "code" to code,
                    "message" to message,
                    "ref_id" to refId,
                )
            )
        }
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

        /** 全局暂停键（whale_modules/all_paused，A1 一键暂停；默认 false）。 */
        const val KEY_ALL_PAUSED = "all_paused"

        /**
         * 全局暂停标志读取（执行器校验链前置检查）：
         * true 时 NotificationModule 回 failed/unavailable、sensor.query 回 paused。
         */
        fun isAllPaused(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALL_PAUSED, false)
    }
}
