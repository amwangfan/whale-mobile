package com.wangfanlab.whale.modules.notification2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns

/**
 * 通知收件箱（SQLite：whale_notifications.db）。
 *
 * 三张表：
 * - notifications：notification.event 落库。event_id 主键去重（INSERT OR IGNORE），
 *   状态机 received → displayed → opened/dismissed/action_submitted，另有
 *   expired/failed 与本地扩展 revoked（协议 receipt 无 revoked 值，撤回回执映射为 dismissed）。
 * - processed_events：event_id 去重持久层（跨进程/重启有效），供
 *   [NotificationDeduplicator] 使用；容量有界（FIFO）。
 * - action_outbox：通知按钮操作回传的本地 Outbox（先落库再上行，
 *   发送失败保留待重试，进程被杀不丢）。
 *
 * 容量纪律：每设备通知历史上限 [MAX_RECORDS]=500 条，超出按 received_at 先进先出淘汰。
 */
class NotificationInbox(
    context: Context,
    dbName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

    /** 通知状态（wire 值即协议 receipt status；REVOKED 为本地扩展）。 */
    enum class Status(val wire: String) {
        RECEIVED("received"),
        DISPLAYED("displayed"),
        OPENED("opened"),
        DISMISSED("dismissed"),
        ACTION_SUBMITTED("action_submitted"),
        EXPIRED("expired"),
        FAILED("failed"),
        /** 本地扩展：core 撤回。协议 receipt 无此值，上行时映射为 dismissed。 */
        REVOKED("revoked"),
        ;

        companion object {
            fun ofWire(wire: String): Status? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Record(
        val eventId: String,
        val notificationId: String,
        val category: String,
        val title: String,
        val body: String,
        val status: Status,
        val createdAt: Long,
        val expiresAt: Long,
        val targetJson: String?,
        val groupKey: String?,
        val collapseKey: String?,
        val receivedAt: Long,
        val updatedAt: Long,
        val read: Boolean,
    )

    data class OutboxEntry(
        val rowId: Long,
        val eventId: String,
        val notificationId: String,
        val actionId: String,
        /** 完整 v2 notification.action 信封 JSON。 */
        val payloadJson: String,
        val createdAt: Long,
        val attempts: Int,
    )

    // ------------------------------------------------------------  schema

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_NOTIFICATIONS (
                 ${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                 event_id TEXT NOT NULL UNIQUE,
                 notification_id TEXT NOT NULL,
                 category TEXT NOT NULL,
                 title TEXT NOT NULL,
                 body TEXT NOT NULL,
                 status TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 expires_at INTEGER NOT NULL,
                 target_json TEXT,
                 group_key TEXT,
                 collapse_key TEXT,
                 received_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL,
                 read INTEGER NOT NULL DEFAULT 0
               )""",
        )
        db.execSQL("CREATE INDEX idx_notif_notification_id ON $TABLE_NOTIFICATIONS(notification_id)")
        db.execSQL("CREATE INDEX idx_notif_received_at ON $TABLE_NOTIFICATIONS(received_at)")
        db.execSQL(
            """CREATE TABLE $TABLE_PROCESSED (
                 event_id TEXT PRIMARY KEY,
                 processed_at INTEGER NOT NULL
               )""",
        )
        db.execSQL(
            """CREATE TABLE $TABLE_OUTBOX (
                 ${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                 event_id TEXT NOT NULL,
                 notification_id TEXT NOT NULL,
                 action_id TEXT NOT NULL,
                 payload_json TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 attempts INTEGER NOT NULL DEFAULT 0
               )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 即当前版本；后续升级在此迁移。
    }

    // ------------------------------------------------------------  通知记录

    /**
     * 落库新事件（event_id 主键去重：重复插入返回 false，不覆盖既有状态）。
     * 插入后执行 FIFO 容量淘汰。
     */
    fun insert(event: NotificationEvent, status: Status, nowMs: Long): Boolean {
        val values = ContentValues().apply {
            put("event_id", event.eventId)
            put("notification_id", event.notificationId)
            put("category", event.category)
            put("title", event.title)
            put("body", event.body)
            put("status", status.wire)
            put("created_at", event.createdAt)
            put("expires_at", event.expiresAt)
            put("target_json", event.target?.let { targetToJson(it.type, it.id) })
            put("group_key", event.groupKey)
            put("collapse_key", event.collapseKey)
            put("received_at", nowMs)
            put("updated_at", nowMs)
            put("read", 0)
        }
        val row = writableDatabase.insertWithOnConflict(
            TABLE_NOTIFICATIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (row == -1L) return false // event_id 已存在
        enforceCapacity()
        return true
    }

    /** 按 event_id 更新状态（状态机迁移由调用方保证合法）。 */
    fun updateStatusByEvent(eventId: String, status: Status, nowMs: Long): Int =
        updateStatus("event_id=?", arrayOf(eventId), status, nowMs)

    /** 按 notification_id 更新状态（revoke 时同一通知可能有多条事件记录）。 */
    fun updateStatusByNotification(notificationId: String, status: Status, nowMs: Long): Int =
        updateStatus("notification_id=?", arrayOf(notificationId), status, nowMs)

    private fun updateStatus(where: String, args: Array<String>, status: Status, nowMs: Long): Int {
        val values = ContentValues().apply {
            put("status", status.wire)
            put("updated_at", nowMs)
        }
        return writableDatabase.update(TABLE_NOTIFICATIONS, values, where, args)
    }

    fun findByEventId(eventId: String): Record? =
        query("event_id=?", arrayOf(eventId), limit = 1).firstOrNull()

    fun findByNotificationId(notificationId: String): List<Record> =
        query("notification_id=?", arrayOf(notificationId))

    /** 历史查询（通知历史页面用）：按收到时间倒序分页。 */
    fun history(limit: Int = 50, offset: Int = 0): List<Record> =
        query(null, null, orderDesc = true, limit = limit, offset = offset)

    /** 标记已读（通知历史/点击后）。返回受影响行数。 */
    fun markRead(notificationId: String): Int {
        val values = ContentValues().apply { put("read", 1) }
        return writableDatabase.update(
            TABLE_NOTIFICATIONS, values, "notification_id=?", arrayOf(notificationId),
        )
    }

    fun markAllRead(): Int {
        val values = ContentValues().apply { put("read", 1) }
        return writableDatabase.update(TABLE_NOTIFICATIONS, values, "read=0", null)
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NOTIFICATIONS", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    /** FIFO 容量淘汰：超上限时淘汰最早收到的。 */
    fun enforceCapacity(maxRecords: Int = MAX_RECORDS) {
        writableDatabase.execSQL(
            """DELETE FROM $TABLE_NOTIFICATIONS WHERE ${BaseColumns._ID} NOT IN (
                 SELECT ${BaseColumns._ID} FROM $TABLE_NOTIFICATIONS
                 ORDER BY received_at DESC, ${BaseColumns._ID} DESC LIMIT ?
               )""",
            arrayOf(maxRecords),
        )
    }

    private fun query(
        where: String?,
        args: Array<String>?,
        orderDesc: Boolean = false,
        limit: Int = 500,
        offset: Int = 0,
    ): List<Record> {
        val order = if (orderDesc) "received_at DESC" else "received_at ASC"
        val sql = buildString {
            append("SELECT * FROM $TABLE_NOTIFICATIONS")
            if (where != null) append(" WHERE ").append(where)
            append(" ORDER BY ").append(order)
            append(" LIMIT ").append(limit).append(" OFFSET ").append(offset)
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Record>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Record(
                        eventId = c.getString(c.getColumnIndexOrThrow("event_id")),
                        notificationId = c.getString(c.getColumnIndexOrThrow("notification_id")),
                        category = c.getString(c.getColumnIndexOrThrow("category")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        body = c.getString(c.getColumnIndexOrThrow("body")),
                        status = Status.ofWire(c.getString(c.getColumnIndexOrThrow("status")))
                            ?: Status.FAILED,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
                        targetJson = c.getString(c.getColumnIndexOrThrow("target_json")),
                        groupKey = c.getString(c.getColumnIndexOrThrow("group_key")),
                        collapseKey = c.getString(c.getColumnIndexOrThrow("collapse_key")),
                        receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                        read = c.getInt(c.getColumnIndexOrThrow("read")) == 1,
                    )
                )
            }
            return out
        }
    }

    // ------------------------------------------------------------  事件去重

    /** event_id 是否已处理（跨进程/重启持久）。 */
    fun isProcessed(eventId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_PROCESSED WHERE event_id=? LIMIT 1", arrayOf(eventId),
        ).use { it.moveToFirst() }

    fun markProcessed(eventId: String, nowMs: Long) {
        val values = ContentValues().apply {
            put("event_id", eventId)
            put("processed_at", nowMs)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_PROCESSED, null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        // 去重表同样有界，避免无限膨胀
        writableDatabase.execSQL(
            """DELETE FROM $TABLE_PROCESSED WHERE event_id NOT IN (
                 SELECT event_id FROM $TABLE_PROCESSED
                 ORDER BY processed_at DESC LIMIT $MAX_PROCESSED
               )""",
        )
    }

    /** 本机已处理的最大 event_id（ULID 时间有序，notification.sync 的 last_event_id）。 */
    fun lastProcessedEventId(): String? =
        readableDatabase.rawQuery("SELECT MAX(event_id) FROM $TABLE_PROCESSED", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ------------------------------------------------------------  操作 Outbox

    fun enqueueAction(
        eventId: String,
        notificationId: String,
        actionId: String,
        payloadJson: String,
        nowMs: Long,
    ): Long {
        val values = ContentValues().apply {
            put("event_id", eventId)
            put("notification_id", notificationId)
            put("action_id", actionId)
            put("payload_json", payloadJson)
            put("created_at", nowMs)
            put("attempts", 0)
        }
        return writableDatabase.insert(TABLE_OUTBOX, null, values)
    }

    fun pendingActions(): List<OutboxEntry> {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_OUTBOX ORDER BY ${BaseColumns._ID} ASC LIMIT 200", null,
        ).use { c ->
            val out = ArrayList<OutboxEntry>(c.count)
            while (c.moveToNext()) {
                out.add(
                    OutboxEntry(
                        rowId = c.getLong(c.getColumnIndexOrThrow(BaseColumns._ID)),
                        eventId = c.getString(c.getColumnIndexOrThrow("event_id")),
                        notificationId = c.getString(c.getColumnIndexOrThrow("notification_id")),
                        actionId = c.getString(c.getColumnIndexOrThrow("action_id")),
                        payloadJson = c.getString(c.getColumnIndexOrThrow("payload_json")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                    )
                )
            }
            return out
        }
    }

    fun deleteAction(rowId: Long) {
        writableDatabase.delete(
            TABLE_OUTBOX, "${BaseColumns._ID}=?", arrayOf(rowId.toString()),
        )
    }

    fun incrementActionAttempts(rowId: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_OUTBOX SET attempts=attempts+1 WHERE ${BaseColumns._ID}=?",
            arrayOf(rowId),
        )
    }

    companion object {
        const val DB_NAME = "whale_notifications.db"
        const val DB_VERSION = 1

        /** 每设备通知历史上限（修正方案/任务要求：容量有界，FIFO）。 */
        const val MAX_RECORDS = 500

        /** event_id 去重表上限（远大于历史上限，覆盖补传窗口）。 */
        const val MAX_PROCESSED = 10000

        const val TABLE_NOTIFICATIONS = "notifications"
        const val TABLE_PROCESSED = "processed_events"
        const val TABLE_OUTBOX = "action_outbox"

        private fun targetToJson(type: String, id: String?): String {
            val obj = com.google.gson.JsonObject()
            obj.addProperty("type", type)
            if (id != null) obj.addProperty("id", id)
            return obj.toString()
        }
    }
}
