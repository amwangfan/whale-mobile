package com.wangfanlab.whale.transport.v2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 下行动作幂等去重表（contracts/v2 §16）：跨重启持久化，idempotency_key 主键。
 *
 * - 与 v1 action_dedup 语义相同（首终态不可翻案），但状态机为 V2
 *   （succeeded/failed/timeout/cancelled/unknown），故独立建表/独立 DB；
 * - 终态回执全文（receipt payload JSON）加密保存（沿用 [QueueCipher]/
 *   [OfflineQueue.CIPHER_PREFIX] 机制），重复键重发时原样回放；
 * - 容量有界（FIFO 淘汰 [CAPACITY]）。
 */
class ActionDedupStoreV2(
    context: Context,
    dbName: String = DEFAULT_DB_NAME,
    private val cipher: QueueCipher? = null,
) : SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

    /** 一条幂等记录；terminalState 为 null 表示「已记录未终结」（跨重启发现时须回 unknown）。 */
    data class Record(
        val idemKey: String,
        val actionId: String,
        val taskId: String?,
        val capability: String,
        val terminalState: ReceiptStatus?,
        /** 终态回执 payload（含 recorded action_id），用于重复键原样回放（§7.1）。 */
        val terminalReceipt: Map<String, Any?>?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE action_dedup_v2 (
              idem_key       TEXT PRIMARY KEY,
              action_id      TEXT NOT NULL,
              task_id        TEXT,
              capability     TEXT NOT NULL,
              terminal_state TEXT,
              receipt_json   TEXT,
              created_at     INTEGER NOT NULL,
              updated_at     INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_action_dedup_v2_action_id ON action_dedup_v2(action_id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 即当前结构；后续结构演进在此写迁移（终态表宁可保留也不丢，幂等语义依赖它）。
    }

    /** 记录新动作（幂等）。返回 true=新插入；false=键已存在（竞态或重复下发）。 */
    @Synchronized
    fun recordIfAbsent(
        idemKey: String,
        actionId: String,
        taskId: String?,
        capability: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val values = ContentValues().apply {
            put("idem_key", idemKey)
            put("action_id", actionId)
            put("task_id", taskId)
            put("capability", capability)
            put("created_at", nowMs)
            put("updated_at", nowMs)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "action_dedup_v2", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (rowId >= 0) evict(writableDatabase)
        return rowId >= 0
    }

    fun get(idemKey: String): Record? = query("idem_key = ?", arrayOf(idemKey))

    /** 业务层按 action_id 补记终态时反查幂等键。 */
    fun getByActionId(actionId: String): Record? = query("action_id = ?", arrayOf(actionId))

    /**
     * 落终态（first terminal wins）。已有终态时返回 false，绝不翻案。
     * [receiptPayload] 为完整终态回执 payload（含 action_id/status/时间戳等），
     * 加密后保存，用于重复键重发真实终态。
     */
    @Synchronized
    fun setTerminal(
        idemKey: String,
        state: ReceiptStatus,
        receiptPayload: Map<String, Any?>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        require(state.terminal) { "setTerminal 只接受终态: $state" }
        val values = ContentValues().apply {
            put("terminal_state", state.wire)
            put("receipt_json", encrypt(EnvelopeCodecV2.payloadToJson(receiptPayload)))
            put("updated_at", nowMs)
        }
        // WHERE terminal_state IS NULL：SQL 层保证 first-wins，不依赖调用方时序。
        return writableDatabase.update(
            "action_dedup_v2", values,
            "idem_key = ? AND terminal_state IS NULL", arrayOf(idemKey),
        ) > 0
    }

    /** 撤销设备时调用（§16：撤销后队列清空）。 */
    @Synchronized
    fun clear() {
        writableDatabase.delete("action_dedup_v2", null, null)
    }

    private fun query(where: String, args: Array<String>): Record? = readableDatabase.query(
        "action_dedup_v2",
        arrayOf("idem_key", "action_id", "task_id", "capability", "terminal_state", "receipt_json"),
        where, args, null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) return null
        val receipt = if (c.isNull(5)) null else try {
            EnvelopeCodecV2.payloadFromJson(decrypt(c.getString(5)))
        } catch (t: Throwable) {
            // 损坏/密钥失效不能让副作用重放；保留终态标记，仅丢失回放内容。
            Log.w(TAG, "终态回执扩展字段不可读取（idem_key=${c.getString(0)}）", t)
            null
        }
        Record(
            idemKey = c.getString(0),
            actionId = c.getString(1),
            taskId = if (c.isNull(2)) null else c.getString(2),
            capability = c.getString(3),
            terminalState = if (c.isNull(4)) null else ReceiptStatus.of(c.getString(4)),
            terminalReceipt = receipt,
        )
    }

    private fun evict(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM action_dedup_v2 WHERE idem_key IN (" +
                "SELECT idem_key FROM action_dedup_v2 ORDER BY created_at DESC " +
                "LIMIT -1 OFFSET $CAPACITY)",
        )
    }

    private fun encrypt(plain: String): String {
        val c = cipher ?: return plain
        return OfflineQueue.CIPHER_PREFIX + c.encrypt(plain)
    }

    private fun decrypt(stored: String): String {
        if (stored.startsWith(OfflineQueue.CIPHER_PREFIX)) {
            val c = cipher ?: throw SecurityException("密文存储但无 cipher")
            return c.decrypt(stored.removePrefix(OfflineQueue.CIPHER_PREFIX))
        }
        if (cipher != null) throw SecurityException("明文 receipt 出现在加密存储")
        return stored
    }

    companion object {
        private const val TAG = "WhaleDedupV2"
        private const val DB_VERSION = 1
        const val CAPACITY = 4096
        const val DEFAULT_DB_NAME = "whale_action_dedup_v2.db"
    }
}

/**
 * action.request 分发器（contracts/v2 §7/§16）。
 *
 * 职责：
 * - 结构校验（action_id/capability/idempotency_key/timeout_ms/expires_at/authorized_scope）；
 * - 幂等：按 idempotency_key 持久去重。重复键——在飞 → 重发 `received`（非终态）；
 *   已终结 → 回放已记录 action_id 的真实终态；跨重启未终结 → 终结为 `unknown`，
 *   **绝不重放副作用**；
 * - 校验链：paused → unavailable；expires_at 已过 → expired；capability 不在 §5.3
 *   动作子集 → capability_unknown；不在 authorized_scope / accepted_capabilities
 *   → scope_denied；
 * - 执行：capability → [ActionHandler] 注册表。有执行器 → `received` 后执行并回终态；
 *   无执行器但有 UI 委托 → `received` 后交 UI（终态由业务层经 [completeFromBusiness]
 *   补发）；两者皆无 → 立即 `failed/unavailable`（§16：不悬挂 received）；
 * - 超时：自执行启动起 [ActionRequestV2.timeoutMs] 未终结 → `timeout` 终态
 *   （§7.2：超时不等于未执行，core 不得自动重放）。
 *
 * 线程模型：所有状态变更在 [scheduler]（与 WssClientV2 共用的单线程 executor）上；
 * 执行器回复回调从任意线程 marshal 回 scheduler。执行器实现如需阻塞请自行切线程。
 */
class ReceiptDispatcherV2(
    private val store: ActionDedupStoreV2,
    /** 上行 action.receipt payload 发送口（由 WssClientV2 注入；在 scheduler 线程调用）。 */
    private val sendUplink: (Map<String, Any?>) -> Unit,
    private val scheduler: ScheduledExecutorService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 解析后的 action.request（§7.1）。 */
    data class ActionRequestV2(
        val actionId: String,
        val taskId: String?,
        val runId: String?,
        val capability: String,
        val arguments: Map<String, Any?>,
        val observationId: String?,
        val timeoutMs: Long,
        val expiresAt: Long,
        val idempotencyKey: String,
        val authorizedScope: List<String>,
        val approvalRef: String?,
        val correlationId: String?,
        /** 原始 payload（交执行器/UI 时使用，字段完整保真）。 */
        val raw: Map<String, Any?>,
    )

    /**
     * 动作执行器。实现必须（异步或同步）恰好调用一次 [reply]；
     * 超时由分发器兜底（超时后迟到的 reply 按 first-terminal-wins 丢弃）。
     */
    fun interface ActionHandler {
        fun execute(request: ActionRequestV2, reply: (ActionOutcome) -> Unit)
    }

    /** 执行器产出的终态结果（status 必须是终态）。 */
    data class ActionOutcome(
        val status: ReceiptStatus,
        val errorCode: String? = null,
        /** result.summary（≤2000 字符；大输出走 outputRef）。 */
        val resultSummary: String? = null,
        val outputRef: String? = null,
        /** verification.status（passed/failed/skipped；未做验证必须 skipped，§7.3）。 */
        val verificationStatus: String? = null,
        val verificationSummary: String? = null,
        val providerRef: String? = null,
    ) {
        init {
            require(status.terminal) { "ActionOutcome.status 必须是终态: $status" }
            require(status != ReceiptStatus.FAILED || errorCode != null) {
                "failed 终态必须携带 error_code（§7.2）"
            }
        }

        /** 组装 action.receipt 的 result 子对象（§7.3）。 */
        fun resultPayload(): Map<String, Any?>? {
            val summary = resultSummary ?: return null
            val result = LinkedHashMap<String, Any?>()
            result["summary"] = summary.take(2000)
            outputRef?.let { result["output_ref"] = it }
            if (verificationStatus != null) {
                val verification = LinkedHashMap<String, Any?>()
                verification["status"] = verificationStatus
                verification["summary"] = (verificationSummary ?: "").take(500)
                result["verification"] = verification
            }
            return result
        }
    }

    // ---- 外部可注入的策略（连接态/暂停态变化时由 WssClientV2/运行时更新） ----

    /** hello_ack 收窄后的能力集合（§5.2）；空集 = 尚未握手（此时一切动作拒绝）。 */
    @Volatile
    var acceptedCapabilitiesProvider: () -> Set<String> = { emptySet() }

    /** 全局暂停（A1 一键暂停）：true 时动作立即 failed/unavailable。 */
    @Volatile
    var isPaused: () -> Boolean = { false }

    /** 是否有 UI 侧处理器可承接「无原生执行器」的动作（无则立即 failed/unavailable）。 */
    @Volatile
    var hasUiHandler: () -> Boolean = { false }

    /** UI 委托分发（已在主线程语义由调用方保证；本类只在 scheduler 线程调用它）。 */
    @Volatile
    var uiDispatch: (Map<String, Any?>) -> Unit = {}

    // ---- 执行器注册表（capability → handler；注册/注销可跨线程） ----

    private val handlers = ConcurrentHashMap<String, ActionHandler>()

    fun registerHandler(capability: String, handler: ActionHandler) {
        require(capability in CapabilitiesV2.ACTION_SUBSET) {
            "能力不在 §5.3 动作子集: $capability"
        }
        handlers[capability] = handler
    }

    fun unregisterHandler(capability: String) {
        handlers.remove(capability)
    }

    // ---- 在飞动作（仅本进程；跨重启的在飞记录视为「未终结」→ unknown） ----

    private data class InFlight(
        val actionId: String,
        val startedAt: Long,
        val timeoutTask: ScheduledFuture<*>,
    )

    private val inFlight = HashMap<String, InFlight>() // 仅 scheduler 线程访问

    // ------------------------------------------------------------- 入口

    /**
     * 处理一帧 action.request（WssClientV2 在 scheduler 线程调用）。
     * [frameId] 为信封 id（结构非法时用于 error.invalid_message 的 ref_id）。
     */
    fun handleRequest(frameId: String, payload: Map<String, Any?>) {
        val request = parse(payload)
        if (request == null) {
            // 无法关联 action_id 的结构非法帧：回协议级错误（§4），不产生回执。
            val refActionId = payload["action_id"] as? String
            if (refActionId != null && IdsV2.ACTION_ID.matches(refActionId)) {
                // action_id 合法但其余字段非法：回 failed/invalid_message，core 可关联。
                val now = clock()
                sendReceipt(
                    actionId = refActionId, taskId = payload["task_id"] as? String,
                    status = ReceiptStatus.FAILED, errorCode = ErrorCodeV2.INVALID_MESSAGE,
                    startedAt = null, completedAt = now, result = null, providerRef = null,
                )
            } else {
                sendProtocolError(frameId, ErrorCodeV2.INVALID_MESSAGE, "malformed action.request")
            }
            return
        }
        dispatch(request)
    }

    /** 结构解析：失败返回 null（由调用方决定回执形态）。 */
    internal fun parse(payload: Map<String, Any?>): ActionRequestV2? {
        val actionId = payload["action_id"] as? String ?: return null
        if (!IdsV2.ACTION_ID.matches(actionId)) return null
        val capability = payload["capability"] as? String ?: return null
        val idemKey = payload["idempotency_key"] as? String ?: return null
        if (!IdsV2.IDEMPOTENCY_KEY.matches(idemKey)) return null
        val timeoutMs = payloadLongV2(payload, "timeout_ms") ?: return null
        if (timeoutMs < 100 || timeoutMs > 600000) return null
        val expiresAt = payloadLongV2(payload, "expires_at") ?: return null
        val taskId = payload["task_id"] as? String
        if (taskId != null && !IdsV2.TASK_ID.matches(taskId)) return null
        val runId = payload["run_id"] as? String
        if (runId != null && !IdsV2.RUN_ID.matches(runId)) return null
        val observationId = payload["observation_id"] as? String
        if (observationId != null && !IdsV2.OBSERVATION_ID.matches(observationId)) return null
        @Suppress("UNCHECKED_CAST")
        val arguments = payload["arguments"] as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val scope = (payload["authorized_scope"] as? List<*>)?.filterIsInstance<String>()
            ?: return null
        if (scope.isEmpty()) return null
        // §8：arguments 引用 element_id 时 observation_id 必填。
        if (arguments.containsKey("element_id") && observationId == null) return null
        return ActionRequestV2(
            actionId = actionId,
            taskId = taskId,
            runId = runId,
            capability = capability,
            arguments = arguments,
            observationId = observationId,
            timeoutMs = timeoutMs,
            expiresAt = expiresAt,
            idempotencyKey = idemKey,
            authorizedScope = scope,
            approvalRef = payload["approval_ref"] as? String,
            correlationId = payload["correlation_id"] as? String,
            raw = payload,
        )
    }

    private fun dispatch(request: ActionRequestV2) {
        val now = clock()
        val idemKey = request.idempotencyKey

        // ---- 幂等（§7.1/§16） ----
        store.get(idemKey)?.let { existing ->
            when {
                // 同会话在飞：重发 received（执行中的副作用不会被打断/重复）。
                inFlight.containsKey(idemKey) -> {
                    Log.i(TAG, "重复 action.request（在飞，key=$idemKey），重发 received")
                    sendReceipt(
                        actionId = request.actionId, taskId = request.taskId,
                        status = ReceiptStatus.RECEIVED, errorCode = null,
                        startedAt = null, completedAt = null, result = null, providerRef = null,
                    )
                }
                // 已终结：回放已记录 action_id 的真实终态（绝不重复执行）。
                existing.terminalState != null -> {
                    Log.i(TAG, "重复 action.request（已终结=${existing.terminalState}），回放终态回执")
                    val replay = existing.terminalReceipt
                    if (replay != null) {
                        sendUplink(replay)
                    } else {
                        // 回放内容损坏的兜底：按记录的终态重建最小回执（recorded action_id）。
                        sendReceipt(
                            actionId = existing.actionId, taskId = existing.taskId,
                            status = existing.terminalState, errorCode = null,
                            startedAt = null, completedAt = now,
                            result = null, providerRef = null,
                        )
                    }
                }
                // 跨重启发现「已记录未终结」：副作用结果不明 → unknown（§16 绝不重放）。
                else -> {
                    Log.w(TAG, "跨重启发现未终结动作（key=$idemKey），终结为 unknown 待人工核验")
                    settle(existing, ActionOutcome(status = ReceiptStatus.UNKNOWN))
                }
            }
            return
        }

        store.recordIfAbsent(idemKey, request.actionId, request.taskId, request.capability, nowMs = now)
        val record = store.get(idemKey)!!

        fun failTerminal(code: String) {
            settle(record, ActionOutcome(status = ReceiptStatus.FAILED, errorCode = code))
        }

        // ---- 校验链（§5.3/§7.1/§16） ----
        if (isPaused()) { failTerminal(ErrorCodeV2.UNAVAILABLE); return }
        if (now >= request.expiresAt) { failTerminal(ErrorCodeV2.EXPIRED); return } // 过期不补执行
        if (request.capability !in CapabilitiesV2.ACTION_SUBSET) {
            failTerminal(ErrorCodeV2.CAPABILITY_UNKNOWN); return
        }
        if (request.capability !in request.authorizedScope) {
            failTerminal(ErrorCodeV2.SCOPE_DENIED); return
        }
        if (request.capability !in acceptedCapabilitiesProvider()) {
            failTerminal(ErrorCodeV2.SCOPE_DENIED); return
        }

        val handler = handlers[request.capability]
        if (handler == null && !hasUiHandler()) {
            // §16：无执行器且无 UI 委托时立即 failed/unavailable，不悬挂 received。
            failTerminal(ErrorCodeV2.UNAVAILABLE)
            return
        }

        // ---- 接收并在飞 ----
        sendReceipt(
            actionId = request.actionId, taskId = request.taskId,
            status = ReceiptStatus.RECEIVED, errorCode = null,
            startedAt = null, completedAt = null, result = null, providerRef = null,
        )
        val startedAt = clock()
        val timeoutTask = scheduler.schedule({
            onTimeout(idemKey)
        }, request.timeoutMs, TimeUnit.MILLISECONDS)
        inFlight[idemKey] = InFlight(request.actionId, startedAt, timeoutTask)

        if (handler == null) {
            // UI 委托路径：终态由业务层经 completeFromBusiness 补发（超时仍由本类兜底）。
            try {
                uiDispatch(request.raw)
            } catch (t: Throwable) {
                Log.e(TAG, "UI 委托分发异常 capability=${request.capability}", t)
                settleInFlight(idemKey, ActionOutcome(ReceiptStatus.FAILED, ErrorCodeV2.INTERNAL))
            }
            return
        }
        val reply: (ActionOutcome) -> Unit = { outcome ->
            scheduler.execute { settleInFlight(idemKey, outcome) }
        }
        try {
            handler.execute(request, reply)
        } catch (t: Throwable) {
            Log.e(TAG, "action 执行器异常 capability=${request.capability} action_id=${request.actionId}", t)
            reply(ActionOutcome(ReceiptStatus.FAILED, ErrorCodeV2.INTERNAL))
        }
    }

    /** 超时兜底：超过 timeout_ms 未终结 → timeout 终态（first-wins，迟到的执行结果丢弃）。 */
    private fun onTimeout(idemKey: String) {
        inFlight.remove(idemKey)
        val record = store.get(idemKey) ?: return
        if (record.terminalState != null) return // 已终结（竞态：结果与超时同时到达）
        Log.w(TAG, "action 超时未终结（key=$idemKey），回 timeout 终态")
        settle(record, ActionOutcome(status = ReceiptStatus.TIMEOUT))
    }

    /**
     * 在飞动作终结（执行器回调路径）。first terminal wins：已终结时丢弃并记日志
     * （常见于执行器回复晚于超时兜底）。
     */
    private fun settleInFlight(idemKey: String, outcome: ActionOutcome) {
        val inflight = inFlight.remove(idemKey)
        val record = store.get(idemKey)
        if (record == null) {
            // 记录缺失（不应发生：在飞必已落库）——无法关联 action_id，只能记日志。
            Log.w(TAG, "在飞记录缺失（key=$idemKey），丢弃 outcome=${outcome.status}")
            return
        }
        inflight?.timeoutTask?.cancel(false)
        val settled = settle(record, outcome, startedAt = inflight?.startedAt)
        if (!settled) {
            Log.w(TAG, "终态已落库（first terminal wins），丢弃迟到结果 key=$idemKey outcome=${outcome.status}")
        }
    }

    /**
     * 落终态并发送回执（first terminal wins；返回是否由本次调用终结）。
     * 回执携带 recorded action_id（§7.1 重复键回执已记录 action_id 的真实状态）。
     */
    private fun settle(
        record: ActionDedupStoreV2.Record,
        outcome: ActionOutcome,
        startedAt: Long? = null,
    ): Boolean {
        val now = clock()
        val receipt = buildReceiptPayload(
            actionId = record.actionId, taskId = record.taskId, outcome = outcome,
            startedAt = startedAt, completedAt = now,
        )
        if (!store.setTerminal(record.idemKey, outcome.status, receipt, nowMs = now)) {
            return false
        }
        sendUplink(receipt)
        return true
    }

    /**
     * 业务层（UI/Dart）终态入口：按 action_id 反查幂等键，first-wins 落库并回执。
     * 查无记录（如重启后 UI 重放）时仍按给定 action_id 尽力回执（core 自行关联）。
     */
    fun completeFromBusiness(
        actionId: String,
        status: ReceiptStatus,
        errorCode: String? = null,
        resultSummary: String? = null,
        outputRef: String? = null,
        verificationStatus: String? = null,
        verificationSummary: String? = null,
        providerRef: String? = null,
    ) {
        require(status.terminal) { "completeFromBusiness 只接受终态: $status" }
        scheduler.execute {
            val record = store.getByActionId(actionId)
            if (record == null) {
                Log.w(TAG, "业务层终态查无幂等记录 action_id=$actionId，尽力回执")
                sendReceipt(
                    actionId = actionId, taskId = null, status = status, errorCode = errorCode,
                    startedAt = null, completedAt = clock(),
                    result = ActionOutcome(
                        status, errorCode, resultSummary, outputRef,
                        verificationStatus, verificationSummary, providerRef,
                    ).resultPayload(),
                    providerRef = providerRef,
                )
                return@execute
            }
            inFlight.remove(record.idemKey)?.timeoutTask?.cancel(false)
            settle(
                record,
                ActionOutcome(
                    status, errorCode, resultSummary, outputRef,
                    verificationStatus, verificationSummary, providerRef,
                ),
            )
        }
    }

    /** 取消在飞动作（device.stop / 紧急停止预留入口）：终结为 cancelled。 */
    fun cancel(idemKey: String) {
        scheduler.execute {
            val record = store.get(idemKey) ?: return@execute
            if (record.terminalState != null) return@execute
            inFlight.remove(idemKey)?.timeoutTask?.cancel(false)
            settle(record, ActionOutcome(status = ReceiptStatus.CANCELLED, errorCode = ErrorCodeV2.CANCELLED))
        }
    }

    /** 连接断开时调用：清空在飞表（不终结——重连后重复下发按「跨重启未终结」规则处理）。 */
    fun onConnectionLost() {
        for ((_, inflight) in inFlight) inflight.timeoutTask.cancel(false)
        inFlight.clear()
    }

    /** 撤销设备时调用（§16：幂等表清空）。 */
    fun onDeviceRevoked() {
        scheduler.execute {
            inFlight.clear()
            store.clear()
        }
    }

    // ------------------------------------------------------------- 回执组装

    /** 组装完整 action.receipt payload（§7.2/schema：含可选 task_id 回显与时间戳）。 */
    internal fun buildReceiptPayload(
        actionId: String,
        taskId: String?,
        outcome: ActionOutcome,
        startedAt: Long?,
        completedAt: Long?,
    ): Map<String, Any?> {
        val payload = LinkedHashMap<String, Any?>()
        payload["action_id"] = actionId
        taskId?.let { payload["task_id"] = it }
        payload["status"] = outcome.status.wire
        outcome.resultPayload()?.let { payload["result"] = it }
        if (outcome.errorCode != null &&
            (outcome.status == ReceiptStatus.FAILED ||
                outcome.status == ReceiptStatus.TIMEOUT ||
                outcome.status == ReceiptStatus.CANCELLED)
        ) {
            payload["error_code"] = outcome.errorCode
        }
        outcome.providerRef?.let { payload["provider_ref"] = it }
        startedAt?.let { payload["started_at"] = it }
        completedAt?.let { payload["completed_at"] = it }
        return payload
    }

    private fun sendReceipt(
        actionId: String,
        taskId: String?,
        status: ReceiptStatus,
        errorCode: String?,
        startedAt: Long?,
        completedAt: Long?,
        result: Map<String, Any?>?,
        providerRef: String?,
    ) {
        val payload = LinkedHashMap<String, Any?>()
        payload["action_id"] = actionId
        taskId?.let { payload["task_id"] = it }
        payload["status"] = status.wire
        result?.let { payload["result"] = it }
        if (errorCode != null && status != ReceiptStatus.RECEIVED && status != ReceiptStatus.SUCCEEDED) {
            payload["error_code"] = errorCode
        }
        providerRef?.let { payload["provider_ref"] = it }
        startedAt?.let { payload["started_at"] = it }
        completedAt?.let { payload["completed_at"] = it }
        sendUplink(payload)
    }

    /** 协议级 error 上行口（结构非法且无法关联 action_id 时）。由 WssClientV2 注入覆盖。 */
    @Volatile
    var sendProtocolError: (refId: String, code: String, message: String) -> Unit = { _, _, _ -> }

    companion object {
        private const val TAG = "WhaleReceiptsV2"
    }
}
