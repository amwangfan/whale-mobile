package com.wangfanlab.whale.modules.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.wangfanlab.whale.transport.v2.EnvelopeCodecV2
import com.wangfanlab.whale.transport.v2.EventOutboxV2
import com.wangfanlab.whale.transport.v2.IdsV2
import com.wangfanlab.whale.transport.v2.MsgTypeV2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3：段聚合边界（同包名/间隔 120s/换包）与 segment_ready 事件合规。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SegmentAggregatorTest {

    private lateinit var context: Context
    private val dbName = "test_capture_seg.db"
    private lateinit var store: CaptureStore
    private lateinit var aggregator: SegmentAggregator
    private var idCounter = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        context.deleteDatabase("test_outbox_seg.db")
        store = CaptureStore(context, dbName)
        // 确定性 segment_id，便于断言
        aggregator = SegmentAggregator(store) { "segment_TEST%024d".format(++idCounter) }
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(dbName)
        context.deleteDatabase("test_outbox_seg.db")
    }

    private fun insertShot(ts: Long, pkg: String, ocr: String? = null): Long =
        store.insert(
            CaptureStore.Row(
                id = 0, captureTime = ts, packageName = pkg, appLabel = pkg,
                dhash = ts, width = 1, height = 1,
                ocrStatus = "ok", ocrText = ocr, imagePath = "",
            ),
        )

    @Test
    fun `同包名间隔小于120s归入当前段`() {
        val r1 = aggregator.onFrame(insertShot(10_000, "com.a"), "com.a", 10_000, "开始")
        assertTrue(r1 is SegmentAggregator.Result.Opened)
        val segId = r1.segmentId
        // 119.9s 内 → 归段
        val r2 = aggregator.onFrame(insertShot(10_000 + 119_999, "com.a"), "com.a", 10_000 + 119_999, null)
        assertTrue(r2 is SegmentAggregator.Result.Appended)
        assertEquals(segId, r2.segmentId)
        val seg = store.getSegment(segId)!!
        assertEquals(2, seg.memberCount)
        assertEquals(10_000, seg.startedAt)
        assertEquals(10_000 + 119_999, seg.endedAt)
        assertEquals("开始", seg.summary) // 首条非空 OCR 候选为段摘要
    }

    @Test
    fun `间隔达到120s开新段且旧段封口`() {
        aggregator.onFrame(insertShot(0, "com.a"), "com.a", 0, "旧段")
        val r = aggregator.onFrame(
            insertShot(SegmentAggregator.GAP_MS, "com.a"),
            "com.a", SegmentAggregator.GAP_MS, "新段",
        )
        assertTrue(r is SegmentAggregator.Result.ClosedAndOpened)
        r as SegmentAggregator.Result.ClosedAndOpened
        assertNotEquals(r.segmentId, r.closed.segmentId)
        assertTrue(r.closed.closed)
        assertEquals("com.a", r.closed.packageName)
        assertEquals(1, r.closed.memberCount)
        assertEquals("旧段", r.closed.summary)
        // 新段为当前段
        assertEquals(r.segmentId, store.currentOpenSegment()!!.segmentId)
    }

    @Test
    fun `换包名立即开新段`() {
        aggregator.onFrame(insertShot(0, "com.a"), "com.a", 0, null)
        val r = aggregator.onFrame(insertShot(5_000, "com.b"), "com.b", 5_000, null)
        assertTrue(r is SegmentAggregator.Result.ClosedAndOpened)
        r as SegmentAggregator.Result.ClosedAndOpened
        assertEquals("com.a", r.closed.packageName)
        assertEquals("com.b", store.getSegment(r.segmentId)!!.packageName)
    }

    @Test
    fun `flush 封口当前段且无段时返回 null`() {
        assertNull(aggregator.flush())
        aggregator.onFrame(insertShot(0, "com.a"), "com.a", 0, "x")
        val flushed = aggregator.flush()
        assertNotNull(flushed)
        assertTrue(flushed!!.closed)
        assertNull(store.currentOpenSegment())
        // 再次 flush 无段
        assertNull(aggregator.flush())
    }

    @Test
    fun `段证据引用形态为 capture-行id`() {
        val rowId = insertShot(0, "com.a")
        val r = aggregator.onFrame(rowId, "com.a", 0, null)
        val seg = store.getSegment(r.segmentId)!!
        assertEquals(listOf("capture:$rowId"), seg.evidence)
    }

    // ------------------------------------------------------------- 事件产出（§9 合规）

    @Test
    fun `pipeline 换段时产出合规 segment_ready 事件到 EventOutboxV2`() {
        val outbox = EventOutboxV2(
            context, null,
            deviceIdProvider = { "ba4bb14c75917cfc0e69fb7a1caf8ba3" },
            dbName = "test_outbox_seg.db",
        )
        try {
            val emitter = SegmentReadyEmitter(outbox)
            val events = mutableListOf<CaptureStore.Segment>()
            val pipeline = CapturePipeline(
                context, store,
                ocr = { "页面文本内容" }, // 假 OCR
                segmentAggregator = aggregator,
                onSegmentReady = { seg -> events.add(seg); emitter.onSegmentReady(seg) },
            )
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            // 帧 1（开段）→ 帧 2（120s 后换段，封口段 1 → 事件）
            pipeline.process(bmp, dhash = 1L, packageName = "com.video",
                appLabel = "视频", captureTimeMs = 1_000)
            assertEquals(0, events.size)
            pipeline.process(bmp, dhash = 2L, packageName = "com.video",
                appLabel = "视频", captureTimeMs = 1_000 + SegmentAggregator.GAP_MS)
            assertEquals(1, events.size)

            // 队列里恰有一条 experience.event，字段与 contracts/v2 §9 对齐
            val batch = outbox.pendingBatch()
            assertEquals(1, batch.size)
            assertEquals(MsgTypeV2.EXPERIENCE_EVENT, batch[0].type)
            val p = EnvelopeCodecV2.payloadFromJson(batch[0].payloadJson)
            assertTrue(IdsV2.EVENT_ID.matches(p["event_id"] as String))
            assertEquals("screen.segment_ready", p["kind"])
            assertEquals("screen_memo", p["source"])
            assertEquals("owner_private", p["privacy_class"])
            assertEquals("2.0", p["schema_version"])
            assertEquals("ba4bb14c75917cfc0e69fb7a1caf8ba3", p["device_id"])
            val ref = p["evidence_ref"] as String
            assertTrue("evidence_ref=$ref 应为 capture:segment_ 形态",
                ref.matches(Regex("^capture:segment_[0-9A-Za-z]+$")))
            assertEquals("capture:${events[0].segmentId}", ref)
            assertEquals("页面文本内容", p["summary"])
            assertEquals(events[0].endedAt, p["occurred_at"])
            @Suppress("UNCHECKED_CAST")
            val extras = p["extras"] as Map<String, Any?>
            assertEquals("com.video", extras["package"])
            assertEquals(1L, (extras["member_count"] as Number).toLong())
        } finally {
            outbox.close()
        }
    }

    @Test
    fun `emitter 无摘要时使用占位摘要`() {
        val outbox = EventOutboxV2(
            context, null,
            deviceIdProvider = { "ba4bb14c75917cfc0e69fb7a1caf8ba3" },
            dbName = "test_outbox_seg.db",
        )
        try {
            store.openSegment("segment_EMPTY", "com.a", 1000, null)
            store.appendSegmentEvidence("segment_EMPTY", "capture:1", 1000, null)
            val seg = store.closeSegment("segment_EMPTY")!!
            val emitter = SegmentReadyEmitter(outbox)
            val eventId = emitter.onSegmentReady(seg)
            assertTrue(IdsV2.EVENT_ID.matches(eventId))
            val p = EnvelopeCodecV2.payloadFromJson(outbox.pendingBatch()[0].payloadJson)
            assertTrue((p["summary"] as String).contains("com.a"))
            assertEquals("capture:segment_EMPTY", p["evidence_ref"])
        } finally {
            outbox.close()
        }
    }
}
