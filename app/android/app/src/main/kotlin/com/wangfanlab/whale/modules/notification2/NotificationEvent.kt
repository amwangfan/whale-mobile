package com.wangfanlab.whale.modules.notification2

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * contracts/v2 §13 notification.event 的端侧模型与解析（纯 JVM + Gson，可单测）。
 *
 * 协议事实源：contracts/v2/messages/notification.event.schema.json。
 * 端侧解析是防御性的：schema 校验在传输层（WssClientV2，并行任务）做，
 * 这里再做一遍关键字段兜底，并按协议上限截断 title/body。
 */

/** 通知操作按钮（schema actions[]，最多 4 个）。 */
data class NotificationAction(
    val actionId: String,
    val label: String,
)

/** 点击跳转目标（schema target）。 */
data class NotificationTarget(
    /** task / conversation / device / approval / chat / none。 */
    val type: String,
    val id: String?,
)

/** 解析后的 notification.event payload。 */
data class NotificationEvent(
    val eventId: String,
    val notificationId: String,
    val deviceId: String,
    val category: String,
    /** 已按协议上限截断（≤200）。 */
    val title: String,
    /** 已按协议上限截断（≤2000）。 */
    val body: String,
    /** low / normal / high。 */
    val priority: String,
    val createdAt: Long,
    val expiresAt: Long,
    val target: NotificationTarget?,
    val actions: List<NotificationAction>,
    val groupKey: String?,
    val collapseKey: String?,
) {
    /** 审批类：修正方案 §3.3/§3.4 —— 带批准/拒绝操作按钮，高优先级。 */
    val isApproval: Boolean get() = category == CATEGORY_APPROVAL

    /** 任务进度类：合并更新不连弹（同 collapse_key 同系统通知 id + onlyAlertOnce）。 */
    val isTaskProgress: Boolean get() = category == CATEGORY_TASK_PROGRESS

    val isChat: Boolean get() = category == CATEGORY_CHAT

    companion object {
        const val CATEGORY_CHAT = "chat.message"
        const val CATEGORY_TASK_STARTED = "task.started"
        const val CATEGORY_TASK_PROGRESS = "task.progress"
        const val CATEGORY_TASK_COMPLETED = "task.completed"
        const val CATEGORY_TASK_FAILED = "task.failed"
        const val CATEGORY_APPROVAL = "approval.requested"
        const val CATEGORY_DEVICE_OFFLINE = "device.offline"
        const val CATEGORY_DEVICE_ONLINE = "device.online"
        const val CATEGORY_MEMORY_UPDATED = "memory.updated"
        const val CATEGORY_REMINDER = "reminder.triggered"

        /** 协议上限（schema maxLength），超长截断不拒绝。 */
        const val MAX_TITLE_LEN = 200
        const val MAX_BODY_LEN = 2000
        const val MAX_ACTIONS = 4
        const val MAX_TARGET_ID_LEN = 128

        val KNOWN_CATEGORIES: Set<String> = setOf(
            CATEGORY_CHAT, CATEGORY_TASK_STARTED, CATEGORY_TASK_PROGRESS,
            CATEGORY_TASK_COMPLETED, CATEGORY_TASK_FAILED, CATEGORY_APPROVAL,
            CATEGORY_DEVICE_OFFLINE, CATEGORY_DEVICE_ONLINE,
            CATEGORY_MEMORY_UPDATED, CATEGORY_REMINDER,
        )
    }
}

/** notification.revoke payload（撤回指令自身也带 event_id，需参与去重）。 */
data class NotificationRevoke(
    val eventId: String,
    val notificationId: String,
    val deviceId: String,
    val reason: String?,
)

/**
 * notification.event / notification.revoke 的防御性解析器。
 *
 * 入参可以是完整 v2 信封 JSON（取其中 payload），也可以是裸 payload JSON
 * （便于单元测试与本地触发）。关键字段缺失/类型错误返回 null（调用方记日志后不处理，
 * 不产生任何回执——伪报比沉默更糟，坏消息由传输层按 §2 回 error）。
 */
object NotificationEventParser {
    private const val TAG = "WhaleNotif2Parse"

    /** 从完整信封或裸 payload JSON 中取出 payload 对象。 */
    private fun extractPayload(json: String): JsonObject? {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析失败: ${e.message}")
            return null
        }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val payloadEl = obj.get("payload")
        // 完整信封：payload 是对象；裸 payload：对象本身含 event_id
        return when {
            payloadEl != null && payloadEl.isJsonObject -> payloadEl.asJsonObject
            obj.has("event_id") -> obj
            else -> null
        }
    }

    fun parseEvent(json: String): NotificationEvent? {
        val p = extractPayload(json) ?: return null
        val eventId = p.str("event_id") ?: return null
        val notificationId = p.str("notification_id") ?: return null
        val deviceId = p.str("device_id") ?: return null
        val category = p.str("category") ?: return null
        if (category !in NotificationEvent.KNOWN_CATEGORIES) {
            // 前向兼容：未知 category 不丢弃（core 可能先行上线新分类），
            // 由 ChannelRegistry 归到 whale_system；仅记日志。
            Log.i(TAG, "未知 category=$category，归入系统渠道")
        }
        val title = (p.str("title") ?: return null)
            .take(NotificationEvent.MAX_TITLE_LEN)
        if (title.isEmpty()) return null
        val body = (p.str("body") ?: "").take(NotificationEvent.MAX_BODY_LEN)
        val priority = p.str("priority") ?: "normal"
        val createdAt = p.lng("created_at") ?: return null
        val expiresAt = p.lng("expires_at") ?: return null

        val target = p.get("target")?.takeIf { it.isJsonObject }?.asJsonObject?.let { t ->
            val type = t.str("type") ?: return@let null
            NotificationTarget(
                type = type,
                id = t.str("id")?.take(NotificationEvent.MAX_TARGET_ID_LEN),
            )
        }

        val actions = mutableListOf<NotificationAction>()
        p.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            for (el in arr) {
                if (!el.isJsonObject) continue
                val a = el.asJsonObject
                val id = a.str("action_id") ?: continue
                val label = a.str("label") ?: continue
                if (label.isEmpty()) continue
                actions.add(NotificationAction(id, label.take(32)))
                if (actions.size >= NotificationEvent.MAX_ACTIONS) break // 协议上限 4
            }
        }

        return NotificationEvent(
            eventId = eventId,
            notificationId = notificationId,
            deviceId = deviceId,
            category = category,
            title = title,
            body = body,
            priority = priority,
            createdAt = createdAt,
            expiresAt = expiresAt,
            target = target,
            actions = actions,
            groupKey = p.str("group_key"),
            collapseKey = p.str("collapse_key"),
        )
    }

    fun parseRevoke(json: String): NotificationRevoke? {
        val p = extractPayload(json) ?: return null
        return NotificationRevoke(
            eventId = p.str("event_id") ?: return null,
            notificationId = p.str("notification_id") ?: return null,
            deviceId = p.str("device_id") ?: return null,
            reason = p.str("reason"),
        )
    }

    private fun JsonObject.str(name: String): String? {
        val el = get(name) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
        return el.asString
    }

    private fun JsonObject.lng(name: String): Long? {
        val el = get(name) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
        return try {
            el.asLong
        } catch (e: NumberFormatException) {
            null
        }
    }
}
