package com.wangfanlab.whale.transport

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WSS 控制通道客户端（contracts/v1 §1-§4）。
 *
 * 握手时序（与 server/dev_gateway.py 对齐）：
 *   connect → TCP/WS 建立（CONNECTING → AWAITING_AUTH）
 *   ← auth.challenge{challenge: hex}（服务端首帧）
 *   → device.hello{signature: Ed25519(challenge UTF-8 字节), challenge, ...}（seq=1）
 *   ← device.hello_ack{session_id, heartbeat_interval, accepted_capabilities}（→ ONLINE）
 *   → device.heartbeat 每 heartbeat_interval 秒（默认 30s）
 *
 * - seq：上行每连接从 1 单调递增；下行观察记录（跳变仅告警不拒绝，与网关一致）。
 * - 断线：指数退避重连（1s→60s 封顶，±20% 抖动，见 ReconnectBackoff）；
 *   曾 ONLINE 后掉线进入 DEGRADED 直至恢复；auth_failed/device_revoked 为终态不重试。
 * - 单帧上限 256 KiB（收发双向校验，§2）。
 * - action.request：端侧校验 V1 能力白名单 + accepted_capabilities + expires_at +
 *   idempotency_key 去重（§4/§6/D7），自动回 receipt(state=received)。
 *   校验通过后查 [ActionExecutor] 注册表：有执行器 → 同步执行并回终态
 *   （executed/failed，不转发 Dart）；无执行器 → 保持原行为，经
 *   [Listener.onActionRequest] 交 Dart 展示，终态由业务层经 sendActionReceipt 补发
 *   （「接受」≠「已执行」）。
 *
 * 线程模型：除构造/销毁外所有状态变更都在内部单线程 executor 上；
 * Listener 回调统一切到 mainHandler（默认主线程）。
 */
class WssClient(
    private val identity: DeviceIdentity,
    private val queue: OfflineQueue,
    private val listener: Listener,
    private val mainHandler: Handler,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    backoffFactory: () -> ReconnectBackoff = { ReconnectBackoff() },
) {

    enum class State { DISCONNECTED, CONNECTING, AWAITING_AUTH, ONLINE, DEGRADED }

    interface Listener {
        fun onStateChanged(state: State, info: Map<String, Any?>)
        fun onActionRequest(payload: Map<String, Any?>)
        fun onProtocolError(code: String, message: String, refId: String)

        /**
         * sensor.query 分发钩。返回 true = 有模块接管（稍后经 sendSensorResult 回复）；
         * 默认 false = 无处理器，客户端立即按 fail-closed 回 unavailable（§4 不允许静默丢弃）。
         */
        fun onSensorQuery(payload: Map<String, Any?>): Boolean = false

        /**
         * SEC-03：是否有 UI 侧处理器可承接「无原生执行器」的 action。
         * false 时客户端立即回 failed/unavailable（不再回 accepted 后悬挂）。
         */
        fun hasUiActionHandler(): Boolean = false

        /**
         * 本地执行器处理的 action 到达终态（executed/failed）时回调（已在主线程）。
         * 用于 UI 展示最近回执；不影响已上行的 receipt。
         */
        fun onActionTerminal(
            actionId: String,
            capability: String,
            state: String,
            errorCode: String?,
        ) {}

        /**
         * 下行 call.invite（contracts/v1 §4，payload: session_id/media_params/expires_at）。
         * 默认忽略（前向兼容）；VoipModule 经此接管来电会话（主线程回调）。
         */
        fun onCallInvite(payload: Map<String, Any?>) {}

        /**
         * 下行 call.hangup（payload: session_id/reason?）。默认忽略；
         * VoipModule 经此执行远端挂断（主线程回调）。
         */
        fun onCallHangup(payload: Map<String, Any?>) {}
    }

    /**
     * action 执行器（注册表模式，modules/notifications/DESIGN.md §4）。
     * 在 WssClient 完成传输层校验后被调用；实现必须恰好回一次终态
     * （reply("executed", null) 或 reply("failed", errorCode)）。
     * 调用线程为 WssClient executor 线程；实现如需切线程自行安排。
     */
    interface ActionExecutor {
        fun execute(
            payload: Map<String, Any?>,
            reply: (state: String, errorCode: String?, providerRef: String?) -> Unit,
        )
    }

    /** 心跳负载来源；默认仅报 online。 */
    data class HeartbeatStatus(
        val status: String = "online", // online / connected_only / paused
        val battery: Int? = null,
        val modules: Map<String, String> = emptyMap(),
    )

    @Volatile
    var heartbeatStatusProvider: () -> HeartbeatStatus = { HeartbeatStatus() }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "whale-wss") }
    private val backoff = backoffFactory()

    // ---- 仅 executor 线程写；getStateInfo 可跨线程读（@Volatile 保证可见性） ----
    @Volatile
    private var state = State.DISCONNECTED
    private var ws: WebSocket? = null
    private var url: String? = null
    private var capabilities: List<String> = emptyList()
    private var appVersion: String = "unknown"
    private var voluntaryClose = false
    private var destroyed = false
    private var hadOnlineThisSession = false
    private var upSeq = SeqCounter()
    private var downSeq = SeqCounter()
    /** 最近一次掉线原因（诊断；仅 executor 线程写，@Volatile 跨线程读）。 */
    @Volatile
    private var lastDisconnectReason: String? = null

    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var heartbeatIntervalSec = DEFAULT_HEARTBEAT_INTERVAL_SEC
    private var acceptedCapabilities: List<String> = emptyList()
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var lastInboundMonoMs = 0L
    private var serverTimeOffsetMs = 0L
    // action 执行器注册表：capability → 执行器（注册/注销可跨线程，读在 executor 线程）。
    private val actionExecutors = ConcurrentHashMap<String, ActionExecutor>()
    // 本进程内正在执行中的 idempotency_key（配合持久化 [queue] 区分
    // 「同会话在飞」与「跨重启结果不明」——后者回 unknown，绝不自动重放）。
    private val inFlightActionKeys = HashSet<String>()

    // ------------------------------------------------------------- 公共 API

    fun connect(url: String, capabilities: List<String>, appVersion: String) {
        executor.execute {
            if (destroyed) return@execute
            // 幂等连接：相同地址且已在线/建连中时不重开（防恢复路径与 UI
            // 自动连接竞态导致服务端踢旧会话的拉锯）。
            if (this.url == url && state != State.DISCONNECTED) {
                Log.i(TAG, "已处于 ${stateName(state)}（同地址），忽略重复 connect")
                return@execute
            }
            voluntaryClose = false
            this.url = url
            this.capabilities = capabilities
            this.appVersion = appVersion
            hadOnlineThisSession = false
            backoff.reset()
            cancelReconnect()
            openSocket()
        }
    }

    fun disconnect() {
        executor.execute {
            voluntaryClose = true
            cancelReconnect()
            stopHeartbeat()
            ws?.close(1000, "client disconnect")
            ws = null
            transition(State.DISCONNECTED)
        }
    }

    /** 注册 action 执行器（capability 维度，后者覆盖前者；连接前注册即可）。 */
    fun registerActionExecutor(capability: String, executor: ActionExecutor) {
        actionExecutors[capability] = executor
    }

    fun unregisterActionExecutor(capability: String) {
        actionExecutors.remove(capability)
    }

    fun destroy() {
        disconnect()
        executor.shutdown()
    }

    /**
     * 立即重连（BG-02 网络恢复回调用）：重置退避并立刻建连。
     * 仅在非主动断开、非销毁、当前不在线时生效。
     */
    fun reconnectNow() {
        executor.execute {
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
     * 上行 sensor.event：先入队（幂等），在线则立即尝试发送。
     * @return event_id（幂等键）
     */
    fun sendSensorEvent(
        kind: String,
        occurredAt: Long,
        privacyClass: String,
        summary: String,
        evidenceRef: String? = null,
        schemaVersion: String = "1.0",
        dropOnPause: Boolean = false,
        eventId: String = Ids.newEventId(),
    ): String {
        val payload = LinkedHashMap<String, Any?>()
        payload["event_id"] = eventId
        payload["kind"] = kind
        payload["occurred_at"] = occurredAt
        payload["privacy_class"] = privacyClass
        payload["summary"] = summary
        evidenceRef?.let { payload["evidence_ref"] = it }
        payload["schema_version"] = schemaVersion
        // BG-02 毒丸防护：契约要求 event_id ≥ 16 字符；过短 id 换发新幂等键，
        // 避免必然被服务端拒绝的条目进入队列。
        val effectiveEventId = if (eventId.length >= MIN_EVENT_ID_LEN) eventId else {
            Log.w(TAG, "event_id 长度过短（${eventId.length} < $MIN_EVENT_ID_LEN），换发新幂等键: $eventId")
            Ids.newEventId()
        }
        payload["event_id"] = effectiveEventId
        val payloadJson = EnvelopeCodec.payloadToJson(payload)
        executor.execute {
            queue.enqueue(effectiveEventId, TYPE_SENSOR_EVENT, payloadJson, dropOnPause)
            if (state == State.ONLINE) flushQueueLocked()
        }
        return effectiveEventId
    }

    /** 上行 action.receipt（尽力发送，不入队；core 侧等待自带超时）。 */
    fun sendActionReceipt(
        actionId: String,
        state: String,
        executedAt: Long? = null,
        errorCode: String? = null,
        providerRef: String? = null,
    ) {
        executor.execute {
            // SEC-03：业务层（Dart）终态也落幂等表，重连重放可返回真实终态。
            if (state == "executed" || state == "failed" || state == "unknown") {
                queue.setActionTerminalByActionId(actionId, state, errorCode)
            }
            val payload = LinkedHashMap<String, Any?>()
            payload["action_id"] = actionId
            payload["state"] = state
            if (executedAt != null && (state == "executed" || state == "failed")) {
                payload["executed_at"] = executedAt
            }
            if (errorCode != null && state == "failed") payload["error_code"] = errorCode
            if (providerRef != null) payload["provider_ref"] = providerRef
            sendEnvelopeLocked(TYPE_ACTION_RECEIPT, payload)
        }
    }

    fun getStateInfo(): Map<String, Any?> {
        // 允许任意线程调用；读取 @Volatile/不可变字段 + 队列统计。
        val s = queue.stats()
        return mapOf(
            "state" to stateName(state),
            "device_id" to identity.getDeviceId(),
            "public_key_hex" to identity.getPublicKeyHex(),
            "session_id" to sessionId,
            "heartbeat_interval" to heartbeatIntervalSec,
            "reconnect_attempts" to backoff.attempts,
            "last_disconnect_reason" to lastDisconnectReason,
            "queue_pending" to s.pending,
            "queue_sent" to s.sent,
            "queue_acked" to s.acked,
            "queue_dropped" to s.dropped,
        )
    }

    // ------------------------------------------------------------- WS 回调（OkHttp 线程 → executor）

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            executor.execute {
                lastInboundMonoMs = SystemClock.elapsedRealtime()
                transition(State.AWAITING_AUTH)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executor.execute { handleTextLocked(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            executor.execute { handleTransportLostLocked("closed code=$code reason=$reason") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            executor.execute {
                handleTransportLostLocked("failure: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------- 内部（全部 executor 线程）

    private fun openSocket() {
        val target = url ?: return
        ws?.cancel()
        ws = null
        stopHeartbeat()
        upSeq = SeqCounter()
        downSeq = SeqCounter()
        sessionId = null
        transition(if (hadOnlineThisSession) State.DEGRADED else State.CONNECTING)
        val request = Request.Builder().url(target).build()
        ws = okHttpClient.newWebSocket(request, wsListener)
    }

    private fun handleTransportLostLocked(why: String) {
        if (destroyed) return
        lastDisconnectReason = why
        stopHeartbeat()
        ws = null
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

    private fun handleTextLocked(text: String) {
        lastInboundMonoMs = SystemClock.elapsedRealtime()
        try {
            EnvelopeCodec.checkFrameSize(text)
        } catch (e: EnvelopeException) {
            Log.w(TAG, "下行帧超限，断开: ${e.message}")
            ws?.close(1009, "frame too large")
            return
        }
        val env = try {
            EnvelopeCodec.decode(text)
        } catch (e: EnvelopeException) {
            Log.w(TAG, "信封解析失败: ${e.message}")
            return
        }
        val major = EnvelopeCodec.majorOf(env.v)
        if (major == null || major != SUPPORTED_MAJOR) {
            Log.w(TAG, "未知协议 major=${env.v}，忽略（§2）")
            return
        }
        if (!downSeq.observeIncoming(env.seq)) {
            Log.w(TAG, "下行 seq 跳变/乱序: seq=${env.seq}（仅记录）")
        }
        when (env.type) {
            TYPE_AUTH_CHALLENGE -> onAuthChallengeLocked(env)
            TYPE_HELLO_ACK -> onHelloAckLocked(env)
            TYPE_ACTION_REQUEST -> onActionRequestLocked(env)
            TYPE_SENSOR_QUERY -> onSensorQueryLocked(env)
            TYPE_SENSOR_ACK -> onSensorAckLocked(env)
            TYPE_CALL_INVITE -> onCallInviteLocked(env)
            TYPE_CALL_HANGUP -> onCallHangupLocked(env)
            TYPE_ERROR -> onErrorLocked(env)
            else -> Log.d(TAG, "忽略未知/暂不支持的消息类型: ${env.type}")
        }
    }

    private fun onAuthChallengeLocked(env: Envelope) {
        if (state != State.AWAITING_AUTH) return
        val challenge = env.payload["challenge"] as? String ?: run {
            Log.w(TAG, "auth.challenge 缺少 challenge 字段")
            return
        }
        // §3/schema：对 challenge 字符串的 UTF-8 字节做 Ed25519 签名，小写 hex 输出。
        val signature = identity.signHex(challenge.toByteArray(Charsets.UTF_8))
        val payload = LinkedHashMap<String, Any?>()
        payload["protocol_version"] = PROTOCOL_VERSION
        payload["device_id"] = identity.getDeviceId()
        payload["capabilities"] = capabilities
        payload["app_version"] = appVersion
        payload["signature"] = signature
        payload["challenge"] = challenge
        sendEnvelopeLocked(TYPE_DEVICE_HELLO, payload)
    }

    private fun onHelloAckLocked(env: Envelope) {
        if (state != State.AWAITING_AUTH) return
        sessionId = env.payload["session_id"] as? String
        heartbeatIntervalSec = payloadLong(env.payload, "heartbeat_interval")
            ?.toInt()?.coerceIn(1, 3600) ?: DEFAULT_HEARTBEAT_INTERVAL_SEC
        @Suppress("UNCHECKED_CAST")
        acceptedCapabilities =
            (env.payload["accepted_capabilities"] as? List<*>)?.filterIsInstance<String>()
                ?: emptyList()
        val serverTime = payloadLong(env.payload, "server_time")
        if (serverTime != null) {
            serverTimeOffsetMs = serverTime - System.currentTimeMillis()
        }
        hadOnlineThisSession = true
        backoff.reset()
        transition(State.ONLINE)
        startHeartbeatLocked()
        queue.resetSentToPending()
        flushQueueLocked()
    }

    /** sensor.ack：服务端按 event_id 确认（含去重命中），队列 SENT→ACKED 停发（§6 闭环）。 */
    private fun onSensorAckLocked(env: Envelope) {
        val ids = (env.payload["event_ids"] as? List<*>)?.filterIsInstance<String>() ?: return
        var n = 0
        for (id in ids) {
            if (queue.markAcked(id)) n++
        }
        if (n > 0) Log.d(TAG, "sensor.ack 确认 $n 条")
    }

    /** sensor.query 到达：有模块接管则分发，否则 fail-closed 回 unavailable。 */
    private fun onSensorQueryLocked(env: Envelope) {
        val requestId = env.payload["request_id"] as? String ?: return
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

    /** 供感知模块回 sensor.result（成功/失败/拒绝统一入口）。data 为条目数组（schema：array<object>）。 */
    fun sendSensorResult(
        requestId: String,
        status: String, // ok / denied / unavailable / error
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    ) {
        executor.execute {
            val payload = LinkedHashMap<String, Any?>()
            payload["request_id"] = requestId
            payload["status"] = status
            payload["data"] = data
            payload["truncated"] = truncated
            payload["source"] = source
            sendEnvelopeLocked(TYPE_SENSOR_RESULT, payload)
        }
    }

    private fun onActionRequestLocked(env: Envelope) {
        val p = env.payload
        val actionId = p["action_id"] as? String ?: return
        val capability = p["capability"] as? String ?: return
        val idemKey = p["idempotency_key"] as? String ?: return
        val expiresAt = payloadLong(p, "expires_at") ?: 0L
        val now = System.currentTimeMillis()

        fun receipt(state: String, errorCode: String? = null) {
            sendActionReceipt(
                actionId, state,
                executedAt = if (state == "failed") now else null,
                errorCode = errorCode,
            )
        }

        // SEC-03：持久幂等 —— 重启后仍识别重复下发，绝不自动重放副作用。
        val existing = queue.getActionRecord(idemKey)
        if (existing != null) {
            when {
                // 同会话在飞：仅重发 received（执行中的副作用不会被打断/重复）
                inFlightActionKeys.contains(idemKey) -> {
                    Log.i(TAG, "重复 action.request（在飞，idempotency_key=$idemKey），重发 received")
                    receipt("received")
                }
                // 已有终态：重发真实终态回执（幂等，不重复执行）
                existing.terminalState != null -> {
                    Log.i(TAG, "重复 action.request（已终结=${existing.terminalState}），重发终态回执")
                    sendActionReceipt(
                        existing.actionId, existing.terminalState,
                        executedAt = now, errorCode = existing.errorCode,
                    )
                }
                // 跨重启发现「记录但未终结」：副作用结果不明 → unknown（人工核验，不重放）
                else -> {
                    Log.w(TAG, "跨重启发现未终结动作（idempotency_key=$idemKey），回 unknown 待人工核验")
                    queue.setActionTerminal(idemKey, "unknown", null)
                    sendActionReceipt(existing.actionId, "unknown", executedAt = now)
                }
            }
            return
        }
        queue.recordActionIfAbsent(idemKey, actionId, capability)
        fun failTerminal(code: String) {
            queue.setActionTerminal(idemKey, "failed", code)
            receipt("failed", code)
        }
        if (expiresAt <= now) { // expires_at=0（epoch）同样视为已过期，§6 过期不补执行
            failTerminal("expired")
            return
        }
        if (capability !in V1_CAPABILITIES) {
            failTerminal("capability_unknown")
            return
        }
        if (capability !in acceptedCapabilities) {
            failTerminal("scope_denied")
            return
        }
        val actionExecutor = actionExecutors[capability]
        if (actionExecutor == null) {
            // SEC-03：无原生执行器且 UI 不在 → 明确终态 unavailable（不留悬挂 accepted）；
            // UI 在 → received 后交 Dart，终态由业务层经 sendActionReceipt 补发并落幂等表。
            if (!listener.hasUiActionHandler()) {
                failTerminal("unavailable")
                return
            }
            receipt("received")
            postMain { listener.onActionRequest(p) }
            return
        }
        // 有执行器：received 确认收到后同步执行，由执行器回终态（executed/failed）。
        receipt("received")
        inFlightActionKeys.add(idemKey)
        val reply: (String, String?, String?) -> Unit = { state, errorCode, providerRef ->
            executor.execute {
                inFlightActionKeys.remove(idemKey)
                if (state == "executed" || state == "failed" || state == "unknown") {
                    queue.setActionTerminal(idemKey, state, errorCode)
                }
                val terminalAt =
                    if (state == "executed" || state == "failed") System.currentTimeMillis() else null
                sendActionReceipt(actionId, state, executedAt = terminalAt, errorCode = errorCode,
                    providerRef = providerRef)
                postMain { listener.onActionTerminal(actionId, capability, state, errorCode) }
            }
        }
        try {
            actionExecutor.execute(p, reply)
        } catch (t: Throwable) {
            Log.e(TAG, "action 执行器异常 capability=$capability action_id=$actionId", t)
            reply("failed", "internal", null)
        }
    }

    /**
     * 下行 call.invite（contracts/v1 §4）。仅做结构性检查（session_id 存在），
     * media_params/expires_at 语义校验由消费方（VoipModule，纯逻辑层可测）执行。
     * 无消费者时静默忽略（§3 前向兼容）。
     */
    private fun onCallInviteLocked(env: Envelope) {
        val sessionId = env.payload["session_id"] as? String ?: run {
            Log.w(TAG, "call.invite 缺少 session_id，忽略")
            return
        }
        Log.i(TAG, "call.invite session=$sessionId")
        postMain { listener.onCallInvite(env.payload) }
    }

    /** 下行 call.hangup：远端结束会话；分发消费方挂断本地连接。 */
    private fun onCallHangupLocked(env: Envelope) {
        val sessionId = env.payload["session_id"] as? String ?: run {
            Log.w(TAG, "call.hangup 缺少 session_id，忽略")
            return
        }
        Log.i(TAG, "call.hangup session=$sessionId")
        postMain { listener.onCallHangup(env.payload) }
    }

    private fun onErrorLocked(env: Envelope) {
        val code = env.payload["code"] as? String ?: "internal"
        val message = env.payload["message"] as? String ?: ""
        val refId = env.payload["ref_id"] as? String ?: ""
        Log.w(TAG, "协议错误 code=$code message=$message ref=$refId")
        postMain { listener.onProtocolError(code, message, refId) }
        when (code) {
            "device_revoked" -> {
                // §6：撤销后队列清空；不重连（需重新配对）
                queue.clear()
                voluntaryClose = true
                ws?.close(1000, "device revoked")
                transition(State.DISCONNECTED)
            }
            "auth_failed" -> {
                voluntaryClose = true
                ws?.close(1000, "auth failed")
                transition(State.DISCONNECTED)
            }
        }
    }

    // ---- 发送 ----

    /** 构造上行信封并写入 WS；返回是否成功写入。仅 executor 线程调用。 */
    private fun sendEnvelopeLocked(type: String, payload: Map<String, Any?>): Boolean {
        val socket = ws ?: return false
        val env = Envelope.uplink(type, upSeq.next(), payload, System.currentTimeMillis())
        val json = try {
            env.toJson().also { EnvelopeCodec.checkFrameSize(it) }
        } catch (e: EnvelopeException) {
            Log.w(TAG, "上行帧构造失败: ${e.message}")
            return false
        }
        return socket.send(json)
    }

    /** 排空离线队列（ONLINE 时调用）。 */
    private fun flushQueueLocked() {
        while (true) {
            val batch = queue.pendingBatch(QUEUE_FLUSH_BATCH)
            if (batch.isEmpty()) return
            var sentAny = false
            for (entry in batch) {
                // BG-02 毒丸防护：event_id 不满足契约（minLength 16）的条目必然被
                // 服务端 schema 拒绝且永不 ACK，会随重连无限重发——入队/ flush 双侧
                // 拦截，按「已终结」处理（markSent+markAcked）并记日志。
                if (entry.eventId.length < MIN_EVENT_ID_LEN) {
                    Log.w(TAG, "队列条目 event_id 不满足契约（长度 ${entry.eventId.length} < $MIN_EVENT_ID_LEN），丢弃: ${entry.eventId}")
                    queue.markSent(entry.eventId)
                    queue.markAcked(entry.eventId)
                    continue
                }
                val payload = try {
                    EnvelopeCodec.payloadFromJson(entry.payloadJson)
                } catch (e: Exception) {
                    Log.w(TAG, "队列条目 payload 损坏，丢弃 event_id=${entry.eventId}")
                    queue.markSent(entry.eventId)
                    queue.markAcked(entry.eventId)
                    continue
                }
                if (sendEnvelopeLocked(entry.type, payload)) {
                    queue.markSent(entry.eventId)
                    sentAny = true
                } else {
                    return // WS 不可用，留待下次连接重发
                }
            }
            if (!sentAny) return
        }
    }

    // ---- 心跳 ----

    private fun startHeartbeatLocked() {
        stopHeartbeat()
        val interval = heartbeatIntervalSec.toLong()
        heartbeatFuture = executor.scheduleWithFixedDelay({
            if (state != State.ONLINE) return@scheduleWithFixedDelay
            // 看门狗：3 倍心跳间隔无任何下行帧视为僵死（§2 基线 90s@30s 间隔）
            val silentMs = SystemClock.elapsedRealtime() - lastInboundMonoMs
            if (silentMs > interval * 3_000) {
                Log.w(TAG, "超过 ${interval * 3}s 无下行帧，强制重连")
                ws?.cancel() // 触发 onFailure → 重连流程
                return@scheduleWithFixedDelay
            }
            val hs = heartbeatStatusProvider()
            val payload = LinkedHashMap<String, Any?>()
            payload["device_id"] = identity.getDeviceId()
            payload["observed_at"] = System.currentTimeMillis()
            payload["status"] = hs.status
            hs.battery?.let { payload["battery"] = it.coerceIn(0, 100) }
            if (hs.modules.isNotEmpty()) payload["modules"] = hs.modules
            if (sendEnvelopeLocked(TYPE_DEVICE_HEARTBEAT, payload)) {
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

    // ---- 状态 ----

    private fun transition(newState: State) {
        if (state == newState) return
        state = newState
        val info = getStateInfo()
        postMain { listener.onStateChanged(newState, info) }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        private const val TAG = "WhaleWssClient"
        const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 30
        private const val QUEUE_FLUSH_BATCH = 100

        /** contracts/v1 sensor.event event_id 最小长度（毒丸防护阈值）。 */
        private const val MIN_EVENT_ID_LEN = 16

        const val TYPE_AUTH_CHALLENGE = "auth.challenge"
        const val TYPE_DEVICE_HELLO = "device.hello"
        const val TYPE_HELLO_ACK = "device.hello_ack"
        const val TYPE_DEVICE_HEARTBEAT = "device.heartbeat"
        const val TYPE_SENSOR_EVENT = "sensor.event"
        const val TYPE_SENSOR_ACK = "sensor.ack"
        const val TYPE_SENSOR_QUERY = "sensor.query"
        const val TYPE_SENSOR_RESULT = "sensor.result"
        const val TYPE_ACTION_REQUEST = "action.request"
        const val TYPE_ACTION_RECEIPT = "action.receipt"
        const val TYPE_CALL_INVITE = "call.invite"
        const val TYPE_CALL_ANSWER = "call.answer"
        const val TYPE_CALL_HANGUP = "call.hangup"
        const val TYPE_CALL_STATE = "call.state"
        const val TYPE_ERROR = "error"

        /** §5 V1 能力全集（端侧白名单，D7）。 */
        val V1_CAPABILITIES: Set<String> = setOf(
            "screen.context.read", "screen.history.search", "screen.evidence.read",
            "screen.live.subscribe", "phone.notification.post", "phone.notification.read",
            "phone.call.ring", "phone.call.audio", "phone.device.safe_action",
        )

        fun stateName(s: State): String = when (s) {
            State.DISCONNECTED -> "disconnected"
            State.CONNECTING -> "connecting"
            State.AWAITING_AUTH -> "awaiting_auth"
            State.ONLINE -> "online"
            State.DEGRADED -> "degraded"
        }

        /** payload 中的整数字段（Gson Map 解码后为 Number，兼容 Long/Double）。 */
        fun payloadLong(p: Map<String, Any?>, key: String): Long? =
            (p[key] as? Number)?.toLong()
    }
}
