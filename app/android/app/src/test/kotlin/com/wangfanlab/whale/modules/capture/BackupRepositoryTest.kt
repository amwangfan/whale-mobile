package com.wangfanlab.whale.modules.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.modules.capture.backup.BackupRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Phase 3：备份导出/导入往返、幂等、校验失败、关联与时间戳保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRepositoryTest {

    private lateinit var context: Context
    private lateinit var storeA: CaptureStore // 源
    private lateinit var storeB: CaptureStore // 导入目标（模拟另一台设备/重装）
    private lateinit var zipFile: File

    private fun shotsDir(): File = File(context.filesDir, CaptureStore.SHOTS_DIR_RELATIVE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_backup_a.db")
        context.deleteDatabase("test_backup_b.db")
        shotsDir().deleteRecursively()
        storeA = CaptureStore(context, "test_backup_a.db")
        storeB = CaptureStore(context, "test_backup_b.db")
        zipFile = File(context.cacheDir, "backup_test.zip")
        zipFile.delete()
    }

    @After
    fun tearDown() {
        storeA.close()
        storeB.close()
        context.deleteDatabase("test_backup_a.db")
        context.deleteDatabase("test_backup_b.db")
        shotsDir().deleteRecursively()
        zipFile.delete()
    }

    private fun seedSource(): Pair<Long, String> {
        // 两帧一段 + 收藏 + 备注 + 图像文件
        val id1 = storeA.insert(
            CaptureStore.Row(0, 1111L, "com.a", "应用A", 11L, 1080, 2400,
                "ok", "第一段内容", "202611/1111.jpg", createdAt = 1111L),
        )
        val id2 = storeA.insert(
            CaptureStore.Row(0, 2222L, "com.a", "应用A", 22L, 1080, 2400,
                "ok", "第二段内容", "202611/2222.jpg", createdAt = 2222L),
        )
        val agg = SegmentAggregator(storeA) { "segment_BKTEST000000000000000001" }
        agg.onFrame(id1, "com.a", 1111L, "第一段内容")
        agg.onFrame(id2, "com.a", 2222L, "第二段内容")
        val segId = storeA.currentOpenSegment()!!.segmentId
        storeA.addFavorite(captureId = id1, note = "收藏一", createdAt = 3333L)
        storeA.addFavorite(segmentId = segId, createdAt = 3334L)
        storeA.addNote(captureId = id2, text = "备注内容", createdAt = 4444L)
        // 图像文件（源）
        for (ts in listOf(1111L, 2222L)) {
            val f = File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/202611/$ts.jpg")
            f.parentFile?.mkdirs()
            f.writeBytes("jpeg-bytes-$ts".toByteArray())
        }
        return id1 to segId
    }

    @Test
    fun `导出导入往返保留关联与时间戳`() {
        val (srcId1, segId) = seedSource()
        val export = BackupRepository(context, storeA).exportZip(zipFile)
        assertTrue(zipFile.isFile)
        assertEquals(2, export.captures)
        assertEquals(1, export.segments)
        assertEquals(2, export.favorites)
        assertEquals(1, export.notes)
        assertEquals(2, export.files)
        // manifest 存在且含文件清单
        ZipFile(zipFile).use { z ->
            assertNotNull(z.getEntry("manifest.json"))
            assertNotNull(z.getEntry("data/captures.jsonl"))
            assertNotNull(z.getEntry("files/202611/1111.jpg"))
        }
        // 删除本地图像（模拟重装/换机后的空环境）再导入
        shotsDir().deleteRecursively()

        val report = BackupRepository(context, storeB).importZip(zipFile)
        assertFalse(report.skippedDuplicateBackup)
        assertEquals(2, report.capturesImported)
        assertEquals(1, report.segmentsImported)
        assertEquals(2, report.favoritesImported)
        assertEquals(1, report.notesImported)
        assertEquals(2, report.filesImported)
        assertEquals(0, report.rowsFailed)
        assertEquals(0, report.checksumFailed)
        assertTrue(report.errors.isEmpty())

        // 时间戳保留
        val rows = storeB.listCaptures()
        assertEquals(2, rows.size)
        assertEquals(1111L, rows[0].captureTime)
        assertEquals("第一段内容", rows[0].ocrText)
        assertTrue(rows[0].createdAt == 1111L)
        // 段保留 segment_id 且 evidence 重映射到新行 id
        val seg = storeB.getSegment(segId)
        assertNotNull(seg)
        assertEquals(2, seg!!.memberCount)
        val newIds = rows.map { it.id }.toSet()
        val evidenceIds = seg.evidence.mapNotNull { CaptureStore.evidenceRefToRowId(it) }
        assertEquals(2, evidenceIds.size)
        assertTrue(newIds.containsAll(evidenceIds))
        // 收藏/备注关联重映射
        val favs = storeB.listFavorites()
        assertEquals(2, favs.size)
        val favCapture = favs.first { it.captureId != null }
        assertTrue(favCapture.captureId in newIds)
        assertEquals("收藏一", favCapture.note)
        assertEquals(3333L, favCapture.createdAt)
        val notes = storeB.listNotes()
        assertEquals(1, notes.size)
        assertEquals(4444L, notes[0].createdAt)
        // 图像文件已落位且内容一致
        val f = File(context.filesDir, "${CaptureStore.SHOTS_DIR_RELATIVE}/202611/1111.jpg")
        assertEquals("jpeg-bytes-1111", f.readText())
    }

    @Test
    fun `重复导入同一备份幂等`() {
        seedSource()
        BackupRepository(context, storeA).exportZip(zipFile)
        val repo = BackupRepository(context, storeB)
        val r1 = repo.importZip(zipFile)
        assertFalse(r1.skippedDuplicateBackup)
        val r2 = repo.importZip(zipFile)
        assertTrue(r2.skippedDuplicateBackup)
        assertEquals(0, r2.capturesImported)
        assertEquals(2, storeB.count())
        assertEquals(2, storeB.listFavorites().size)
        assertEquals(1, storeB.listNotes().size)
        assertEquals(1, storeB.listSegments(Int.MAX_VALUE).size)
    }

    @Test
    fun `部分重叠备份行级去重`() {
        seedSource()
        BackupRepository(context, storeA).exportZip(zipFile)
        // 目标库已有一行与备份冲突（同去重键）
        storeB.insert(
            CaptureStore.Row(0, 1111L, "com.a", "应用A", 11L, 1, 1,
                "ok", "已存在", "", createdAt = 9999L),
        )
        val report = BackupRepository(context, storeB).importZip(zipFile)
        assertEquals(1, report.capturesImported)
        assertEquals(1, report.rowsSkippedDuplicate)
        assertEquals(2, storeB.count())
        // 冲突行的收藏映射到既有行
        val existingId = storeB.findIdByKey(1111L, "com.a", 11L)!!
        assertTrue(storeB.isFavoriteCapture(existingId))
    }

    @Test
    fun `文件校验失败计入报告且不导入该文件`() {
        seedSource()
        BackupRepository(context, storeA).exportZip(zipFile)
        // 篡改 zip 内一个文件（重写 zip，破坏 sha256 对应关系）
        val tampered = File(context.cacheDir, "backup_tampered.zip")
        ZipFile(zipFile).use { zin ->
            java.util.zip.ZipOutputStream(tampered.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val bytes = zin.getInputStream(e).readBytes()
                    if (e.name == "files/202611/1111.jpg") {
                        zout.write("tampered".toByteArray())
                    } else {
                        zout.write(bytes)
                    }
                    zout.closeEntry()
                }
            }
        }
        val report = BackupRepository(context, storeB).importZip(tampered)
        assertEquals(1, report.checksumFailed)
        assertEquals(1, report.filesImported)
        // 行仍导入但图像引用清空
        val row = storeB.listCaptures().first { it.captureTime == 1111L }
        assertEquals("", row.imagePath)
        tampered.delete()
    }

    @Test
    fun `坏包拒绝`() {
        val bad = File(context.cacheDir, "bad.zip")
        java.util.zip.ZipOutputStream(bad.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("random.txt"))
            it.write("x".toByteArray())
            it.closeEntry()
        }
        val report = BackupRepository(context, storeB).importZip(bad)
        assertEquals(1, report.rowsFailed)
        assertFalse(report.errors.isEmpty())
        bad.delete()
    }
}
