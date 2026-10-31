package com.wangfanlab.whale.transport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SEC-01 队列加密迁移测试（Robolectric 提供真实 SQLite 与 Context）。
 *
 * 覆盖：v1 明文 → v2 密文迁移（含幂等/失败删除）、加密往返、
 * 损坏密文 fail-closed 丢弃、无明文可检索、容量语义不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineQueueEncryptionTest {

    private lateinit var context: Context
    private val dbName = "test_queue_enc.db"

    /** 可逆假加密（测试用）：前缀反转。 */
    private val fakeCipher = object : QueueCipher {
        override fun encrypt(plain: String): String =
            android.util.Base64.encodeToString(plain.reversed().toByteArray(), android.util.Base64.NO_WRAP)

        override fun decrypt(blob: String): String {
            val bytes = try {
                android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                throw SecurityException("bad blob", e)
            }
            val s = String(bytes)
            require(s.isNotEmpty()) { "empty" }
            return s.reversed()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 手工建 v1 明文库（模拟升级前状态）。 */
    private fun createV1PlaintextDb(rows: List<Triple<String, String, Int>>) {
        val path = context.getDatabasePath(dbName).absolutePath
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.version = 1
            db.execSQL(
                """CREATE TABLE uplink_queue (
                     event_id TEXT PRIMARY KEY, type TEXT NOT NULL, payload_json TEXT NOT NULL,
                     state INTEGER NOT NULL DEFAULT 0, drop_on_pause INTEGER NOT NULL DEFAULT 0,
                     created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE TABLE queue_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            for ((id, payload, state) in rows) {
                db.execSQL(
                    "INSERT INTO uplink_queue VALUES(?,?,?,?,0,1000,1000)",
                    arrayOf<Any>(id, "sensor.event", payload, state),
                )
            }
        }
    }

    private fun rawPayloads(): List<String> {
        val path = context.getDatabasePath(dbName).absolutePath
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val out = mutableListOf<String>()
            db.rawQuery("SELECT payload_json FROM uplink_queue", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
            return out
        }
    }

    @Test
    fun `v1 明文库迁移后全部加密且可正常读出`() {
        createV1PlaintextDb(
            listOf(
                Triple("ev-aaaaaaaaaaaaaaa1", "{\"event_id\":\"ev-aaaaaaaaaaaaaaa1\",\"summary\":\"私密OCR内容\"}", 0),
                Triple("ev-aaaaaaaaaaaaaaa2", "{\"event_id\":\"ev-aaaaaaaaaaaaaaa2\",\"summary\":\"通知标题\"}", 1),
            ),
        )
        val queue = OfflineQueue(context, dbName = dbName, cipher = fakeCipher)
        val batch = queue.pendingBatch(10)
        // PENDING 的 1 条可读出且内容还原
        assertEquals(1, batch.size)
        assertTrue(batch[0].payloadJson.contains("私密OCR内容"))
        // 落库内容全部带密文前缀，无明文可检索
        val raw = rawPayloads()
        assertEquals(2, raw.size)
        assertTrue(raw.all { it.startsWith(OfflineQueue.CIPHER_PREFIX) })
        assertFalse(raw.any { it.contains("私密OCR内容") || it.contains("通知标题") })
    }

    @Test
    fun `迁移幂等——重复升级不产生二次加密或丢行`() {
        createV1PlaintextDb(
            listOf(Triple("ev-bbbbbbbbbbbbbbb1", "{\"event_id\":\"ev-bbbbbbbbbbbbbbb1\"}", 0)),
        )
        val q1 = OfflineQueue(context, dbName = dbName, cipher = fakeCipher)
        assertEquals(1, q1.pendingBatch(10).size)
        q1.close()
        // 二次打开（版本已是 2，不再迁移）；再手工触发一次迁移也应跳过已加密行
        val q2 = OfflineQueue(context, dbName = dbName, cipher = fakeCipher)
        val batch = q2.pendingBatch(10)
        assertEquals(1, batch.size)
        assertTrue(batch[0].payloadJson.contains("ev-bbbbbbbbbbbbbbb1"))
        q2.close()
    }

    @Test
    fun `新入队事件落库即密文`() {
        val queue = OfflineQueue(context, dbName = dbName, cipher = fakeCipher)
        queue.enqueue("ev-ccccccccccccccc1", "sensor.event", "{\"summary\":\"屏幕文字\"}")
        val raw = rawPayloads()
        assertEquals(1, raw.size)
        assertTrue(raw[0].startsWith(OfflineQueue.CIPHER_PREFIX))
        assertFalse(raw[0].contains("屏幕文字"))
        assertEquals("{\"summary\":\"屏幕文字\"}", queue.pendingBatch(1)[0].payloadJson)
    }

    @Test
    fun `损坏密文 fail-closed 丢弃且计入 dropped`() {
        val queue = OfflineQueue(context, dbName = dbName, cipher = fakeCipher)
        queue.enqueue("ev-ddddddddddddddd1", "sensor.event", "{\"ok\":1}")
        // 手工写入损坏密文（带前缀但 base64 非法）
        val path = context.getDatabasePath(dbName).absolutePath
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "INSERT INTO uplink_queue VALUES(?,?,?,?,0,1001,1001)",
                arrayOf<Any>("ev-ddddddddddddddd2", "sensor.event", OfflineQueue.CIPHER_PREFIX + "!!!not-base64!!!", 0),
            )
        }
        val batch = queue.pendingBatch(10)
        assertEquals(1, batch.size)
        assertEquals("ev-ddddddddddddddd1", batch[0].eventId)
        // 损坏行被删除并计入 dropped
        assertEquals(1, queue.stats().dropped)
        assertEquals(1, rawPayloads().size)
    }

    @Test
    fun `迁移时 cipher 缺失删除明文行（不留明文）`() {
        createV1PlaintextDb(
            listOf(Triple("ev-eeeeeeeeeeeeeee1", "{\"summary\":\"隐私\"}", 0)),
        )
        // 无 cipher 打开：迁移删除明文行（fail closed），队列清空
        val queue = OfflineQueue(context, dbName = dbName, cipher = null)
        assertEquals(0, queue.pendingBatch(10).size)
        assertTrue(rawPayloads().isEmpty())
        assertEquals(1, queue.stats().dropped)
    }

    @Test
    fun `加密后容量与幂等语义不变`() {
        val queue = OfflineQueue(context, dbName = dbName, capacity = 3, cipher = fakeCipher)
        for (i in 1..5) {
            queue.enqueue("ev-fffffffffffffff$i", "sensor.event", "{\"i\":$i}")
        }
        // 容量 3：最旧 2 条 PENDING 被丢
        assertEquals(2, queue.stats().dropped)
        assertEquals(3, queue.stats().pending)
        // 幂等：重复 event_id 不入队
        assertFalse(queue.enqueue("ev-fffffffffffffff5", "sensor.event", "{\"i\":5}"))
    }
}
