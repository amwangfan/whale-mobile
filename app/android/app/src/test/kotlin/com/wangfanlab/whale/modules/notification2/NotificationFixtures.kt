package com.wangfanlab.whale.modules.notification2

import android.app.Notification
import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * notification2 测试公共件：事件 JSON 构造、假上行、假门面。
 */

private val seq = AtomicLong(0)

/** 生成合法形态的 id（满足 ^[0-9A-HJKMNP-TV-Z]{26}$ 风格即可，测试不跑 schema 校验）。 */
fun fakeUlid(): String = Ulid.newUlid()

fun fakeEventId(): String = "evt_" + fakeUlid()

fun fakeNotificationId(): String = "notif_" + fakeUlid()

const val TEST_DEVICE_ID = "ba4bb14c75917cfc0e69fb7a1caf8ba3"

/** 构造裸 payload JSON（解析器同时接受完整信封与裸 payload）。 */
fun eventJson(
    eventId: String = fakeEventId(),
    notificationId: String = fakeNotificationId(),
    deviceId: String = TEST_DEVICE_ID,
    category: String = NotificationEvent.CATEGORY_TASK_COMPLETED,
    title: String = "任务已完成",
    body: String = "Root 手机已完成应用检查。",
    priority: String = "normal",
    createdAt: Long = 1_794_052_895_000,
    expiresAt: Long = 1_794_139_200_000,
    target: String? = """{"type":"task","id":"task_001"}""",
    actions: String? = null,
    groupKey: String? = "task_001",
    collapseKey: String? = "task_001",
): String {
    val sb = StringBuilder("{")
    sb.append("\"event_id\":\"$eventId\",")
    sb.append("\"notification_id\":\"$notificationId\",")
    sb.append("\"device_id\":\"$deviceId\",")
    sb.append("\"category\":\"$category\",")
    sb.append("\"title\":").append(com.google.gson.Gson().toJson(title)).append(",")
    sb.append("\"body\":").append(com.google.gson.Gson().toJson(body)).append(",")
    sb.append("\"priority\":\"$priority\",")
    sb.append("\"created_at\":$createdAt,")
    sb.append("\"expires_at\":$expiresAt")
    if (target != null) sb.append(",\"target\":$target")
    if (actions != null) sb.append(",\"actions\":$actions")
    if (groupKey != null) sb.append(",\"group_key\":\"$groupKey\"")
    if (collapseKey != null) sb.append(",\"collapse_key\":\"$collapseKey\"")
    sb.append("}")
    return sb.toString()
}

fun revokeJson(
    eventId: String = fakeEventId(),
    notificationId: String,
    deviceId: String = TEST_DEVICE_ID,
    reason: String? = "任务结果已更新",
): String {
    val sb = StringBuilder("{")
    sb.append("\"event_id\":\"$eventId\",")
    sb.append("\"notification_id\":\"$notificationId\",")
    sb.append("\"device_id\":\"$deviceId\"")
    if (reason != null) sb.append(",\"reason\":").append(com.google.gson.Gson().toJson(reason))
    sb.append("}")
    return sb.toString()
}

/** 假上行：记录全部发送；failAlways/failNext 模拟断网。 */
class FakeUplink : NotificationUplinkSender {
    val sent = mutableListOf<String>()
    var failAlways = false

    override suspend fun send(payload: String) {
        if (failAlways) throw RuntimeException("net down")
        sent.add(payload)
    }

    fun payloads(): List<com.google.gson.JsonObject> =
        sent.map { com.google.gson.JsonParser.parseString(it).asJsonObject }

    fun payloadPayloads(): List<com.google.gson.JsonObject> =
        payloads().map { it.getAsJsonObject("payload") }

    fun statuses(): List<String> =
        payloadPayloads().mapNotNull { it.get("status")?.asString }
}

/** 假门面：不触系统，只记录 post/cancel。 */
class FakeFacade(context: Context) : NotificationManagerFacade(context) {
    data class Post(val id: Int, val tag: String?, val notification: Notification)

    val posts = mutableListOf<Post>()
    val cancels = mutableListOf<Int>()
    var postResult = true

    override fun post(id: Int, tag: String?, notification: Notification): Boolean {
        posts.add(Post(id, tag, notification))
        return postResult
    }

    override fun cancel(id: Int, tag: String?) {
        cancels.add(id)
    }
}

/** 每个测试一个独立库名，避免 Robolectric 间串扰。 */
fun freshDbName(): String = "test_notif2_${seq.incrementAndGet()}.db"
