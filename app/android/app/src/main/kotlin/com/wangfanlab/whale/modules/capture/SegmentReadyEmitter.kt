package com.wangfanlab.whale.modules.capture

import android.util.Log
import com.wangfanlab.whale.transport.v2.EventOutboxV2

/**
 * segment_ready 经历事件产出（Phase 3，contracts/v2 §9）。
 *
 * 段封口（[SegmentAggregator] 换段/flush）时把段摘要写入 v2 上行队列：
 * - kind=`screen.segment_ready`、source=`screen_memo`、
 *   privacy_class=`owner_private`；
 * - evidence_ref=`capture:segment_<ULID>`（§10 capture: 命名空间，
 *   段成员帧经 sensor.query `screen.evidence.read` 按需索取，不随事件上行）；
 * - event_id 由 [EventOutboxV2] 生成（`evt_`+ULID 幂等键）；
 * - extras 仅含结构化元数据（包名/起止时间/成员数），**不含原始 OCR/截图**。
 *
 * 使用方式：`CapturePipeline(..., onSegmentReady = emitter::onSegmentReady)`。
 */
class SegmentReadyEmitter(
    private val outbox: EventOutboxV2,
) {

    /** 段封口回调：组装并入队 experience.event，返回 event_id。 */
    fun onSegmentReady(segment: CaptureStore.Segment): String {
        val summary = segment.summary?.take(MAX_EVENT_SUMMARY)
            ?: "屏幕片段 ${segment.packageName}（${segment.memberCount} 帧）"
        val eventId = outbox.enqueueExperienceEvent(
            kind = KIND,
            occurredAt = segment.endedAt,
            source = SOURCE,
            summary = summary,
            privacyClass = PRIVACY_CLASS,
            evidenceRef = "capture:${segment.segmentId}",
            extras = mapOf(
                "package" to segment.packageName,
                "started_at" to segment.startedAt,
                "ended_at" to segment.endedAt,
                "member_count" to segment.memberCount,
            ),
        )
        Log.i(TAG, "segment_ready 已入队 event=$eventId segment=${segment.segmentId}")
        return eventId
    }

    companion object {
        private const val TAG = "WhaleSegReady"
        const val KIND = "screen.segment_ready"
        const val SOURCE = "screen_memo"
        const val PRIVACY_CLASS = "owner_private"
        private const val MAX_EVENT_SUMMARY = 200
    }
}
