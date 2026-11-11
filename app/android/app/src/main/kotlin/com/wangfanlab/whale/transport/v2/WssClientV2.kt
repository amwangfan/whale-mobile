package com.wangfanlab.whale.transport.v2

import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import com.wangfanlab.whale.BuildConfig
import com.wangfanlab.whale.transport.DeviceIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WSS 控制通道客户端（contracts/v2，替换 v1 [com.wangfanlab.whale.transport.WssClient]）。
 *
 * 握手时序（§1/§4）：
 *   connect → TCP/WS 建立（CONNECTING → AWAITING_AUTH）
 *   ← auth.challenge{challenge: 64-hex, expires_in: 秒}（服务端首帧）
 *   → device.hello{v2 全字段，signature=Ed25519(challenge UTF-8 字节)}（seq=1）
 *   ← device.hello_ack{session_id, server_time, heartbeat_interval, accepted_capabilities}
 *   → device.heartbeat 每 heartbeat_interval 秒（基线 30s，§1；90s 无心跳对端判离线）
 *
 * - 帧处理（§4）：未过信封校验 → error.invalid_message 保持连接；未知 type → 静默忽略；
 *   未知 major → error.protocol_version 后关闭。
 * - seq：上行每连接从 1 单调递增；下行观察记录（跳变仅告警不拒绝，与 v1 网关语义一致）。
 * - 断线重连：沿用 v1 [ReconnectBackoff] 语义（1s→60s 指数退避，±20% 抖动）；
 *   曾 ONLINE 后掉线进入 DEGRADED；auth_failed/device_revoked 为终态不重连，
 *   撤销时按 §16 清空上行队列与幂等表。
 * - action.request 一律交 [ReceiptDispatcherV2]（幂等/超时/执行器注册表）；
 *   业务层（Dart）终态经 [sendActionReceipt] 回流到分发器做 first-terminal-wins 落库。
 *
 * 线程模型：除构造/销毁外所有状态变更都在内部单线程 executor 上；
 * Listener 回调统一切到 mainHandler（默认主线程）。
 */
class WssClientV2(
    private val identity: DeviceIdentity,
    private val outbox: EventOutboxV2,
    dedupStore: ActionDedupStoreV2,
    private val listener: Listener,
    private val mainHandler: Handler,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    backoffFactory: () -> ReconnectBackoff = { ReconnectBackoff() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State { DISCONNECTED, CONNECTING, AWAITING_AUTH, ONLINE, DEGRADED }

    /** 帧分发接口（主线程回调）。默认实现均为忽略（前向兼容）。 */
    interface Listener {
        fun onStateChanged(state: State, info: Map<String, Any?>)

        /** UI 委托的 action.request（无原生执行器时；终态经 sendActionReceipt 补发）。 */
        fun onActionRequest(payload: Map<String, Any?>) {}

        /**
         * sensor.query 分发钩。返回 true = 有模块接管（稍后经 [sendSensorResult] 回复）；
         * 默认 false = 无处理器，客户端立即 fail-closed 回 unavailable。
         */
        fun onSensorQuery(payload: Map<String, Any?>): Boolean = false

        /**
         * 下行 notification.event（§13.1；已自动回 received 回执）。同 event_id 的
         * 重复事件也会派发（会话内 LRU 命中）：实现方应依持久去重（如 notification2
         * Inbox）保证不重复展示，并可按落库状态重发终态回执；默认实现忽略。
         */
        fun onNotificationEvent(payload: Map<String, Any?>) {}

        /** 下行 notification.revoke（§13.3）。 */
        fun onNotificationRevoke(payload: Map<String, Any?>) {}

        /** 下行 recording.control（§12.2；MediaProjection 录制控制）。 */
        fun onRecordingControl(payload: Map<String, Any?>) {}

        /** 下行 device.confirm.request（§14 可信确认挑战）。 */
        fun onConfirmRequest(payload: Map<String, Any?>) {}

        /** 下行 call.invite（VoIP 来电邀请；§6 消息目录 call.*）。 */
        fun onCallInvite(payload: Map<String, Any?>) {}

        /** 下行 call.hangup（对端/远端挂断）。 */
        fun onCallHangup(payload: Map<String, Any?>) {}

        /** 协议级错误（含本端上行 error 的回声与对端主动错误）。 */
        fun onProtocolError(code: String, message: String, refId: String) {}

        /** 本地执行的动作到达终态时回调（诊断/UI 展示；不影响已上行回执）。 */
        fun onActionTerminal(actionId: String, capability: String, status: String, errorCode: String?) {}
    }

    // ------------------------------------------------------------- hello 组装（§5.2）

    /** features 布尔特征集（§5.2；feature=false 的能力即使声明也会被 core 忽略）。 */
    data class FeatureSet(
        val rootShell: Boolean,
        val accessibility: Boolean,
        val mediaProjection: Boolean,
        val telecom: Boolean,
        val ocrMlkit: Boolean,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "root_shell" to rootShell,
            "accessibility" to accessibility,
            "media_projection" to mediaProjection,
            "telecom" to telecom,
            "ocr_mlkit" to ocrMlkit,
        )
    }

    /** features 实测来源（可注入假实现测试）。 */
    fun interface FeatureProbe {
        fun probe(): FeatureSet
    }

    /**
     * Android 实测 [FeatureProbe]：
     * - root_shell：常见 su 路径存在性（不执行 su，避免触发授权弹窗；近似值，
     *   真实可用性以执行期 `su 0 sh -c` 结果为准）；
     * - accessibility：本 App 的无障碍服务已被系统启用；
     * - media_projection：MediaProjectionManager 服务可用（实际采集仍需用户当次授权）；
     * - telecom：系统特性 FEATURE_TELEPHONY；
     * - ocr_mlkit：ML Kit 文本识别类存在。
     */
    class AndroidFeatureProbe(private val context: Context) : FeatureProbe {
        override fun probe(): FeatureSet {
            val app = context.applicationContext
            return FeatureSet(
                rootShell = SU_PATHS.any { File(it).exists() },
                accessibility = accessibilityEnabled(app),
                mediaProjection = try {
                    app.getSystemService(MediaProjectionManager::class.java) != null
                } catch (t: Throwable) { false },
                telecom = try {
                    app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                } catch (t: Throwable) { false },
                ocrMlkit = try {
                    Class.forName("com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions")
                    true
                } catch (t: Throwable) { false },
            )
        }

        private fun accessibilityEnabled(context: Context): Boolean = try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val expectedPrefix = "${context.packageName}/"
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.id.startsWith(expectedPrefix) } == true
        } catch (t: Throwable) {
            false
        }

        companion object {
            private val SU_PATHS = arrayOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/vendor/bin/su", "/su/bin/su",
            )
        }
    }

    /** device.hello 组装配置（§5.2 DeviceDescriptor）。 */
    data class HelloConfig(
        /** 能力集合（按构建变体传入；日常版不得含 device.shell 等 Root 能力，§5.2）。 */
        val capabilities: Set<String>,
        val features: FeatureSet,
        val deviceModel: String,
        val androidRelease: String,
        val apiLevel: Int,
        val appVersion: String,
        /** 构建变体（daily/root；默认取 BuildConfig.FLAVOR）。 */
        val appFlavor: String,
    ) {
        companion object {
            /** 以当前设备/构建实测组装（capabilities 由 CapabilityRegistry 按变体给出）。 */
            fun detect(
                context: Context,
                capabilities: Set<String>,
                appVersion: String,
                probe: FeatureProbe = AndroidFeatureProbe(context),
            ): HelloConfig = HelloConfig(
                capabilities = capabilities,
                features = probe.probe(),
                deviceModel = Build.MODEL ?: "unknown",
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                apiLevel = Build.VERSION.SDK_INT,
                appVersion = appVersion,
                appFlavor = BuildConfig.FLAVOR.ifEmpty { "daily" },
            )
        }
    }

    /** 心跳负载来源（§6：online/connected_only/paused + 可选电量/网络/模块摘要）。 */
    data class HeartbeatStatusV2(
        val status: String = "online",
        val battery: Int? = null,
        val network: String? = null, // wifi / cellular / none / unknown
        val modules: Map<String, String> = emptyMap(),
    )

    @Volatile
    var heartbeatStatusProvider: () -> HeartbeatStatusV2 = { HeartbeatStatusV2() }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "whale-wss-v2") }
    private val lifecycle = SerialLifecycle(executor)
    private val backoff = backoffFactory()

    /** action 分发器（幂等/超时/执行器注册表）；与客户端共用单线程 executor。 */
    val dispatcher: ReceiptDispatcherV2 = ReceiptDispatcherV2(
        store = dedupStore,
        sendUplink = { payload -> sendEnvelopeLocked(MsgTypeV2.ACTION_RECEIPT, payload) },
        scheduler = executor,
        clock = clock,
    ).apply {
        acceptedCapabilitiesProvider = { acceptedCapabilities }
        hasUiHandler = { listenerHasUiHandler() }
        uiDispatch = { payload -> postMain { listener.onActionRequest(payload) } }
        sendProtocolError = { refId, code, message -> sendErrorLocked(refId, code, message) }
    }

    // ---- 仅 executor 线程写；getStateInfo 可跨线程读（@Volatile 保证可见性） ----
    @Volatile
    private var state = State.DISCONNECTED
    private var ws: WebSocket? = null
    private val connectionGeneration = ConnectionGeneration()
    private var url: String? = null
    private var helloConfig: HelloConfig? = null
    private var voluntaryClose = false
    private var destroyed = false
    private var hadOnlineThisSession = false
    private var upSeq = SeqCounter()
    private var downSeq = SeqCounter()

    @Volatile
    private var lastDisconnectReason: String? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var heartbeatIntervalSec = DEFAULT_HEARTBEAT_INTERVAL_SEC

    @Volatile
    private var acceptedCapabilities: Set<String> = emptySet()
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var authTimeoutFuture: ScheduledFuture<*>? = null
    private var lastInboundMonoMs = 0L
    private var serverTimeOffsetMs = 0L

    /** 通知事件会话内去重（§13：同一事件经多通道送达必须只展示一次；LRU 512）。 */
    private val recentNotifEventIds = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > NOTIF_DEDUP_CAPACITY
    }

    /** 已处理的最大通知事件 ID（ULID 字典序 ≈ 时间序）；notification.sync 上报用。 */
    @Volatile
    private var lastNotifEventId: String? = null

    // ------------------------------------------------------------- 公共 API

    fun connect(url: String, config: HelloConfig) {
        lifecycle.execute {
            if (destroyed) return@execute
            // 幂等连接：相同地址且已在线/建连中时不重开（防恢复路径与 UI 竞态拉锯）。
            if (this.url == url && state != State.DISCONNECTED) {
                Log.i(TAG, "已处于 ${stateName(state)}（同地址），忽略重复 connect")
                return@execute
            }
            voluntaryClose = false
            this.url = url
            this.helloConfig = config
            hadOnlineThisSession = false
            backoff.reset()
            cancelReconnect()
            openSocket()
        }
    }

    fun disconnect() {
        lifecycle.execute { disconnectLocked() }
    }

    private fun disconnectLocked() {
        voluntaryClose = true
        connectionGeneration.invalidate()
        cancelReconnect()
        stopHeartbeat()
        cancelAuthTimeout()
        ws?.close(1000, "client disconnect")
        ws = null
        dispatcher.onConnectionLost()
        transition(State.DISCONNECTED)
    }

    /**
     * 立即重连（网络恢复回调用）：重置退避并立刻建连。
     * 仅在非主动断开、非销毁、当前不在线时生效。
     */
    fun reconnectNow() {
        lifecycle.execute {
            if (destroyed || voluntaryClose) return@execute
            if (state == State.ONLINE || state == State.AWAITING_AUTH) return@execute
            if (url == null) return@execute
            Log.i(TAG, "网络恢复，立即重连（重置退避）")
            backoff.reset()
            cancelReconnect()
            openSocket()
        }
    }

    /**
     * 销毁客户端。shutdown 后若工作线程仍卡在延迟任务的 take() 上
     * （JDK STPE 竞态：worker 被 interrupt 唤醒后抢在 onShutdown 清队列之前
     * 重新 park 在已取消但未物理移除的延迟任务上，要等整个 delay 才退出），
     * 以 shutdownNow 兜底打断——此时优雅关闭任务已入队执行或可被丢弃
     * （ws?.cancel 路径见 disconnect 对端超时兜底），最多阻塞调用线程 2s。
     */
    fun destroy() {
        lifecycle.close {
            destroyed = true
            disconnectLocked()
        }
    }

    /**
     * 上行 experience.event（§9/§16）：先入队（event_id 幂等），在线则立即尝试发送。
     * @return 实际使用的 event_id
     */
    fun sendExperienceEvent(
        kind: String,
        occurredAt: Long,
        source: String,
        summary: String,
        privacyClass: String,
        evidenceRef: String? = null,
        taskId: String? = null,
        runId: String? = null,
        extras: Map<String, Any?>? = null,
        dropOnPause: Boolean = false,
        eventId: String = IdsV2.newEventId(),
    ): String {
        val id = outbox.enqueueExperienceEvent(
            kind, occurredAt, source, summary, privacyClass,
            evidenceRef, taskId, runId, extras, dropOnPause = dropOnPause, eventId = eventId,
        )
        lifecycle.execute {
            if (state == State.ONLINE) flushOutboxLocked()
        }
        return id
    }

    /**
     * 业务层终态回执（UI 委托动作的完成入口）：五终态 first-wins 落库后上行。
     */
    fun sendActionReceipt(
        actionId: String,
        status: ReceiptStatus,
        errorCode: String? = null,
        resultSummary: String? = null,
        outputRef: String? = null,
        verificationStatus: String? = null,
        verificationSummary: String? = null,
        providerRef: String? = null,
    ) {
        dispatcher.completeFromBusiness(
            actionId, status, errorCode,
            resultSummary, outputRef, verificationStatus, verificationSummary, providerRef,
        )
    }

    /** 上行 sensor.result（§6）。 */
    fun sendSensorResult(
        requestId: String,
        status: String, // ok / denied / unavailable / paused / error
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    ) {
        lifecycle.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["request_id"] = requestId
            payload["status"] = status
            payload["data"] = data
            payload["truncated"] = truncated
            payload["source"] = source
            sendEnvelopeLocked(MsgTypeV2.SENSOR_RESULT, payload)
        }
    }

    /** 上行 notification.receipt（§13.2；received 由客户端自动回，其余由业务层上报）。 */
    fun sendNotificationReceipt(
        eventId: String,
        notificationId: String,
        status: String,
        receivedAt: Long = clock(),
    ) {
        require(status in NOTIFICATION_RECEIPT_STATUSES) { "非法通知回执状态: $status" }
        lifecycle.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["event_id"] = eventId
            payload["notification_id"] = notificationId
            payload["device_id"] = identity.getDeviceId()
            payload["status"] = status
            payload["received_at"] = receivedAt
            sendEnvelopeLocked(MsgTypeV2.NOTIFICATION_RECEIPT, payload)
        }
    }

    /** 上行 notification.action（§13.3；按钮操作回传，审批裁决仍在 core）。 */
    fun sendNotificationAction(eventId: String, notificationId: String, actionId: String) {
        lifecycle.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["event_id"] = eventId
            payload["notification_id"] = notificationId
            payload["device_id"] = identity.getDeviceId()
            payload["action_id"] = actionId
            payload["submitted_at"] = clock()
            sendEnvelopeLocked(MsgTypeV2.NOTIFICATION_ACTION, payload)
        }
    }

    /** 上行 notification.sync（§13.3；重连后同步位置上报，last_event_id 可空）。 */
    fun sendNotificationSync() {
        lifecycle.execute {
            if (state != State.ONLINE) return@execute
            val payload = LinkedHashMap<String, Any?>()
            payload["device_id"] = identity.getDeviceId()
            lastNotifEventId?.let { payload["last_event_id"] = it }
            payload["synced_at"] = clock()
            sendEnvelopeLocked(MsgTypeV2.NOTIFICATION_SYNC, payload)
        }
    }

    /** 上行 device.confirm.response（§14；approved=true 时以身份私钥签名 challenge_id）。 */
    fun sendConfirmResponse(challengeId: String, approved: Boolean, reason: String? = null) {
        lifecycle.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["challenge_id"] = challengeId
            payload["approved"] = approved
            if (approved) {
                // §14/schema：approved=true 时 signature=Ed25519(challenge_id UTF-8 字节)，必填。
                payload["signature"] = identity.signHex(challengeId.toByteArray(Charsets.UTF_8))
            }
            if (!approved && reason != null) payload["reason"] = reason
            payload["responded_at"] = clock()
            sendEnvelopeLocked(MsgTypeV2.DEVICE_CONFIRM_RESPONSE, payload)
        }
    }

    /** 上行 recording.state（§12.2 录制状态回报）。 */
    fun sendRecordingState(
        runId: String,
        state: String,
        fileRef: String? = null,
        durationMs: Long? = null,
        errorCode: String? = null,
    ) {
        lifecycle.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["run_id"] = runId
            payload["state"] = state
            fileRef?.let { payload["file_ref"] = it }
            durationMs?.let { payload["duration_ms"] = it }
            errorCode?.let { payload["error_code"] = it }
            sendEnvelopeLocked(MsgTypeV2.RECORDING_STATE, payload)
        }
    }

    /** 上行 run.trace.event（§12.1；payload 由轨迹记录器组装）。 */
    fun sendRunTraceEvent(payload: Map<String, Any?>) {
        lifecycle.execute { sendEnvelopeLocked(MsgTypeV2.RUN_TRACE_EVENT, payload) }
    }

    /** 上行协议级 error（§6/§15）。 */
    fun sendError(refId: String, code: String, message: String) {
        lifecycle.execute { sendErrorLocked(refId, code, message) }
    }

    /** 进入 paused 时调用：清除 outbox 中标记 drop_on_pause 的未送达事件（§16），返回清除数。 */
    fun clearDropOnPauseEvents(): Int = outbox.purgeDropOnPause()

    fun getStateInfo(): Map<String, Any?> {
        val s = outbox.stats()
        return mapOf(
            "state" to stateName(state),
            "protocol" to PROTOCOL_VERSION_V2,
            "device_id" to identity.getDeviceId(),
            "public_key_hex" to identity.getPublicKeyHex(),
            "session_id" to sessionId,
            "heartbeat_interval" to heartbeatIntervalSec,
            "reconnect_attempts" to backoff.attempts,
            "last_disconnect_reason" to lastDisconnectReason,
            "outbox_pending" to s.pending,
            "outbox_sent" to s.sent,
            "outbox_acked" to s.acked,
            "outbox_dropped" to s.dropped,
        )
    }

    // ------------------------------------------------------------- WS 回调（OkHttp 线程 → executor）

    private fun enqueueSocketEvent(token: ConnectionGeneration.Token, block: () -> Unit) {
        lifecycle.execute { connectionGeneration.runIfCurrent(token, block) }
    }

    private fun socketListener(token: ConnectionGeneration.Token) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            enqueueSocketEvent(token) {
                lastInboundMonoMs = SystemClock.elapsedRealtime()
                transition(State.AWAITING_AUTH)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            enqueueSocketEvent(token) { handleTextLocked(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            enqueueSocketEvent(token) { handleTransportLostLocked("closed code=$code reason=$reason") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            enqueueSocketEvent(token) {
                handleTransportLostLocked("failure: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------- 内部（全部 executor 线程）

    private fun openSocket() {
        val target = url ?: return
        val token = connectionGeneration.begin()
        ws?.cancel()
        ws = null
        stopHeartbeat()
        cancelAuthTimeout()
        upSeq = SeqCounter()
        downSeq = SeqCounter()
        sessionId = null
        transition(if (hadOnlineThisSession) State.DEGRADED else State.CONNECTING)
        val request = Request.Builder().url(target).build()
        ws = okHttpClient.newWebSocket(request, socketListener(token))
    }

    private fun handleTransportLostLocked(why: String) {
        if (destroyed) return
        connectionGeneration.invalidate()
        lastDisconnectReason = why
        stopHeartbeat()
        cancelAuthTimeout()
        ws = null
        // 在飞动作不终结（副作用可能已发生）；重连后重复下发由分发器幂等规则接管。
        dispatcher.onConnectionLost()
        if (voluntaryClose) {
            transition(State.DISCONNECTED)
            return
        }
        Log.i(TAG, "连接断开（$why），进入重连退避")
        transition(if (hadOnlineThisSession) State.DEGRADED else State.CONNECTING)
        scheduleReconnectLocked()
    }

    private fun scheduleReconnectLocked() {
        if (reconnectFuture != null || destroyed || voluntaryClose) return
        val delay = backoff.nextDelayMs()
        Log.i(TAG, "第 ${backoff.attempts} 次重连将于 ${delay}ms 后发起")
        reconnectFuture = executor.schedule({
            reconnectFuture = null
            if (!destroyed && !voluntaryClose) openSocket()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun cancelAuthTimeout() {
        authTimeoutFuture?.cancel(false)
        authTimeoutFuture = null
    }

    // ------------------------------------------------------------- 帧处理（§4）

    private fun handleTextLocked(text: String) {
        lastInboundMonoMs = SystemClock.elapsedRealtime()
        try {
            EnvelopeCodecV2.checkFrameSize(text)
        } catch (e: EnvelopeExceptionV2) {
            Log.w(TAG, "下行帧超限，断开: ${e.message}")
            ws?.close(1009, "frame too large")
            return
        }
        val env = try {
            EnvelopeCodecV2.decode(text)
        } catch (e: EnvelopeExceptionV2) {
            // §4：未过信封校验 → error.invalid_message 并保持连接（无法取得 ref 时用零 ULID）。
            Log.w(TAG, "信封解析失败: ${e.message}")
            sendErrorLocked(ZERO_ULID, ErrorCodeV2.INVALID_MESSAGE, "malformed envelope")
            return
        }
        if (!EnvelopeCodecV2.isSupportedVersion(env.v)) {
            // §4：未知 major → error.protocol_version 后关闭。
            Log.w(TAG, "未知协议 major（v=${env.v}），回 protocol_version 并关闭")
            sendErrorLocked(env.id, ErrorCodeV2.PROTOCOL_VERSION, "unsupported major: ${env.v}")
            voluntaryClose = true // 不重连：对端讲的协议我们不懂，重连无意义
            ws?.close(1002, "protocol version unsupported")
            transition(State.DISCONNECTED)
            return
        }
        if (env.dir != DIR_DOWNLINK) {
            Log.w(TAG, "下行帧 dir=${env.dir}（应为 downlink），回 invalid_message")
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad dir")
            return
        }
        if (!downSeq.observeIncoming(env.seq)) {
            Log.w(TAG, "下行 seq 跳变/乱序: seq=${env.seq}（仅记录）")
        }
        when (env.type) {
            MsgTypeV2.AUTH_CHALLENGE -> onAuthChallengeLocked(env)
            MsgTypeV2.DEVICE_HELLO_ACK -> onHelloAckLocked(env)
            MsgTypeV2.EVENT_ACK -> onEventAckLocked(env)
            MsgTypeV2.ACTION_REQUEST -> dispatcher.handleRequest(env.id, env.payload)
            MsgTypeV2.SENSOR_QUERY -> onSensorQueryLocked(env)
            MsgTypeV2.NOTIFICATION_EVENT -> onNotificationEventLocked(env)
            MsgTypeV2.NOTIFICATION_REVOKE -> onNotificationRevokeLocked(env)
            MsgTypeV2.RECORDING_CONTROL -> onRecordingControlLocked(env)
            MsgTypeV2.DEVICE_CONFIRM_REQUEST -> onConfirmRequestLocked(env)
            MsgTypeV2.CALL_INVITE -> {
                Log.i(TAG, "收到 call.invite")
                listener.onCallInvite(env.payload)
            }
            MsgTypeV2.CALL_HANGUP -> {
                Log.i(TAG, "收到 call.hangup")
                listener.onCallHangup(env.payload)
            }
            MsgTypeV2.ERROR -> onErrorLocked(env)
            else -> {
                // §4：未知消息类型静默忽略（前向兼容）。
                Log.d(TAG, "忽略未知/暂不支持的消息类型: ${env.type}")
            }
        }
    }

    private fun onAuthChallengeLocked(env: EnvelopeV2) {
        if (state != State.AWAITING_AUTH) return
        val challenge = env.payload["challenge"] as? String
        val expiresIn = payloadLongV2(env.payload, "expires_in")
        if (challenge == null || !IdsV2.CHALLENGE_HEX.matches(challenge) ||
            expiresIn == null || expiresIn !in 1..300
        ) {
            Log.w(TAG, "auth.challenge 结构非法，回 invalid_message")
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad auth.challenge")
            return
        }
        val config = helloConfig ?: run {
            Log.w(TAG, "缺少 HelloConfig，无法握手")
            return
        }
        // §4：对 challenge 十六进制字符串的 UTF-8 字节做 Ed25519 签名，小写 hex 输出。
        val signature = identity.signHex(challenge.toByteArray(Charsets.UTF_8))
        sendEnvelopeLocked(MsgTypeV2.DEVICE_HELLO, buildHelloPayload(config, identity.getDeviceId(), challenge, signature))
        // 挑战有效期内未完成握手则断开重连（防悬挂）。
        cancelAuthTimeout()
        authTimeoutFuture = executor.schedule({
            if (state == State.AWAITING_AUTH) {
                Log.w(TAG, "hello 握手超时（>${expiresIn}s），断开重连")
                ws?.cancel()
            }
        }, expiresIn, TimeUnit.SECONDS)
    }

    private fun onHelloAckLocked(env: EnvelopeV2) {
        if (state != State.AWAITING_AUTH) return
        val sid = env.payload["session_id"] as? String
        if (sid == null || !Ulid.isValid(sid)) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad hello_ack.session_id")
            return
        }
        cancelAuthTimeout()
        sessionId = sid
        heartbeatIntervalSec = payloadLongV2(env.payload, "heartbeat_interval")
            ?.toInt()?.coerceIn(1, 3600) ?: DEFAULT_HEARTBEAT_INTERVAL_SEC
        @Suppress("UNCHECKED_CAST")
        acceptedCapabilities =
            (env.payload["accepted_capabilities"] as? List<*>)?.filterIsInstance<String>()
                ?.filter { it in CapabilitiesV2.ALL }?.toSet() ?: emptySet()
        val serverTime = payloadLongV2(env.payload, "server_time")
        if (serverTime != null) {
            serverTimeOffsetMs = serverTime - clock()
        }
        hadOnlineThisSession = true
        backoff.reset()
        transition(State.ONLINE)
        startHeartbeatLocked()
        outbox.resetSentToPending()
        flushOutboxLocked()
        // §13.3：重连后上报通知同步位置，core 补发仍在有效期内的事件。
        sendNotificationSync()
    }

    /** event.ack：按 event_id 确认（含去重命中），outbox SENT→ACKED 停发（§9/§16）。 */
    private fun onEventAckLocked(env: EnvelopeV2) {
        val ids = (env.payload["event_ids"] as? List<*>)?.filterIsInstance<String>()
            ?.filter { IdsV2.EVENT_ID.matches(it) } ?: return
        val n = outbox.markAcked(ids)
        if (n > 0) Log.d(TAG, "event.ack 确认 $n 条")
    }

    /** sensor.query：操作白名单校验后有模块接管则分发，否则 fail-closed 回 unavailable。 */
    private fun onSensorQueryLocked(env: EnvelopeV2) {
        val requestId = env.payload["request_id"] as? String
        val operation = env.payload["operation"] as? String
        if (requestId == null || !Ulid.isValid(requestId) || operation == null) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad sensor.query")
            return
        }
        if (operation !in CapabilitiesV2.SENSOR_QUERY_SUBSET) {
            Log.w(TAG, "sensor.query 操作不在 §5.3 查询子集: $operation")
            sendSensorResult(requestId, "error",
                listOf(mapOf("error" to ErrorCodeV2.CAPABILITY_UNKNOWN)), false, "none")
            return
        }
        val handled = try {
            listener.onSensorQuery(env.payload)
        } catch (t: Throwable) {
            Log.e(TAG, "sensor.query 处理器异常 request_id=$requestId", t)
            false
        }
        if (!handled) {
            sendSensorResult(requestId, status = "unavailable", data = emptyList(),
                truncated = false, source = "none")
        }
    }

    /**
     * notification.event（§13.1）：会话内按 event_id 去重（多通道送达只展示一次）；
     * 到达自动回 received 回执（displayed/opened 等后续状态由业务层上报）。
     * 重复事件同样分发给监听器：业务层（NotificationEventHandler）依持久 Inbox
     * 去重不会重复展示，并按落库当前状态重发终态回执（displayed/failed 等），
     * 修复终态回执丢失后 core 补传无法收敛的问题。
     */
    private fun onNotificationEventLocked(env: EnvelopeV2) {
        val p = env.payload
        val eventId = p["event_id"] as? String
        val notificationId = p["notification_id"] as? String
        if (eventId == null || !IdsV2.EVENT_ID.matches(eventId) ||
            notificationId == null || !IdsV2.NOTIFICATION_ID.matches(notificationId)
        ) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad notification.event")
            return
        }
        if (recentNotifEventIds.put(eventId, true) != null) {
            // 重复事件（§13 多通道送达/core 补传）：重发 received 让 core 停发
            // （监听器无业务处理时的兼容语义）；仍分发给业务层——
            // NotificationEventHandler 依持久 Inbox 去重不会重复展示，
            // 并按落库当前状态重发 displayed/failed 等终态回执，让 core 收敛。
            Log.d(TAG, "重复 notification.event（event_id=$eventId），重发 received 并分发业务层恢复终态")
            sendNotificationReceipt(eventId, notificationId, "received")
            postMain { listener.onNotificationEvent(p) }
            return
        }
        if (lastNotifEventId == null || eventId > lastNotifEventId!!) {
            lastNotifEventId = eventId
        }
        sendNotificationReceipt(eventId, notificationId, "received")
        postMain { listener.onNotificationEvent(p) }
    }

    private fun onNotificationRevokeLocked(env: EnvelopeV2) {
        val notificationId = env.payload["notification_id"] as? String
        if (notificationId == null || !IdsV2.NOTIFICATION_ID.matches(notificationId)) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad notification.revoke")
            return
        }
        postMain { listener.onNotificationRevoke(env.payload) }
    }

    private fun onRecordingControlLocked(env: EnvelopeV2) {
        val runId = env.payload["run_id"] as? String
        val op = env.payload["op"] as? String
        if (runId == null || !IdsV2.RUN_ID.matches(runId) ||
            op !in setOf("start", "pause", "resume", "stop")
        ) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad recording.control")
            return
        }
        postMain { listener.onRecordingControl(env.payload) }
    }

    private fun onConfirmRequestLocked(env: EnvelopeV2) {
        val challengeId = env.payload["challenge_id"] as? String
        if (challengeId == null || !Ulid.isValid(challengeId)) {
            sendErrorLocked(env.id, ErrorCodeV2.INVALID_MESSAGE, "bad device.confirm.request")
            return
        }
        postMain { listener.onConfirmRequest(env.payload) }
    }

    private fun onErrorLocked(env: EnvelopeV2) {
        val code = env.payload["code"] as? String ?: ErrorCodeV2.INTERNAL
        val message = env.payload["message"] as? String ?: ""
        val refId = env.payload["ref_id"] as? String ?: ""
        Log.w(TAG, "协议错误 code=$code message=$message ref=$refId")
        postMain { listener.onProtocolError(code, message, refId) }
        when (code) {
            ErrorCodeV2.DEVICE_REVOKED -> {
                // §16：撤销后队列与幂等表清空；不重连（需重新配对）。
                outbox.clear()
                dispatcher.onDeviceRevoked()
                voluntaryClose = true
                ws?.close(1000, "device revoked")
                transition(State.DISCONNECTED)
            }
            ErrorCodeV2.AUTH_FAILED -> {
                voluntaryClose = true
                ws?.close(1000, "auth failed")
                transition(State.DISCONNECTED)
            }
        }
    }

    // ------------------------------------------------------------- 发送

    /** 构造上行信封并写入 WS；返回是否成功写入。仅 executor 线程调用。 */
    private fun sendEnvelopeLocked(type: String, payload: Map<String, Any?>): Boolean {
        val socket = ws ?: return false
        val env = EnvelopeV2.uplink(type, upSeq.next(), payload, clock())
        val json = try {
            env.toJson().also { EnvelopeCodecV2.checkFrameSize(it) }
        } catch (e: EnvelopeExceptionV2) {
            Log.w(TAG, "上行帧构造失败: ${e.message}")
            return false
        }
        return socket.send(json)
    }

    private fun sendErrorLocked(refId: String, code: String, message: String) {
        val payload = LinkedHashMap<String, Any?>()
        payload["ref_id"] = refId
        payload["code"] = code
        payload["message"] = message.take(500)
        sendEnvelopeLocked(MsgTypeV2.ERROR, payload)
    }

    /** 排空 outbox（ONLINE 时调用；SENT 待 ack，断线由 resetSentToPending 回退重发）。 */
    private fun flushOutboxLocked() {
        while (true) {
            val batch = outbox.pendingBatch()
            if (batch.isEmpty()) return
            var sentAny = false
            for (entry in batch) {
                // 毒丸防护：event_id 不满足 v2 格式的条目必然被服务端拒绝且永不 ack。
                if (!IdsV2.EVENT_ID.matches(entry.eventId)) {
                    Log.w(TAG, "队列条目 event_id 不满足 v2 格式，丢弃: ${entry.eventId}")
                    outbox.markSent(entry.eventId)
                    outbox.markAcked(listOf(entry.eventId))
                    continue
                }
                val payload = try {
                    EnvelopeCodecV2.payloadFromJson(entry.payloadJson)
                } catch (e: Exception) {
                    Log.w(TAG, "队列条目 payload 损坏，丢弃 event_id=${entry.eventId}")
                    outbox.markSent(entry.eventId)
                    outbox.markAcked(listOf(entry.eventId))
                    continue
                }
                if (sendEnvelopeLocked(entry.type, payload)) {
                    outbox.markSent(entry.eventId)
                    sentAny = true
                } else {
                    return // WS 不可用，留待下次连接重发
                }
            }
            if (!sentAny) return
        }
    }

    // ------------------------------------------------------------- 心跳（§1/§6）

    private fun startHeartbeatLocked() {
        stopHeartbeat()
        val interval = heartbeatIntervalSec.toLong()
        heartbeatFuture = executor.scheduleWithFixedDelay({
            if (state != State.ONLINE) return@scheduleWithFixedDelay
            // 看门狗：3 倍心跳间隔无任何下行帧视为僵死（§1 基线 90s@30s 间隔）。
            val silentMs = SystemClock.elapsedRealtime() - lastInboundMonoMs
            if (silentMs > interval * 3_000) {
                Log.w(TAG, "超过 ${interval * 3}s 无下行帧，强制重连")
                ws?.cancel() // 触发 onFailure → 重连流程
                return@scheduleWithFixedDelay
            }
            val hs = heartbeatStatusProvider()
            val payload = LinkedHashMap<String, Any?>()
            payload["device_id"] = identity.getDeviceId()
            payload["observed_at"] = clock()
            payload["status"] = hs.status
            hs.battery?.let { payload["battery"] = it.coerceIn(0, 100) }
            hs.network?.let { payload["network"] = it }
            if (hs.modules.isNotEmpty()) payload["modules"] = hs.modules
            if (sendEnvelopeLocked(MsgTypeV2.DEVICE_HEARTBEAT, payload)) {
                // 心跳写成功也算链路活性证据（WS ping/pong 已覆盖对端死亡检测，
                // 看门狗只须防「本地完全静默」这一失效模式）。
                lastInboundMonoMs = SystemClock.elapsedRealtime()
            }
        }, interval, interval, TimeUnit.SECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    // ------------------------------------------------------------- 状态

    private fun transition(newState: State) {
        if (state == newState) return
        state = newState
        val info = getStateInfo()
        postMain { listener.onStateChanged(newState, info) }
    }

    private fun listenerHasUiHandler(): Boolean = hasUiHandlerOverride?.invoke() ?: false

    /** UI 动作处理器存在性探针（由运行时/桥注入；默认 false → 无执行器动作立即 unavailable）。 */
    @Volatile
    var hasUiHandlerOverride: (() -> Boolean)? = null

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    // ------------------------------------------------------------- 测试钩子（勿用于生产路径）

    @androidx.annotation.VisibleForTesting
    internal fun injectSocketForTest(socket: WebSocket) {
        ws = socket
    }

    @androidx.annotation.VisibleForTesting
    internal fun forceStateForTest(s: State) {
        state = s
    }

    @androidx.annotation.VisibleForTesting
    internal fun onInboundTextForTest(text: String) = handleTextLocked(text)

    @androidx.annotation.VisibleForTesting
    internal fun executorForTest(): ScheduledExecutorService = executor

    companion object {
        private const val TAG = "WhaleWssV2"
        const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 30
        private const val NOTIF_DEDUP_CAPACITY = 512

        /** 无法取得 ref_id 时的占位（合法 ULID 字符集全零）。 */
        const val ZERO_ULID = "00000000000000000000000000"

        /** §13.2 notification.receipt.status 枚举。 */
        val NOTIFICATION_RECEIPT_STATUSES: Set<String> = setOf(
            "received", "displayed", "opened", "dismissed",
            "action_submitted", "expired", "failed",
        )

        fun stateName(s: State): String = when (s) {
            State.DISCONNECTED -> "disconnected"
            State.CONNECTING -> "connecting"
            State.AWAITING_AUTH -> "awaiting_auth"
            State.ONLINE -> "online"
            State.DEGRADED -> "degraded"
        }

        /**
         * 组装 device.hello payload（§5.2/schema；字段顺序与黄金示例一致）。
         * 独立为 internal 纯函数以便单元测试直接校验组装结果。
         */
        internal fun buildHelloPayload(
            config: HelloConfig,
            deviceId: String,
            challenge: String,
            signature: String,
        ): Map<String, Any?> {
            require(config.appFlavor in setOf("daily", "root")) {
                "app_flavor 必须为 daily/root: ${config.appFlavor}"
            }
            val payload = LinkedHashMap<String, Any?>()
            payload["protocol_version"] = PROTOCOL_VERSION_V2
            payload["device_id"] = deviceId
            payload["capabilities"] = config.capabilities.toList()
            payload["features"] = config.features.toPayload()
            payload["device"] = linkedMapOf(
                "model" to config.deviceModel,
                "android_release" to config.androidRelease,
                "api_level" to config.apiLevel,
            )
            payload["app_version"] = config.appVersion
            payload["app_flavor"] = config.appFlavor
            payload["signature"] = signature
            payload["challenge"] = challenge
            return payload
        }
    }
}
