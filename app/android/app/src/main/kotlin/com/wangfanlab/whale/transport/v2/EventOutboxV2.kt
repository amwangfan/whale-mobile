package com.wangfanlab.whale.transport.v2

import android.content.Context
import android.util.Log

/**
 * experience.event 上行队列（contracts/v2 §9/§16）。
 *
 * 底层为 [OfflineQueue] 的加密持久化机制（payload 应用层 AES/GCM 加密、
 * 容量治理、fail-closed 丢弃、drop_on_pause；该类自 v1 栈迁入本包），
 * 使用独立 DB 文件（[DEFAULT_DB_NAME]）。
 *
 * 语义（§16）：
 * - event_id 即幂等键（`evt_`+ULID），重复入队直接忽略；core 侧再去重；
 * - 至少一次送达：PENDING → SENT（写入 WS）→ 收到 event.ack 后 ACKED 停发；
 *   断线重连时 SENT 回退 PENDING 重发（重复由 core 按 event_id 吸收）；
 * - 暂停期（heartbeat status=paused）标记 drop_on_pause 的事件不补传私人历史，
 *   由 [purgeDropOnPause] 在恢复前清除；
 * - 只存 payload 不存信封：id/ts/seq 在实际发送时生成，每次重发都是合法新帧。
 */
class EventOutboxV2(
    context: Context,
    cipher: QueueCipher?,
    private val deviceIdProvider: () -> String,
    dbName: String = DEFAULT_DB_NAME,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val queue = OfflineQueue(context, dbName = dbName, capacity = capacity, cipher = cipher)

    data class Stats(
        val pending: Int,
        val sent: Int,
        val acked: Int,
        val dropped: Long,
    )

    /**
     * 组装并入队一条 experience.event（§9 payload）。
     *
     * @param eventId 幂等键；缺省新生成。毒丸防护：不满足 `evt_`+ULID 格式的
     *   调用方 ID 会被替换为新生 ID（必然被服务端 schema 拒绝且永不 ack 的条目
     *   不允许进入队列）。
     * @return 实际使用的 event_id
     */
    fun enqueueExperienceEvent(
        kind: String,
        occurredAt: Long,
        source: String,
        summary: String,
        privacyClass: String,
        evidenceRef: String? = null,
        taskId: String? = null,
        runId: String? = null,
        extras: Map<String, Any?>? = null,
        schemaVersion: String = SCHEMA_VERSION,
        dropOnPause: Boolean = false,
        eventId: String = IdsV2.newEventId(),
    ): String {
        val effectiveEventId = if (IdsV2.EVENT_ID.matches(eventId)) eventId else {
            Log.w(TAG, "event_id 不满足 v2 格式（evt_+ULID），换发新幂等键: $eventId")
            IdsV2.newEventId()
        }
        // 字段顺序与 schema/黄金示例一致（LinkedHashMap 保序，便于抓包比对）。
        val payload = LinkedHashMap<String, Any?>()
        payload["event_id"] = effectiveEventId
        payload["kind"] = kind
        payload["occurred_at"] = occurredAt
        payload["device_id"] = deviceIdProvider()
        payload["source"] = source
        payload["summary"] = summary
        payload["privacy_class"] = privacyClass
        evidenceRef?.let { payload["evidence_ref"] = it }
        taskId?.let { payload["task_id"] = it }
        runId?.let { payload["run_id"] = it }
        payload["schema_version"] = schemaVersion
        extras?.let { payload["extras"] = it }
        queue.enqueue(
            effectiveEventId, MsgTypeV2.EXPERIENCE_EVENT,
            EnvelopeCodecV2.payloadToJson(payload), dropOnPause,
        )
        return effectiveEventId
    }

    /** 取一批待发送条目（PENDING，按入队时间升序；payload 已解密）。 */
    fun pendingBatch(limit: Int = FLUSH_BATCH): List<OfflineQueue.Entry> = queue.pendingBatch(limit)

    /** 已写入 WS（等待 event.ack）。 */
    fun markSent(eventId: String): Boolean = queue.markSent(eventId)

    /** event.ack 确认（含去重命中）：SENT→ACKED 停发；返回确认条数。 */
    fun markAcked(eventIds: Collection<String>): Int {
        var n = 0
        for (id in eventIds) if (queue.markAcked(id)) n++
        return n
    }

    /** 重连后调用：所有「已发送待确认」回退 PENDING（至少一次送达）。 */
    fun resetSentToPending(): Int = queue.resetSentToPending()

    /** 进入 paused 状态时调用：丢弃所有 drop_on_pause 未送达事件（§16 不补传私人历史）。 */
    fun purgeDropOnPause(): Int = queue.purgeDropOnPause()

    fun stats(): Stats = queue.stats().let { Stats(it.pending, it.sent, it.acked, it.dropped) }

    /** 撤销设备时调用（§16：撤销后队列清空）。 */
    fun clear() = queue.clear()

    fun close() = queue.close()

    companion object {
        private const val TAG = "WhaleOutboxV2"
        private const val FLUSH_BATCH = 100

        /** experience.event payload 的 schema_version（§9 对象合同版本）。 */
        const val SCHEMA_VERSION = "2.0"
        const val DEFAULT_DB_NAME = "whale_transport_v2.db"
        const val DEFAULT_CAPACITY = 1000
    }
}
