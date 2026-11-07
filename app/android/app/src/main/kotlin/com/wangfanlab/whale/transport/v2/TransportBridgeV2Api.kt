package com.wangfanlab.whale.transport.v2

/**
 * Flutter 桥 V2 接口层——**Kotlin 侧预留**。
 *
 * Dart 侧声明与 UI 重构后续做；本文件先固定 Kotlin 侧契约，使未来的
 * MethodChannel（拟 `whale/transport_v2`）只做薄代理，不再携带协议逻辑。
 * 事件方向（原生 → Dart）沿用 Map 载荷经 [WssClientV2.Listener] 扇出，
 * 由桥层在接入时映射到 EventChannel。
 *
 * 计划方法面（与 v1 TransportBridge 对齐，语义按 contracts/v2）：
 * - connectV2({url, capabilities?}) → {device_id, public_key_hex}
 *     capabilities 缺省按 CapabilityRegistry 当前变体集合；
 * - disconnectV2() → null；
 * - sendExperienceEvent({kind, occurred_at, source, summary, privacy_class,
 *     evidence_ref?, task_id?, run_id?, extras?, drop_on_pause?, event_id?}) → {event_id}；
 * - sendActionReceiptV2({action_id, status, error_code?, result_summary?,
 *     output_ref?, verification_status?, verification_summary?, provider_ref?}) → true
 *     （status 为五终态之一；first-terminal-wins 由 ReceiptDispatcherV2 保证）；
 * - sendNotificationReceipt({event_id, notification_id, status, received_at?}) → true；
 * - sendNotificationAction({event_id, notification_id, action_id}) → true；
 * - sendSensorResultV2({request_id, status, data[], truncated, source}) → true；
 * - setStatusV2({status: online|connected_only|paused}) → true
 *     （paused 时清理 outbox 的 drop_on_pause 事件）；
 * - getStateV2() → WssClientV2.getStateInfo 字段集；
 * - getIdentityV2() → {device_id, public_key_hex}。
 *
 * 事件面（原生 → Dart，由 [WssClientV2.Listener] 映射）：
 * - {kind: "state", state, ...getStateV2 字段}
 * - {kind: "action_request", action_id, task_id?, run_id?, capability, arguments, ...}
 * - {kind: "action_receipt", action_id, capability, status, error_code?}
 * - {kind: "sensor_query", request_id, operation, filters, max_items, deadline}
 * - {kind: "notification_event" / "notification_revoke", ...payload}
 * - {kind: "recording_control", run_id, op, options?}
 * - {kind: "confirm_request", challenge_id, channel, scopes, ...}
 * - {kind: "error", code, message, ref_id}
 */
interface TransportV2Api {

    /** 建立 v2 控制通道（幂等；capabilities 由调用方按构建变体给全量，core 可收窄）。 */
    fun connect(url: String, helloConfig: WssClientV2.HelloConfig)

    fun disconnect()

    /** 上行 experience.event；返回实际使用的 event_id（幂等键）。 */
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
    ): String

    /** 业务层终态回执（UI 委托动作的完成入口）。 */
    fun sendActionReceipt(
        actionId: String,
        status: ReceiptStatus,
        errorCode: String? = null,
        resultSummary: String? = null,
        outputRef: String? = null,
        verificationStatus: String? = null,
        verificationSummary: String? = null,
        providerRef: String? = null,
    )

    fun sendNotificationReceipt(
        eventId: String,
        notificationId: String,
        status: String,
        receivedAt: Long = System.currentTimeMillis(),
    )

    fun sendNotificationAction(eventId: String, notificationId: String, actionId: String)

    fun sendSensorResult(
        requestId: String,
        status: String,
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    )

    /** online/connected_only/paused；paused 时清理 outbox 的 drop_on_pause 事件（§16）。 */
    fun setStatus(status: String)

    fun getState(): Map<String, Any?>
}

/**
 * [TransportV2Api] 的默认实现：直接代理到一个存活的 [WssClientV2]。
 * 持有方（未来的 NodeRuntime V2 接线）负责客户端生命周期；本 facade 不做重连决策。
 */
class TransportV2Facade(
    private val client: WssClientV2,
) : TransportV2Api {

    @Volatile
    private var heartbeatStatus: WssClientV2.HeartbeatStatusV2 =
        WssClientV2.HeartbeatStatusV2()

    init {
        client.heartbeatStatusProvider = { heartbeatStatus }
    }

    override fun connect(url: String, helloConfig: WssClientV2.HelloConfig) =
        client.connect(url, helloConfig)

    override fun disconnect() = client.disconnect()

    override fun sendExperienceEvent(
        kind: String,
        occurredAt: Long,
        source: String,
        summary: String,
        privacyClass: String,
        evidenceRef: String?,
        taskId: String?,
        runId: String?,
        extras: Map<String, Any?>?,
        dropOnPause: Boolean,
        eventId: String,
    ): String = client.sendExperienceEvent(
        kind, occurredAt, source, summary, privacyClass,
        evidenceRef, taskId, runId, extras, dropOnPause, eventId,
    )

    override fun sendActionReceipt(
        actionId: String,
        status: ReceiptStatus,
        errorCode: String?,
        resultSummary: String?,
        outputRef: String?,
        verificationStatus: String?,
        verificationSummary: String?,
        providerRef: String?,
    ) = client.sendActionReceipt(
        actionId, status, errorCode,
        resultSummary, outputRef, verificationStatus, verificationSummary, providerRef,
    )

    override fun sendNotificationReceipt(
        eventId: String,
        notificationId: String,
        status: String,
        receivedAt: Long,
    ) = client.sendNotificationReceipt(eventId, notificationId, status, receivedAt)

    override fun sendNotificationAction(eventId: String, notificationId: String, actionId: String) =
        client.sendNotificationAction(eventId, notificationId, actionId)

    override fun sendSensorResult(
        requestId: String,
        status: String,
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    ) = client.sendSensorResult(requestId, status, data, truncated, source)

    override fun setStatus(status: String) {
        require(status in setOf("online", "connected_only", "paused")) { "非法心跳状态: $status" }
        heartbeatStatus = WssClientV2.HeartbeatStatusV2(status = status)
        if (status == "paused") {
            // §16：暂停期不补传私人历史（drop_on_pause 事件清除）。
            client.clearDropOnPauseEvents()
        }
    }

    override fun getState(): Map<String, Any?> = client.getStateInfo()
}
