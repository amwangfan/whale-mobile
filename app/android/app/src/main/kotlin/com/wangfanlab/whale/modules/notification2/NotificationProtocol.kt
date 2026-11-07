package com.wangfanlab.whale.modules.notification2

import com.google.gson.Gson
import java.security.SecureRandom

/**
 * contracts/v2 §13 上行消息（notification.receipt / notification.action / notification.sync）
 * 的组包与 ID 生成。纯 JVM 实现，可在本地单元测试运行。
 *
 * 上行发送抽象 [NotificationUplinkSender]：本模块只定义接口，不依赖 WssClientV2
 * （并行任务）。实现方收到的是**完整 v2 信封 JSON 字符串**（v/id/type/ts/seq/dir/payload），
 * 可直接透传；seq 缺省为 0，允许传输层按当前连接重写（seq 语义属连接级，§2）。
 */

/** 上行发送注入点。payload 为完整 v2 信封 JSON（UTF-8）。发送失败应抛异常，调用方负责落 Outbox。 */
fun interface NotificationUplinkSender {
    suspend fun send(payload: String)
}

/**
 * ULID（26 字符 Crockford Base32：48bit 毫秒时间 + 80bit 随机），
 * 满足 v2 信封 id 与 evt_/notif_ 前缀后体的 ^[0-9A-HJKMNP-TV-Z]{26}$ 约束。
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_MASK = (1L shl 48) - 1
    private val random = SecureRandom()

    fun newUlid(nowMs: Long = System.currentTimeMillis()): String {
        val chars = CharArray(26)
        var t = nowMs and TIME_MASK
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        for (i in 25 downTo 10) {
            chars[i] = ALPHABET[random.nextInt(32)]
        }
        return String(chars)
    }

    fun newEventId(): String = "evt_" + newUlid()
}

/** v2 上行信封组包（字段顺序与 schema 对齐，便于抓包比对黄金示例）。 */
object NotificationProtocol {
    const val PROTOCOL_VERSION = "2.0"
    const val DIR_UPLINK = "uplink"

    private val gson = Gson()

    /** notification.receipt（§13.2）。status ∈ received/displayed/opened/dismissed/action_submitted/expired/failed。 */
    fun buildReceipt(
        eventId: String,
        notificationId: String,
        deviceId: String,
        status: String,
        atMs: Long,
        seq: Long = 0,
    ): String {
        val payload = LinkedHashMap<String, Any?>(5)
        payload["event_id"] = eventId
        payload["notification_id"] = notificationId
        payload["device_id"] = deviceId
        payload["status"] = status
        payload["received_at"] = atMs
        return envelope("notification.receipt", payload, atMs, seq)
    }

    /** notification.action（§13.3）：通知按钮操作回传。 */
    fun buildAction(
        eventId: String,
        notificationId: String,
        deviceId: String,
        actionId: String,
        submittedAt: Long,
        seq: Long = 0,
    ): String {
        val payload = LinkedHashMap<String, Any?>(5)
        payload["event_id"] = eventId
        payload["notification_id"] = notificationId
        payload["device_id"] = deviceId
        payload["action_id"] = actionId
        payload["submitted_at"] = submittedAt
        return envelope("notification.action", payload, submittedAt, seq)
    }

    /** notification.sync（§13.3）：重连后同步位置上报。lastEventId 为 null 表示无历史。 */
    fun buildSync(
        deviceId: String,
        lastEventId: String?,
        syncedAt: Long,
        seq: Long = 0,
    ): String {
        val payload = LinkedHashMap<String, Any?>(3)
        payload["device_id"] = deviceId
        if (lastEventId != null) payload["last_event_id"] = lastEventId
        payload["synced_at"] = syncedAt
        return envelope("notification.sync", payload, syncedAt, seq)
    }

    private fun envelope(type: String, payload: Map<String, Any?>, ts: Long, seq: Long): String {
        val root = LinkedHashMap<String, Any?>(7)
        root["v"] = PROTOCOL_VERSION
        root["id"] = Ulid.newUlid(ts)
        root["type"] = type
        root["ts"] = ts
        root["seq"] = seq
        root["dir"] = DIR_UPLINK
        root["payload"] = payload
        return gson.toJson(root)
    }
}
