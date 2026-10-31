package com.wangfanlab.whale.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 上行消息离线队列（contracts/v1 §6：至少一次送达 + core 按 event_id 去重）。
 *
 * - 持久化于 SQLite（android.database.sqlite，无第三方 ORM）；
 * - 三态：PENDING（未送出）→ SENT（已写入 WS，待确认）→ ACKED（已确认）；
 *   协议 V1 尚无 sensor.event 的应用层 ack，SENT 表示帧已交付 TCP；连接断开重连后
 *   SENT 一律回退 PENDING 重发（重复由 core 按 event_id 幂等吸收）；
 * - 幂等：event_id 主键，重复入队直接忽略（返回 false）；
 * - 容量上限（默认 [DEFAULT_CAPACITY] 条）：溢出时先回收 ACKED，再丢最旧的未送达
 *   消息并累加 dropped 计数（持久化于 queue_meta，重启不丢）；
 * - drop_on_pause：置位的事件在「功能暂停」（heartbeat status=paused）期间不补传，
 *   恢复在线前由 [purgeDropOnPause] 清除并计数（§6：暂停期间不补传私人历史）。
 *
 * 只存 payload 不存信封：id/ts/seq 在实际发送时生成，保证每次重发都是合法新帧，
 * 而幂等语义由 payload.event_id 承担。
 */
class OfflineQueue(
    context: Context,
    dbName: String = DEFAULT_DB_NAME,
    private val capacity: Int = DEFAULT_CAPACITY,
) : SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

    enum class State(val code: Int) {
        PENDING(0), SENT(1), ACKED(2);

        companion object {
            fun of(code: Int): State = entries.first { it.code == code }
        }
    }

    data class Entry(
        val eventId: String,
        val type: String,
        val payloadJson: String,
        val state: State,
        val dropOnPause: Boolean,
        val createdAt: Long,
    )

    data class Stats(
        val pending: Int,
        val sent: Int,
        val acked: Int,
        val dropped: Long,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE uplink_queue (
              event_id      TEXT PRIMARY KEY,
              type          TEXT NOT NULL,
              payload_json  TEXT NOT NULL,
              state         INTEGER NOT NULL DEFAULT 0,
              drop_on_pause INTEGER NOT NULL DEFAULT 0,
              created_at    INTEGER NOT NULL,
              updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_queue_state_created ON uplink_queue(state, created_at)")
        db.execSQL("CREATE TABLE queue_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS uplink_queue")
        db.execSQL("DROP TABLE IF EXISTS queue_meta")
        onCreate(db)
    }

    /**
     * 入队（幂等）。返回 true=新插入，false=event_id 已存在（重复事件被忽略）。
     * 溢出时按「先回收 ACKED、再丢最旧 PENDING/SENT」腾位，丢未送达消息计入 dropped。
     */
    @Synchronized
    fun enqueue(
        eventId: String,
        type: String,
        payloadJson: String,
        dropOnPause: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("event_id", eventId)
                put("type", type)
                put("payload_json", payloadJson)
                put("state", State.PENDING.code)
                put("drop_on_pause", if (dropOnPause) 1 else 0)
                put("created_at", nowMs)
                put("updated_at", nowMs)
            }
            val rowId = db.insertWithOnConflict(
                "uplink_queue", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (rowId < 0) return false // 幂等去重
            enforceCapacity(db)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** 取一批待发送消息（PENDING，按入队时间升序）。 */
    @Synchronized
    fun pendingBatch(limit: Int = 100): List<Entry> {
        val out = ArrayList<Entry>(limit)
        readableDatabase.query(
            "uplink_queue",
            arrayOf("event_id", "type", "payload_json", "state", "drop_on_pause", "created_at"),
            "state = ?", arrayOf(State.PENDING.code.toString()),
            null, null, "created_at ASC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        eventId = c.getString(0),
                        type = c.getString(1),
                        payloadJson = c.getString(2),
                        state = State.of(c.getInt(3)),
                        dropOnPause = c.getInt(4) != 0,
                        createdAt = c.getLong(5),
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun markSent(eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        transition(eventId, State.PENDING, State.SENT, nowMs)

    @Synchronized
    fun markAcked(eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        transition(eventId, State.SENT, State.ACKED, nowMs)

    /** 重连后调用：所有「已发送待确认」回退为 PENDING（至少一次送达；core 按 event_id 去重）。 */
    @Synchronized
    fun resetSentToPending(nowMs: Long = System.currentTimeMillis()): Int {
        val values = ContentValues().apply {
            put("state", State.PENDING.code)
            put("updated_at", nowMs)
        }
        return writableDatabase.update(
            "uplink_queue", values, "state = ?", arrayOf(State.SENT.code.toString()),
        )
    }

    /** 进入 paused 状态时调用：丢弃所有标记 drop_on_pause 的未送达消息，返回并累计丢弃数。 */
    @Synchronized
    fun purgeDropOnPause(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.delete(
                "uplink_queue",
                "drop_on_pause = 1 AND state != ?",
                arrayOf(State.ACKED.code.toString()),
            )
            if (count > 0) addDropped(db, count.toLong())
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun stats(): Stats {
        fun count(state: State): Int = readableDatabase.compileStatement(
            "SELECT COUNT(*) FROM uplink_queue WHERE state = ${state.code}",
        ).use { it.simpleQueryForLong().toInt() }
        return Stats(
            pending = count(State.PENDING),
            sent = count(State.SENT),
            acked = count(State.ACKED),
            dropped = droppedCount(),
        )
    }

    /** 撤销设备时调用（§6：撤销后队列清空）。 */
    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("uplink_queue", null, null)
            db.delete("queue_meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- 内部 ----

    private fun transition(eventId: String, from: State, to: State, nowMs: Long): Boolean {
        val values = ContentValues().apply {
            put("state", to.code)
            put("updated_at", nowMs)
        }
        return writableDatabase.update(
            "uplink_queue", values,
            "event_id = ? AND state = ?",
            arrayOf(eventId, from.code.toString()),
        ) > 0
    }

    /** 容量治理：先删最旧 ACKED（静默回收），仍超量再丢最旧 PENDING/SENT（计入 dropped）。 */
    private fun enforceCapacity(db: SQLiteDatabase) {
        var total = db.compileStatement("SELECT COUNT(*) FROM uplink_queue").use {
            it.simpleQueryForLong()
        }
        if (total <= capacity) return

        val ackedExcess = (total - capacity).toInt()
        val ackedPurged = deleteOldest(db, State.ACKED, ackedExcess)
        total -= ackedPurged
        if (total <= capacity) return

        val toDrop = (total - capacity).toInt()
        val droppedPending = deleteOldest(db, State.PENDING, toDrop)
        val droppedSent = if (droppedPending < toDrop) {
            deleteOldest(db, State.SENT, toDrop - droppedPending)
        } else 0
        val dropped = droppedPending + droppedSent
        if (dropped > 0) addDropped(db, dropped.toLong())
    }

    private fun deleteOldest(db: SQLiteDatabase, state: State, limit: Int): Int {
        if (limit <= 0) return 0
        return db.delete(
            "uplink_queue",
            "event_id IN (SELECT event_id FROM uplink_queue WHERE state = ?" +
                " ORDER BY created_at ASC LIMIT ?)",
            arrayOf(state.code.toString(), limit.toString()),
        )
    }

    private fun addDropped(db: SQLiteDatabase, delta: Long) {
        db.execSQL(
            "INSERT INTO queue_meta(key, value) VALUES('$META_DROPPED', ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + ?",
            arrayOf(delta.toString(), delta.toString()),
        )
    }

    private fun droppedCount(): Long = readableDatabase.query(
        "queue_meta", arrayOf("value"), "key = ?", arrayOf(META_DROPPED),
        null, null, null,
    ).use { c -> if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0L else 0L }

    companion object {
        private const val DB_VERSION = 1
        private const val META_DROPPED = "dropped_count"
        const val DEFAULT_DB_NAME = "whale_transport.db"
        const val DEFAULT_CAPACITY = 1000
    }
}
