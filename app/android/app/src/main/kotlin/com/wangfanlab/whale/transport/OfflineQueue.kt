package com.wangfanlab.whale.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 上行消息离线队列（contracts/v1 §6：至少一次送达 + core 按 event_id 去重）。
 *
 * - 持久化于 SQLite（android.database.sqlite，无第三方 ORM）；
 * - **payload 应用层加密（SEC-01）**：`payload_json` 列自 DB v2 起存放
 *   `v1.` 前缀的 AES/GCM 密文（[QueueCipher]，生产为 Keystore 主密钥）；
 *   metadata 只保留队列状态（event_id/type/state/时间戳），不含内容；
 * - 三态：PENDING（未送出）→ SENT（已写入 WS，待确认）→ ACKED（已确认）；
 *   协议 V1 尚无 sensor.event 的应用层 ack，SENT 表示帧已交付 TCP；连接断开重连后
 *   SENT 一律回退 PENDING 重发（重复由 core 按 event_id 幂等吸收）；
 * - 幂等：event_id 主键，重复入队直接忽略（返回 false）；
 * - 容量上限（默认 [DEFAULT_CAPACITY] 条）：溢出时先回收 ACKED，再丢最旧的未送达
 *   消息并累加 dropped 计数（持久化于 queue_meta，重启不丢）；
 * - drop_on_pause：置位的事件在「功能暂停」（heartbeat status=paused）期间不补传，
 *   恢复在线前由 [purgeDropOnPause] 清除并计数（§6：暂停期间不补传私人历史）。
 * - fail-closed：解密失败（密文损坏/主密钥失效）的条目直接丢弃并计入 dropped，
 *   绝不上传明文或半解密内容；v1→v2 迁移在单事务内「读旧→加密→写回」，
 *   迁移中无法加密的条目宁可删除也不留明文。
 *
 * 只存 payload 不存信封：id/ts/seq 在实际发送时生成，保证每次重发都是合法新帧，
 * 而幂等语义由 payload.event_id 承担。
 */
class OfflineQueue(
    context: Context,
    dbName: String = DEFAULT_DB_NAME,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val cipher: QueueCipher? = null,
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
        if (oldVersion < 2) {
            // SEC-01 v1→v2：明文 payload 事务式加密迁移。
            // 幂等：已带 v1. 前缀的行跳过；无法加密的行删除（fail closed，不留明文）。
            migratePlaintextToEncrypted(db)
            return
        }
        db.execSQL("DROP TABLE IF EXISTS uplink_queue")
        db.execSQL("DROP TABLE IF EXISTS queue_meta")
        onCreate(db)
    }

    /** v1→v2 迁移：读旧明文 → 加密 → 写回，单事务；无 cipher 或加密失败的行删除。 */
    private fun migratePlaintextToEncrypted(db: SQLiteDatabase) {
        val rows = mutableListOf<Pair<String, String>>()
        db.query(
            "uplink_queue", arrayOf("event_id", "payload_json"),
            null, null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
        }
        var migrated = 0
        var dropped = 0
        db.beginTransaction()
        try {
            for ((eventId, payload) in rows) {
                if (payload.startsWith(CIPHER_PREFIX)) continue // 幂等：已加密
                val enc = try {
                    cipher?.encrypt(payload)
                } catch (t: Throwable) {
                    Log.w(TAG, "迁移加密失败 event_id=$eventId，删除该条（fail closed）", t)
                    null
                }
                if (enc == null) {
                    db.delete("uplink_queue", "event_id = ?", arrayOf(eventId))
                    dropped++
                } else {
                    db.update(
                        "uplink_queue",
                        ContentValues().apply {
                            put("payload_json", CIPHER_PREFIX + enc)
                            put("updated_at", System.currentTimeMillis())
                        },
                        "event_id = ?", arrayOf(eventId),
                    )
                    migrated++
                }
            }
            if (dropped > 0) addDropped(db, dropped.toLong())
            db.setTransactionSuccessful()
            Log.i(TAG, "队列迁移 v1→v2：加密 $migrated 条，删除 $dropped 条")
        } finally {
            db.endTransaction()
        }
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
                put("payload_json", encryptPayload(payloadJson))
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
        val corrupted = ArrayList<String>()
        readableDatabase.query(
            "uplink_queue",
            arrayOf("event_id", "type", "payload_json", "state", "drop_on_pause", "created_at"),
            "state = ?", arrayOf(State.PENDING.code.toString()),
            null, null, "created_at ASC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                val eventId = c.getString(0)
                val plain = try {
                    decryptPayload(c.getString(2))
                } catch (t: Throwable) {
                    // fail closed：密文损坏/密钥失效 → 收集后统一丢弃，绝不送明文
                    Log.w(TAG, "队列条目解密失败，丢弃 event_id=$eventId", t)
                    corrupted.add(eventId)
                    continue
                }
                out.add(
                    Entry(
                        eventId = eventId,
                        type = c.getString(1),
                        payloadJson = plain,
                        state = State.of(c.getInt(3)),
                        dropOnPause = c.getInt(4) != 0,
                        createdAt = c.getLong(5),
                    )
                )
            }
        }
        if (corrupted.isNotEmpty()) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (id in corrupted) {
                    db.delete("uplink_queue", "event_id = ?", arrayOf(id))
                }
                addDropped(db, corrupted.size.toLong())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
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

    // ---- payload 加解密（SEC-01） ----

    /** 入库前加密；无 cipher（测试/降级）时原样存储。 */
    private fun encryptPayload(plain: String): String {
        val c = cipher ?: return plain
        return CIPHER_PREFIX + c.encrypt(plain)
    }

    /** 出库后解密；无 cipher 时要求明文（带前缀视为损坏）。 */
    private fun decryptPayload(stored: String): String {
        if (stored.startsWith(CIPHER_PREFIX)) {
            val c = cipher ?: throw SecurityException("密文队列但无 cipher")
            return c.decrypt(stored.removePrefix(CIPHER_PREFIX))
        }
        // 兼容窗口：迁移前入队的明文行（v1 遗留）只读不加密写回；
        // 正常路径（cipher 非空且迁移已跑）不会命中。
        if (cipher != null) throw SecurityException("明文 payload 出现在加密队列")
        return stored
    }

    companion object {
        private const val TAG = "WhaleOfflineQueue"

        /** DB v2：payload_json 列存 `v1.` 前缀密文（SEC-01）。 */
        private const val DB_VERSION = 2
        private const val META_DROPPED = "dropped_count"

        /** 密文格式版本前缀（格式演进时递增并写迁移）。 */
        const val CIPHER_PREFIX = "v1."
        const val DEFAULT_DB_NAME = "whale_transport.db"
        const val DEFAULT_CAPACITY = 1000
    }
}
