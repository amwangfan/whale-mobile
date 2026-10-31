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
 * call.invite（contracts/v1 §4，schema payload 仅 session_id/media_params/expires_at）：
 *   媒体参数为 V1 固定值（pcm_s16le/16000/mono），不符即忽略；过期即忽略（§6）。
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
    const val DEFAULT_BRIDGE_HOST = "127.0.0.1"
    const val DEFAULT_BRIDGE_PORT = 18799

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
        // 超长截断不拒绝（与通知模块一致）；空名回落默认显示名。
        val name = ((args["name"] as? String)?.trim().orEmpty()
            .ifEmpty { "AI 助手" }).take(MAX_NAME_LEN)
        val host = (args["bridge_host"] as? String)?.trim().orEmpty()
            .ifEmpty { defaultHost }
        if (host.length > MAX_HOST_LEN || host.any { it.isWhitespace() || it < '!' }) {
            return RingDecision.Reject("invalid_message")
        }
        val port = (args["bridge_port"] as? Number)?.toInt()
            ?: (args["bridge_port"] as? String)?.trim()?.toIntOrNull()
            ?: defaultPort
        if (port !in 1..65535) return RingDecision.Reject("invalid_message")
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
    val SESSION_ID_REGEX = Regex("^[0-9A-Za-z-]{16,64}$")
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
