package com.wangfanlab.whale.modules.recording

import com.wangfanlab.whale.transport.v2.ErrorCodeV2
import com.wangfanlab.whale.transport.v2.IdsV2
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2
import com.wangfanlab.whale.transport.v2.ReceiptStatus
import com.wangfanlab.whale.transport.v2.payloadLongV2

/**
 * MediaProjection 录制状态机（contracts/v2 §12.2，纯逻辑可单测）。
 *
 * 职责：
 * - 解析/校验 recording.control payload（run_id/op/options 值域，与
 *   recording.control.schema.json 对齐）；
 * - 驱动 [RecordingDriver]（真实实现=RecordingService 内 MediaProjection 管线；
 *   测试注入假实现）并按状态迁移回报 recording.state
 *   （idle/recording/paused/stopped/failed；uploaded 属 core 侧，端侧不产生）；
 * - 排除包名遮罩判定：options.exclude_packages ∪ [localExcludedProvider]
 *   （capture 模块 ExcludedPackagesStore）命中前台包名 → [RecordingDriver.setMasked]
 *   （遮罩语义=**暂停写入**：遮罩期间编码输出整体丢弃、视频时间轴断档，
 *   不留任何遮罩期间像素；选择理由见 RecordingService 注释）；
 * - 时长统计只累计「录制中且未暂停且未遮罩」的区间；
 * - max_duration_ms 到期自动 stop（由服务侧周期 [tick] 驱动）。
 *
 * 幂等与诚实性：
 * - 同 run_id 重复 start：重报当前状态（幂等），不重复起录；
 * - 异 run_id 并发 start：拒绝（单实例录制，failed/unavailable）；
 * - 未知 run 的 pause/resume/stop：回报 failed/unavailable（不谎报成功）；
 * - 驱动层异常 → failed/internal；缺录制授权 → failed/permission_missing。
 */
class RecordingController(
    private val driver: RecordingDriver,
    private val stateSender: StateSender,
    /** 本地排除包名（capture ExcludedPackagesStore 快照源）。 */
    private val localExcludedProvider: () -> Set<String> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** recording.state 上行口（接线 = WssClientV2.sendRecordingState）。 */
    fun interface StateSender {
        fun send(runId: String, state: String, fileRef: String?, durationMs: Long?, errorCode: String?)
    }

    /** 录制驱动（服务侧 MediaProjection 管线实现；方法返回 errorCode 或 null=成功）。 */
    interface RecordingDriver {
        /** 起录（runId 对应输出文件）；返回 null=成功，否则 §15 errorCode。 */
        fun start(runId: String, options: Options): String?

        fun pause(): String?

        fun resume(): String?

        /** 停录并封口文件；返回 null=成功，否则 §15 errorCode。 */
        fun stop(): String?

        /** 遮罩开关（true=暂停写入）。 */
        fun setMasked(masked: Boolean)
    }

    /** recording.control.options（schema 值域对齐）。 */
    data class Options(
        val profile: String?,
        val maxDurationMs: Long?,
        val excludePackages: Set<String>,
    )

    /** 解析后的控制请求。 */
    data class ControlRequest(
        val runId: String,
        val op: String,
        val options: Options,
    )

    /** 一次 recording.state 回报（测试断言用；toPayload 与 schema 键序一致）。 */
    data class StateReport(
        val runId: String,
        val state: String,
        val fileRef: String? = null,
        val durationMs: Long? = null,
        val errorCode: String? = null,
    ) {
        fun toPayload(): Map<String, Any?> {
            val p = LinkedHashMap<String, Any?>()
            p["run_id"] = runId
            p["state"] = state
            fileRef?.let { p["file_ref"] = it }
            durationMs?.let { p["duration_ms"] = it }
            errorCode?.let { p["error_code"] = it }
            return p
        }
    }

    /** 活动录制（同一时刻仅一个；字段仅本类方法内读写，调用方须串行化）。 */
    private data class Active(
        val runId: String,
        val options: Options,
        /** 录制段起点（clock ms）。 */
        val startedAt: Long,
        /** 当前段（未暂停未遮罩）起点。 */
        var segmentStart: Long,
        /** 已累计的「录制中且未暂停未遮罩」时长。 */
        var accruedMs: Long = 0,
        var paused: Boolean = false,
        var masked: Boolean = false,
        /** 已生效的排除包名（options ∪ 本地存储快照）。 */
        val excluded: Set<String>,
    ) {
        /** 有效录制时长（不含暂停/遮罩区间）。 */
        fun durationMs(now: Long): Long =
            accruedMs + if (paused || masked) 0L else (now - segmentStart).coerceAtLeast(0L)
    }

    private var active: Active? = null

    /** 最近一次的 run 状态（stop 后保留，供重复 stop/状态查询重报）。 */
    private var lastReport: StateReport? = null

    // ------------------------------------------------------------- payload 解析

    /**
     * 解析 recording.control payload（WssClientV2 已做 run_id/op 一级校验，
     * 此处做 options 值域校验；非法返回 null）。
     */
    fun parseControl(payload: Map<String, Any?>): ControlRequest? {
        val runId = payload["run_id"] as? String ?: return null
        if (!IdsV2.RUN_ID.matches(runId)) return null
        val op = payload["op"] as? String ?: return null
        if (op !in OPS) return null
        @Suppress("UNCHECKED_CAST")
        val optionsMap = payload["options"] as? Map<String, Any?>
        val options = parseOptions(optionsMap) ?: return null
        return ControlRequest(runId, op, options)
    }

    /** options 值域（schema：profile ≤32 字符；max_duration_ms 1000..3600000；exclude_packages ≤32 项、单项 ≤128 字符）。 */
    fun parseOptions(map: Map<String, Any?>?): Options? {
        if (map == null) return Options(null, null, emptySet())
        // profile：存在时必须是 ≤32 字符的字符串，否则整体判定非法
        val rawProfile = map["profile"]
        if (rawProfile != null && (rawProfile !is String || rawProfile.length > 32)) return null
        val profile = rawProfile as? String
        val maxDuration = payloadLongV2(map, "max_duration_ms")
        if (maxDuration != null && (maxDuration < 1000 || maxDuration > 3_600_000)) return null
        @Suppress("UNCHECKED_CAST")
        val excludes = (map["exclude_packages"] as? List<*>)?.filterIsInstance<String>()
            ?: emptyList()
        if (excludes.size > 32 || excludes.any { it.isEmpty() || it.length > 128 }) return null
        return Options(profile, maxDuration, excludes.toSet())
    }

    // ------------------------------------------------------------- 控制入口

    /**
     * 处理一条控制请求（recording.control 或 device.screen.record action 包装）。
     * 回报经 [StateSender] 发出，同时返回给调用方（测试断言用）。
     */
    @Synchronized
    fun handle(request: ControlRequest): StateReport {
        val cur = active
        val now = clock()
        val report = when (request.op) {
            "start" -> when {
                // 同 run 重复 start：幂等重报当前状态（不重复起录）
                cur != null && cur.runId == request.runId -> reportOf(cur, now)
                // 异 run 并发：单实例录制，拒绝
                cur != null -> StateReport(
                    request.runId, "failed", errorCode = ErrorCodeV2.UNAVAILABLE,
                )
                else -> startLocked(request, now)
            }
            "pause" -> when {
                cur == null || cur.runId != request.runId -> unknownRun(request.runId)
                cur.paused -> reportOf(cur, now) // 幂等
                else -> {
                    val err = driver.pause()
                    if (err != null) {
                        failLocked(cur, now, err)
                    } else {
                        cur.accruedMs = cur.durationMs(now)
                        cur.paused = true
                        reportOf(cur, now)
                    }
                }
            }
            "resume" -> when {
                cur == null || cur.runId != request.runId -> unknownRun(request.runId)
                !cur.paused -> reportOf(cur, now) // 幂等
                else -> {
                    val err = driver.resume()
                    if (err != null) {
                        failLocked(cur, now, err)
                    } else {
                        cur.paused = false
                        cur.segmentStart = now
                        reportOf(cur, now)
                    }
                }
            }
            "stop" -> when {
                cur == null || cur.runId != request.runId -> unknownRun(request.runId)
                else -> stopLocked(cur, now)
            }
            else -> StateReport(request.runId, "failed", errorCode = ErrorCodeV2.INVALID_MESSAGE)
        }
        emit(report)
        return report
    }

    private fun startLocked(request: ControlRequest, now: Long): StateReport {
        val merged = request.options.copy(
            excludePackages = request.options.excludePackages + localExcludedProvider(),
        )
        val err = try {
            driver.start(request.runId, merged)
        } catch (t: Throwable) {
            ErrorCodeV2.INTERNAL
        }
        return if (err != null) {
            StateReport(request.runId, "failed", errorCode = err)
        } else {
            val a = Active(
                runId = request.runId, options = merged,
                startedAt = now, segmentStart = now,
                excluded = merged.excludePackages,
            )
            active = a
            StateReport(a.runId, "recording", fileRef = fileRefOf(a.runId), durationMs = 0)
        }
    }

    private fun stopLocked(cur: Active, now: Long): StateReport {
        val duration = cur.durationMs(now)
        val err = try {
            driver.stop()
        } catch (t: Throwable) {
            ErrorCodeV2.INTERNAL
        }
        active = null
        return if (err != null) {
            StateReport(cur.runId, "failed", durationMs = duration, errorCode = err)
        } else {
            StateReport(cur.runId, "stopped", fileRef = fileRefOf(cur.runId), durationMs = duration)
        }
    }

    private fun failLocked(cur: Active, now: Long, errorCode: String): StateReport {
        val duration = cur.durationMs(now)
        try {
            driver.stop()
        } catch (_: Throwable) {
        }
        active = null
        return StateReport(cur.runId, "failed", durationMs = duration, errorCode = errorCode)
    }

    private fun unknownRun(runId: String) =
        StateReport(runId, "failed", errorCode = ErrorCodeV2.UNAVAILABLE)

    private fun reportOf(cur: Active, now: Long): StateReport = StateReport(
        cur.runId,
        if (cur.paused) "paused" else "recording",
        fileRef = fileRefOf(cur.runId),
        durationMs = cur.durationMs(now),
    )

    private fun emit(report: StateReport) {
        lastReport = report
        stateSender.send(
            report.runId, report.state, report.fileRef, report.durationMs, report.errorCode,
        )
    }

    // ------------------------------------------------------------- 遮罩与时长

    /**
     * 前台包名变化（服务侧周期轮询驱动）：命中排除集合 → 遮罩开（暂停写入）；
     * 离开 → 遮罩关。返回遮罩是否生效（测试断言用）。
     */
    @Synchronized
    fun onForegroundPackage(pkg: String?): Boolean {
        val cur = active ?: return false
        val now = clock()
        val shouldMask = pkg != null && pkg in cur.excluded
        if (shouldMask == cur.masked) return cur.masked
        if (shouldMask) {
            cur.accruedMs = cur.durationMs(now)
            cur.masked = true
            driver.setMasked(true)
        } else {
            cur.masked = false
            cur.segmentStart = now
            driver.setMasked(false)
        }
        return cur.masked
    }

    /** 周期 tick（服务侧 1s 节拍）：max_duration_ms 到期自动 stop；返回当前状态报告（无活动=null）。 */
    @Synchronized
    fun tick(): StateReport? {
        val cur = active ?: return null
        val now = clock()
        val max = cur.options.maxDurationMs
        if (max != null && cur.durationMs(now) >= max) {
            val report = stopLocked(cur, now)
            emit(report)
            return report
        }
        return reportOf(cur, now)
    }

    /** 当前活动 run（诊断用）。 */
    @Synchronized
    fun activeRunId(): String? = active?.runId

    /** 当前遮罩状态（诊断/测试用）。 */
    @Synchronized
    fun isMasked(): Boolean = active?.masked == true

    // ------------------------------------------------------------- device.screen.record action 适配

    /**
     * device.screen.record 的 action handler 适配（§5.3 允许 action.request 驱动）：
     * arguments={op, run_id?, options?}；run_id 缺省取 request.runId；
     * 控制被接受 → succeeded（终态细节由异步 recording.state 回报携带）。
     */
    fun asActionHandler(): ReceiptDispatcherV2.ActionHandler =
        ReceiptDispatcherV2.ActionHandler { request, reply ->
            val args = request.arguments
            val op = (args["op"] as? String)?.trim()
            val runId = (args["run_id"] as? String) ?: request.runId
            @Suppress("UNCHECKED_CAST")
            val optionsMap = args["options"] as? Map<String, Any?>
            val control = when {
                op == null || op !in OPS -> null
                runId == null || !IdsV2.RUN_ID.matches(runId) -> null
                else -> parseOptions(optionsMap)?.let { ControlRequest(runId, op, it) }
            }
            if (control == null) {
                reply(
                    ReceiptDispatcherV2.ActionOutcome(
                        ReceiptStatus.FAILED, errorCode = ErrorCodeV2.INVALID_MESSAGE,
                        resultSummary = "device.screen.record 参数非法（需 op∈start/pause/resume/stop + run_id）",
                    ),
                )
                return@ActionHandler
            }
            val report = handle(control)
            if (report.state == "failed") {
                reply(
                    ReceiptDispatcherV2.ActionOutcome(
                        ReceiptStatus.FAILED, errorCode = report.errorCode ?: ErrorCodeV2.INTERNAL,
                        resultSummary = "录制 ${control.op} 失败（${report.errorCode}）",
                    ),
                )
            } else {
                reply(
                    ReceiptDispatcherV2.ActionOutcome(
                        ReceiptStatus.SUCCEEDED,
                        resultSummary = "录制 ${control.op} 已受理：${report.state} run=${report.runId}",
                        outputRef = report.fileRef,
                        verificationStatus = "skipped",
                        verificationSummary = "录制状态经 recording.state 异步回报",
                    ),
                )
            }
        }

    companion object {
        val OPS = setOf("start", "pause", "resume", "stop")

        /** §12.2 文件引用形态：recording:<run_id>/video。 */
        fun fileRefOf(runId: String): String = "recording:$runId/video"
    }
}
