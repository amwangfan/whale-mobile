package com.wangfanlab.whale.modules.voip

/**
 * VoIP 模块纯逻辑层（phone.call.ring + call.* 信令）：不依赖任何 Android 类，
 * 可在本地 JUnit（JVM）下直接测试。Android 胶水见 VoipModule.kt。
 *
 * 校验链顺序（phone.call.ring，与 NotificationDecider 对齐）：
 *   0. 全局暂停（whale_modules/all_paused → unavailable）
 *   1. 模块开关（whale_modules/voip_enabled，宿主默认 false → unavailable）
 *   2. 运行时权限（RECORD_AUDIO + MANAGE_OWN_CALLS → permission_missing）
 *   3. 参数白名单（number/name/bridge_host/bridge_port）
 *
 * call.invite（contracts/v1 §4，schema payload 仅 session_id/media_params/expires_at/name?）：
 *   媒体参数为 V1 固定值（pcm_s16le/16000/mono），不符即忽略；过期即忽略（§6）。
 *
 * call.* 上行信令（呼出加回·云端契约定稿）：call.answer / call.state / call.hangup
 *   走独立信封（[CallSignalBuilder] 组装、[CallSessionFlow] 决策），与 experience.event
 *   感知轨分离（防双轨：会话态只走信封轨）。振铃窗 60s（契约），挂断即撤销会话。
 *   媒体恒 pcm_s16le/16k/mono/20ms；桥坐标端侧自带（契约 media_endpoint=(b)），
 *   会话靠媒体首帧 AUTH/session_id 区分。
 */

/** phone.call.ring 校验通过后的执行规格。 */
data class RingSpec(
    val number: String,
    val name: String,
    val bridgeHost: String,
    val bridgePort: Int,
)

/** ring 校验/决策结果：执行，或带 error_code 拒绝（终态 failed）。 */
sealed interface RingDecision {
    data class Execute(val spec: RingSpec) : RingDecision

    /** errorCode 对应 action.receipt payload.error_code（§4 错误码）。 */
    data class Reject(val errorCode: String) : RingDecision
}

/** 参数白名单（phone.call.ring args）。 */
object CallValidator {
    /** 号码白名单：可选前导 +，数字/服务码 * #；总长 1..24（E.164 上限 15 留余量）。 */
    val NUMBER_REGEX = Regex("^\\+?[0-9*#]{1,23}$")
    const val MAX_NAME_LEN = 40
    const val MAX_HOST_LEN = 255

    /** 占位默认（Tob 裁决 c）：空 host + 端口 0 = 未配置；真实坐标只在设备 BridgePrefs。 */
    const val DEFAULT_BRIDGE_HOST = ""
    const val DEFAULT_BRIDGE_PORT = 0

    fun validateRingArgs(args: Map<*, *>?): RingDecision =
        validateRingArgs(args, DEFAULT_BRIDGE_HOST, DEFAULT_BRIDGE_PORT)

    /** 宿主形态：桥默认值由端侧 BridgePrefs 注入（模块卡可改），不再是硬编码回环。 */
    fun validateRingArgs(
        args: Map<*, *>?,
        defaultHost: String,
        defaultPort: Int,
    ): RingDecision {
        val number = (args?.get("number") as? String)?.trim()
        if (number.isNullOrEmpty() || !NUMBER_REGEX.matches(number)) {
            return RingDecision.Reject("invalid_message")
        }
        // 超长截断不拒绝（与通知模块一致）；空名不再回落默认显示名（2026-11-21 主人令：
        // 协议支持真空名——来电只显号码、不显名称；占位回落已废）。
        val name = ((args["name"] as? String)?.trim().orEmpty()).take(MAX_NAME_LEN)
        val host = (args["bridge_host"] as? String)?.trim().orEmpty()
            .ifEmpty { defaultHost }
        if (host.length > MAX_HOST_LEN || host.any { it.isWhitespace() || it < '!' }) {
            return RingDecision.Reject("invalid_message")
        }
        // 端口语义（占位符化）：bridge_port 缺席 → 回落默认（允许占位 0=未配置，
        // 媒体在接听时 fail-closed）；显式提供则必须 1..65535（显式 0 拒绝）。
        val portProvided = args.containsKey("bridge_port")
        val port = (args["bridge_port"] as? Number)?.toInt()
            ?: (args["bridge_port"] as? String)?.trim()?.toIntOrNull()
            ?: defaultPort
        if (portProvided && port !in 1..65535) return RingDecision.Reject("invalid_message")
        if (!portProvided && port != 0 && port !in 1..65535) {
            return RingDecision.Reject("invalid_message")
        }
        return RingDecision.Execute(RingSpec(number, name, host, port))
    }
}

/** call.invite 消费决策。 */
sealed interface InviteDecision {
    /** 校验通过：响铃进入会话（bridge 参数走端侧配置，schema 不携带）。 */
    data class Accept(
        val sessionId: String,
        val expiresAt: Long,
        val frameMs: Int?,
    ) : InviteDecision

    /** 忽略原因：malformed / unsupported_media / expired（§6 过期不执行）。 */
    data class Ignore(val reason: String) : InviteDecision
}

/** call.invite payload 校验（contracts/v1/messages/call.invite.schema.json）。 */
object CallInviteLogic {
    /** 契约 session_id：26 字符 ULID（Crockford base32，无 I/L/O/U）。 */
    val SESSION_ID_REGEX = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
    val VALID_FRAME_MS = setOf(20, 40, 60)

    fun validate(payload: Map<*, *>?, nowMs: Long): InviteDecision {
        val sessionId = (payload?.get("session_id") as? String)?.trim()
        if (sessionId == null || !SESSION_ID_REGEX.matches(sessionId)) {
            return InviteDecision.Ignore("malformed")
        }
        val expiresAt = (payload["expires_at"] as? Number)?.toLong()
            ?: return InviteDecision.Ignore("malformed")
        if (expiresAt <= nowMs) return InviteDecision.Ignore("expired")

        // media_params 为 V1 固定值（§7）；不符 = 无法协商，忽略。
        val media = payload["media_params"] as? Map<*, *>
            ?: return InviteDecision.Ignore("malformed")
        if (media["codec"] != "pcm_s16le") return InviteDecision.Ignore("unsupported_media")
        if ((media["sample_rate"] as? Number)?.toInt() != 16000) {
            return InviteDecision.Ignore("unsupported_media")
        }
        if ((media["channels"] as? Number)?.toInt() != 1) {
            return InviteDecision.Ignore("unsupported_media")
        }
        val frameMs = (media["frame_ms"] as? Number)?.toInt()
        if (frameMs != null && frameMs !in VALID_FRAME_MS) {
            return InviteDecision.Ignore("unsupported_media")
        }
        return InviteDecision.Accept(sessionId, expiresAt, frameMs)
    }

    /**
     * name 穿透门（与 legacy SignalService.textOr 同口径：core 恒传含空串 +
     * hub 尊重空串 + 端侧穿透）：字段存在（含显式空串=匿名）→ trim+截断后原样；
     * 字段缺席/null → null（调用方回落默认）。
     */
    fun displayName(payload: Map<*, *>?): String? {
        if (payload == null || !payload.containsKey("name")) return null
        val v = payload["name"] as? String ?: return null
        return v.trim().take(CallValidator.MAX_NAME_LEN)
    }
}

// ------------------------------------------------------------- 来电 UI 匿名化（主人令 2026-11-26，B 反转）

/**
 * 来电显示策略：来电 UI 永不显真实名——名称槽恒为通用标签 [UI_NAME_LABEL]
 * （空名渲染形态由我定；不显任何真实名，即使 invite/ring payload 带 name）。
 * payload 原文名仅留内部事件轨（FileLog/experience.event）。号码原样透传
 * （含 "0"）；缺席时显 "0" 或全空由 [SHOW_ZERO_WHEN_NUMBER_ABSENT] 一行可切
 * （「全空 vs 显 0」等主人确认）。
 */
object IncomingDisplayPolicy {
    /** 来电 UI 名称槽通用标签（非真实名）。 */
    const val UI_NAME_LABEL = "来电"

    /** 号码缺席时：true=显 "0"；false=全空 ""。一行可切。 */
    const val SHOW_ZERO_WHEN_NUMBER_ABSENT = true

    /** 号码：trim 后原样透传；缺席按开关回落（显示层不拒呼、不发明非 0 号码）。 */
    @JvmStatic
    @JvmOverloads
    fun sanitizeNumber(
        number: String?,
        showZeroWhenAbsent: Boolean = SHOW_ZERO_WHEN_NUMBER_ABSENT,
    ): String {
        val n = number?.trim().orEmpty()
        return if (n.isNotEmpty()) n else if (showZeroWhenAbsent) "0" else ""
    }

    /** 名称：来电 UI 恒为通用标签（匿名化）。入参仅为调用点语义显化。 */
    @JvmStatic
    fun displayNameForUi(name: String?): String = UI_NAME_LABEL
}

// ------------------------------------------------------------- call.* 上行信令（契约 §6）

/** 上行呼叫信令（type ∈ call.answer/call.state/call.hangup；payload 已按契约组装）。 */
data class CallSignal(val type: String, val payload: Map<String, Any?>)

/** 契约载荷组装器：媒体恒 pcm_s16le/16k/mono/20ms（契约钦定，不协商）。 */
object CallSignalBuilder {
    const val TYPE_ANSWER = "call.answer"
    const val TYPE_STATE = "call.state"
    const val TYPE_HANGUP = "call.hangup"

    /** 契约固定媒体参数（answer 回显；与实际 TX 帧 20ms=640B 一致）。 */
    val MEDIA_PARAMS: Map<String, Any?> = linkedMapOf(
        "codec" to "pcm_s16le",
        "sample_rate" to 16000,
        "channels" to 1,
        "frame_ms" to 20,
    )

    val VALID_STATES = setOf("ringing", "active", "held", "ended", "failed")
    val VALID_HANGUP_REASONS = setOf("normal", "timeout", "rejected", "error")

    fun answer(sessionId: String, expiresAt: Long, accepted: Boolean): CallSignal {
        require(CallInviteLogic.SESSION_ID_REGEX.matches(sessionId)) { "非法 session_id" }
        return CallSignal(
            TYPE_ANSWER,
            linkedMapOf(
                "session_id" to sessionId,
                "media_params" to MEDIA_PARAMS,
                "expires_at" to expiresAt,
                "accepted" to accepted,
            ),
        )
    }

    fun state(sessionId: String, state: String): CallSignal {
        require(CallInviteLogic.SESSION_ID_REGEX.matches(sessionId)) { "非法 session_id" }
        require(state in VALID_STATES) { "非法 call.state: $state" }
        return CallSignal(TYPE_STATE, linkedMapOf("session_id" to sessionId, "state" to state))
    }

    fun hangup(sessionId: String, reason: String): CallSignal {
        require(CallInviteLogic.SESSION_ID_REGEX.matches(sessionId)) { "非法 session_id" }
        require(reason in VALID_HANGUP_REASONS) { "非法 hangup reason: $reason" }
        return CallSignal(TYPE_HANGUP, linkedMapOf("session_id" to sessionId, "reason" to reason))
    }
}

/**
 * 契约会话信令状态机（call.invite 绑定的会话；无会话的 action 响铃不走此路）。
 * RINGING → ACTIVE → ENDED；END 幂等（挂断即撤销会话，此后产出恒空，宿主随即丢引用）。
 * - 远端 hangup 下行只回 state=ended，不回声 hangup（防回环）。
 * - 呼出会话（autoAnswer：本端请求、云端回呼）抑制 ringing 上行——对用户无振铃语义。
 * - RINGING 中本地结束按拒接语义（answer accepted=false），与 tracker 既有语义对齐。
 */
class CallSessionFlow(
    val sessionId: String,
    val expiresAt: Long,
    private val autoAnswer: Boolean,
) {
    private var active = false
    private var ended = false
    private var remoteEnded = false
    private var ringingEmitted = false

    /** Telecom 振铃回调：普通来电上行 state=ringing（幂等）；呼出会话抑制。 */
    fun onRinging(): List<CallSignal> {
        if (ended || ringingEmitted || autoAnswer) return emptyList()
        ringingEmitted = true
        return listOf(CallSignalBuilder.state(sessionId, "ringing"))
    }

    /** 接听（用户接听或呼出会话自动接听）：answer accepted=true → state=active。 */
    fun onAnswered(): List<CallSignal> {
        if (ended || active) return emptyList()
        active = true
        return listOf(
            CallSignalBuilder.answer(sessionId, expiresAt, accepted = true),
            CallSignalBuilder.state(sessionId, "active"),
        )
    }

    /** 用户拒接（RINGING 阶段）：answer accepted=false → state=ended；不发 hangup。 */
    fun onRejected(): List<CallSignal> {
        if (ended || active) return emptyList()
        ended = true
        return listOf(
            CallSignalBuilder.answer(sessionId, expiresAt, accepted = false),
            CallSignalBuilder.state(sessionId, "ended"),
        )
    }

    /** 下行 call.hangup：仅标记远端结束；随后的本地 ended 只回 state=ended。 */
    fun onRemoteHangup() {
        remoteEnded = true
    }

    /**
     * 本地结束（挂断键/远端挂断后的本地清理）。ACTIVE 中本地挂断发 hangup normal；
     * RINGING 中本地结束按拒接；远端已标记则只回 state=ended。幂等。
     */
    fun onLocalEnded(): List<CallSignal> {
        if (ended) return emptyList()
        ended = true
        if (remoteEnded) return listOf(CallSignalBuilder.state(sessionId, "ended"))
        return if (active) {
            listOf(
                CallSignalBuilder.hangup(sessionId, "normal"),
                CallSignalBuilder.state(sessionId, "ended"),
            )
        } else {
            listOf(
                CallSignalBuilder.answer(sessionId, expiresAt, accepted = false),
                CallSignalBuilder.state(sessionId, "ended"),
            )
        }
    }

    /** 振铃窗到期仍未接听：hangup timeout + state=failed（契约：振铃窗 60s）。 */
    fun onRingTimeout(): List<CallSignal> {
        if (ended || active) return emptyList()
        ended = true
        return listOf(
            CallSignalBuilder.hangup(sessionId, "timeout"),
            CallSignalBuilder.state(sessionId, "failed"),
        )
    }

    /** 通话错误（媒体桥失败/认证被拒/响铃执行异常）：hangup error + state=failed。幂等。 */
    fun onError(): List<CallSignal> {
        if (ended) return emptyList()
        ended = true
        return listOf(
            CallSignalBuilder.hangup(sessionId, "error"),
            CallSignalBuilder.state(sessionId, "failed"),
        )
    }

    companion object {
        /** 契约振铃窗（毫秒）。 */
        const val RING_WINDOW_MS = 60_000L

        /** 呼出请求等待云端回呼 invite 的有效窗（契约无 corr_id，按时间窗相关）。 */
        const val OUTGOING_PENDING_WINDOW_MS = 60_000L

        /** 有效振铃窗：min(60s, expires_at 剩余)，下限 1s 防即刻自爆。 */
        fun ringWindowMs(expiresAt: Long, nowMs: Long): Long =
            minOf(RING_WINDOW_MS, expiresAt - nowMs).coerceAtLeast(1_000L)
    }
}

/** 上行感知事件（经 WssClient.sendSensorEvent 发出）。 */
data class CallEvent(
    val kind: String, // call.state / call.answered / call.ended（pattern ^[a-z0-9_.]+$）
    val summary: String, // 脱敏摘要（号码仅留尾号）
    val privacyClass: String = "owner_private",
)

/**
 * 通话会话事件跟踪：FakeConnectionService 状态回调 → sensor.event 序列。
 * 状态机 ringing → active → ended；ended 幂等（二次回调不再出事件）。
 */
class CallSessionTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var sessionId: String? = null
    private var ringingAtMs: Long = 0
    private var answeredAtMs: Long? = null
    private var ended = false

    /** 关联最近一次 call.invite 的 session_id（action.request 触发的响铃无会话）。 */
    fun bindSession(id: String?) {
        sessionId = id
    }

    fun onRinging(number: String, name: String): CallEvent? {
        ringingAtMs = clockMs()
        answeredAtMs = null
        ended = false
        return CallEvent(
            kind = "call.state",
            summary = "state=ringing${sessionSuffix()} name=${name.take(24)} number=${maskNumber(number)}",
        )
    }

    fun onActive(): CallEvent? {
        if (ended) return null
        if (answeredAtMs == null) answeredAtMs = clockMs()
        return CallEvent(
            kind = "call.answered",
            summary = "state=active${sessionSuffix()}",
        )
    }

    /** reason: local / remote / rejected（FakeConnectionService DisconnectCause 映射）。 */
    fun onEnded(reason: String): CallEvent? {
        if (ended) return null
        ended = true
        val answered = answeredAtMs
        val durationSec = if (answered != null) {
            ((clockMs() - answered).coerceAtLeast(0)) / 1000
        } else {
            0L
        }
        val effectiveReason = if (answered == null && reason == "local") "rejected" else reason
        return CallEvent(
            kind = "call.ended",
            summary = "state=ended${sessionSuffix()} duration_sec=$durationSec reason=$effectiveReason",
        )
    }

    private fun sessionSuffix(): String =
        sessionId?.let { " session=$it" } ?: ""

    companion object {
        /** 脱敏：仅保留尾 4 位，其余打码（sensor.event summary 要求脱敏）。 */
        fun maskNumber(number: String): String {
            val digits = number.filter { it.isDigit() }
            return if (digits.length <= 4) "***" else "***" + digits.takeLast(4)
        }
    }
}

/**
 * 校验链编排（与 NotificationDecider 同构）：全局暂停 → 模块开关 → 权限 → 参数白名单。
 * 全部注入为纯函数，JVM 可测；Android 取值在 VoipModule 注入。
 */
class RingDecider(
    private val moduleEnabled: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val allPaused: () -> Boolean = { false },
    private val defaultBridgeHost: () -> String = { CallValidator.DEFAULT_BRIDGE_HOST },
    private val defaultBridgePort: () -> Int = { CallValidator.DEFAULT_BRIDGE_PORT },
) {
    fun onRequest(args: Map<*, *>?): RingDecision {
        if (allPaused()) return RingDecision.Reject("unavailable")
        if (!moduleEnabled()) return RingDecision.Reject("unavailable")
        if (!permissionGranted()) return RingDecision.Reject("permission_missing")
        return CallValidator.validateRingArgs(
            args, defaultBridgeHost(), defaultBridgePort())
    }
}
