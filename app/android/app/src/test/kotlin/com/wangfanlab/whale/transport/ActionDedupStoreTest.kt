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
