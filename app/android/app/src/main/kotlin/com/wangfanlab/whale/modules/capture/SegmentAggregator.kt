package com.wangfanlab.whale.modules.capture

import com.wangfanlab.whale.transport.v2.Ulid

/**
 * 动态段聚合（Phase 3「动态重建」）：把连续唯一帧合并为 segment。
 *
 * 规则（与规划 §3.2 一致）：
 * - 当前段与帧**同 package** 且帧时间与段 ended_at 间隔 **< [GAP_MS]（120s）**
 *   → 归入当前段（追加证据 `capture:<rowId>`）；
 * - 否则（换包名 / 间隔到阈值）先封口当前段（产出 segment_ready 事件素材），
 *   再开新段；
 * - segment_id 形如 `segment_<ULID>`（contracts/v2 §9 示例形态，
 *   evidence_ref = `capture:segment_<ULID>`）。
 *
 * 纯存储层逻辑（不依赖位图/服务），Robolectric 可测；时间源可注入以测边界。
 */
class SegmentAggregator(
    private val store: CaptureStore,
    private val idGen: () -> String = { "segment_" + Ulid.newUlid() },
) {

    /** 聚合结果；[ClosedAndOpened] 携带刚封口的段供事件产出。 */
    sealed interface Result {
        val segmentId: String

        /** 归入当前段。 */
        data class Appended(override val segmentId: String) : Result

        /** 无进行中段，直接开新段。 */
        data class Opened(override val segmentId: String) : Result

        /** 旧段封口 + 新段开启。 */
        data class ClosedAndOpened(
            val closed: CaptureStore.Segment,
            override val segmentId: String,
        ) : Result
    }

    /**
     * 喂入一帧已入库的唯一帧（[CapturePipeline] 在 insert 成功后调用）。
     */
    @Synchronized
    fun onFrame(
        captureRowId: Long,
        packageName: String,
        captureTimeMs: Long,
        ocrText: String?,
    ): Result {
        val current = store.currentOpenSegment()
        val ref = "capture:$captureRowId"
        if (current != null &&
            current.packageName == packageName &&
            captureTimeMs - current.endedAt < GAP_MS
        ) {
            store.appendSegmentEvidence(current.segmentId, ref, captureTimeMs, ocrText)
            return Result.Appended(current.segmentId)
        }
        // 换段：封口旧段（若存在），再开新段并收入本帧
        val closed = current?.let { store.closeSegment(it.segmentId) }
        val newId = idGen()
        store.openSegment(newId, packageName, captureTimeMs, summary = null)
        store.appendSegmentEvidence(newId, ref, captureTimeMs, ocrText)
        return if (closed != null) {
            Result.ClosedAndOpened(closed, newId)
        } else {
            Result.Opened(newId)
        }
    }

    /**
     * 显式收尾（服务停止 / 日切 / 用户暂停）：封口当前进行中段并返回
     * （供事件产出）；无进行中段返回 null。
     */
    @Synchronized
    fun flush(): CaptureStore.Segment? =
        store.currentOpenSegment()?.let { store.closeSegment(it.segmentId) }

    companion object {
        /** 同包名帧归入当前段的最大间隔（规划值：120s）。 */
        const val GAP_MS = 120_000L
    }
}
