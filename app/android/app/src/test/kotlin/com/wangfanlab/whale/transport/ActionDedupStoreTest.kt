package com.wangfanlab.whale.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SEC-03 动作幂等持久化测试（Robolectric 真实 SQLite）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActionDedupStoreTest {

    private lateinit var context: Context
    private val dbName = "test_action_dedup.db"
    private lateinit var queue: OfflineQueue

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        queue = OfflineQueue(context, dbName = dbName)
    }

    @After
    fun tearDown() {
        queue.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `记录幂等——同键重复插入返回 false`() {
        assertTrue(queue.recordActionIfAbsent("k1", "a1", "phone.notification.post"))
        assertFalse(queue.recordActionIfAbsent("k1", "a2", "phone.notification.post"))
        // 原始记录不被覆盖
        assertEquals("a1", queue.getActionRecord("k1")?.actionId)
    }

    @Test
    fun `终态写入与读取`() {
        queue.recordActionIfAbsent("k2", "a9", "phone.device.safe_action")
        assertNull(queue.getActionRecord("k2")?.terminalState)
        assertTrue(queue.setActionTerminal("k2", "executed", null))
        val rec = queue.getActionRecord("k2")
        assertEquals("executed", rec?.terminalState)
        assertNull(rec?.errorCode)
    }

    @Test
    fun `按 action_id 补记终态（Dart 回执路径）`() {
        queue.recordActionIfAbsent("k3", "act-77", "phone.notification.post")
        assertTrue(queue.setActionTerminalByActionId("act-77", "failed", "unavailable"))
        val rec = queue.getActionRecord("k3")
        assertEquals("failed", rec?.terminalState)
        assertEquals("unavailable", rec?.errorCode)
    }

    @Test
    fun `未知 action_id 补记返回 false`() {
        assertFalse(queue.setActionTerminalByActionId("nonexistent", "executed", null))
    }

    @Test
    fun `完整终态经关闭重开仍保留嵌套 result 及原始时间`() {
        val result = mapOf<String, Any?>(
            "exit_code" to 0L,
            "observation" to mapOf("nodes" to listOf("button", "text"), "ok" to true),
            "stdout" to "shell output",
        )
        queue.recordActionIfAbsent("complete", "act-complete", "device.agent.shell")
        assertTrue(queue.setActionTerminal("complete", "executed", null,
            executedAt = 1730000000001L, providerRef = "provider-1", result = result))
        queue.close()
        queue = OfflineQueue(context, dbName = dbName)
        val rec = queue.getActionRecord("complete")!!
        assertEquals("executed", rec.terminalState)
        assertEquals(1730000000001L, rec.executedAt)
        assertEquals("provider-1", rec.providerRef)
        assertEquals(result, rec.result)
    }

    @Test
    fun `Dart 失败终态完整字段与空 result 可覆盖读取`() {
        queue.recordActionIfAbsent("dart", "act-dart", "device.agent.control")
        assertTrue(queue.setActionTerminalByActionId("act-dart", "failed", "internal",
            executedAt = 77L, providerRef = "provider-dart", result = emptyMap()))
        val rec = queue.getActionRecord("dart")!!
        assertEquals("failed", rec.terminalState)
        assertEquals("internal", rec.errorCode)
        assertEquals(77L, rec.executedAt)
        assertEquals("provider-dart", rec.providerRef)
        assertEquals(emptyMap<String, Any?>(), rec.result)
        queue.setActionTerminalByActionId("act-dart", "unknown", null)
        val cleared = queue.getActionRecord("dart")!!
        assertNull(cleared.executedAt)
        assertNull(cleared.providerRef)
        assertNull(cleared.result)
    }

    @Test
    fun `v3 旧持久化终态和未终结记录兼容升级`() {
        queue.close()
        context.deleteDatabase(dbName)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).absolutePath, null,
        ).use { db ->
            db.execSQL("""CREATE TABLE action_dedup (
                idem_key TEXT PRIMARY KEY, action_id TEXT NOT NULL, capability TEXT NOT NULL,
                terminal_state TEXT, error_code TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL)""")
            db.execSQL("INSERT INTO action_dedup VALUES('old','old-action','c','failed','expired',1,1)")
            db.execSQL("INSERT INTO action_dedup VALUES('pending','pending-action','c',NULL,NULL,2,2)")
            db.version = 3
        }
        queue = OfflineQueue(context, dbName = dbName)
        val old = queue.getActionRecord("old")!!
        assertEquals("failed", old.terminalState)
        assertEquals("expired", old.errorCode)
        assertNull(old.executedAt)
        assertNull(old.providerRef)
        assertNull(old.result)
        assertNull(queue.getActionRecord("pending")!!.terminalState)
        assertFalse(queue.recordActionIfAbsent("old", "new-action", "c"))
        assertTrue(queue.setActionTerminal("pending", "executed", null,
            executedAt = 99L, result = mapOf("ok" to true)))
        assertEquals(mapOf("ok" to true), queue.getActionRecord("pending")!!.result)
    }

    @Test
    fun `扩展回执使用队列加密且损坏时不丢终态`() {
        val cipher = object : QueueCipher {
            override fun encrypt(plain: String): String =
                java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
            override fun decrypt(blob: String): String =
                String(java.util.Base64.getDecoder().decode(blob))
        }
        queue.close()
        queue = OfflineQueue(context, dbName = dbName, cipher = cipher)
        queue.recordActionIfAbsent("encrypted", "act-encrypted", "device.agent.observe")
        queue.setActionTerminal("encrypted", "executed", null, executedAt = 42L,
            providerRef = "private-provider", result = mapOf("screen" to "private-content"))
        val raw = queue.readableDatabase.rawQuery(
            "SELECT receipt_json FROM action_dedup WHERE idem_key='encrypted'", null,
        ).use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue(raw.startsWith(OfflineQueue.CIPHER_PREFIX))
        assertFalse(raw.contains("private-content"))
        assertFalse(raw.contains("private-provider"))
        queue.close()
        queue = OfflineQueue(context, dbName = dbName, cipher = cipher)
        assertEquals(mapOf("screen" to "private-content"), queue.getActionRecord("encrypted")!!.result)
        queue.writableDatabase.execSQL(
            "UPDATE action_dedup SET receipt_json='v1.!broken!' WHERE idem_key='encrypted'",
        )
        val damaged = queue.getActionRecord("encrypted")!!
        assertEquals("executed", damaged.terminalState)
        assertNull(damaged.result)
        assertFalse(queue.recordActionIfAbsent("encrypted", "retry", "device.agent.observe"))
    }

    @Test
    fun `容量 FIFO 淘汰最旧`() {
        // 用小容量验证淘汰（避免灌 4096 条拖慢测试）
        val small = OfflineQueue(context, dbName = "test_dedup_small.db")
        try {
            for (i in 1..(OfflineQueue.ACTION_DEDUP_CAPACITY + 50)) {
                small.recordActionIfAbsent("key-%05d".format(i), "a$i", "c", nowMs = i.toLong())
            }
            // 最旧的已被淘汰
            assertNull(small.getActionRecord("key-00001"))
            // 最新的仍在
            assertTrue(small.getActionRecord("key-04146") != null)
        } finally {
            small.close()
            context.deleteDatabase("test_dedup_small.db")
        }
    }

    @Test
    fun `v2 升级到 v3 保留队列并新增幂等表`() {
        // 手工建 v2 库（带密文前缀行）
        val path = context.getDatabasePath("test_upgrade23.db").absolutePath
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """CREATE TABLE uplink_queue (
                     event_id TEXT PRIMARY KEY, type TEXT NOT NULL, payload_json TEXT NOT NULL,
                     state INTEGER NOT NULL DEFAULT 0, drop_on_pause INTEGER NOT NULL DEFAULT 0,
                     created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE TABLE queue_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "INSERT INTO uplink_queue VALUES('e1','sensor.event','v1.xxx',0,0,1,1)",
            )
            db.version = 2
        }
        val q = OfflineQueue(context, dbName = "test_upgrade23.db")
        // v3 升级后：旧队列行仍在，幂等表可用
        assertEquals(1, q.stats().pending)
        assertTrue(q.recordActionIfAbsent("k9", "a9", "c"))
        q.close()
        context.deleteDatabase("test_upgrade23.db")
    }
}
