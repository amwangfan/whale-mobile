package com.wangfanlab.whale.transport.v2

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.SecureRandom

/**
 * contracts/v2 协议基础设施：信封编解码、ULID、带前缀 ID、能力枚举、回执状态机、错误码。
 *
 * 本文件为纯 JVM 实现（仅依赖 Gson），不引用 android.*，可在本地单元测试运行。
 * 协议事实源：contracts/v2/PROTOCOL.md 与 contracts/v2/messages 下各消息 schema；
 * 端侧不导入 core 代码，两侧各自校验。
 *
 * 注意：本文件中的能力枚举（[CapabilitiesV2.ALL]）与 contracts/v2 §5.1 逐字一致，
 * 错误码（[ErrorCodeV2.ALL]）与 §15 / error.schema.json / action.receipt.schema.json
 * 逐字一致——Kotlin 无法运行时读 schema，协议变更时必须人工同步本文件。
 */

const val PROTOCOL_VERSION_V2 = "2.0"
const val SUPPORTED_MAJOR_V2 = 2

/** §1：WSS 控制通道路径。 */
const val WSS_PATH_V2 = "/api/v2/devices/ws"

/** §1 约束：单帧 ≤ 256 KiB（UTF-8 字节数）。 */
const val MAX_FRAME_BYTES_V2 = 256 * 1024

const val DIR_UPLINK = "uplink"
const val DIR_DOWNLINK = "downlink"

/** ts/seq 的 schema 上限（2100-01-01 / 2^53-1）。 */
const val MAX_TS_MS = 4102444800000L
const val MAX_SEQ = 9007199254740991L

class EnvelopeExceptionV2(message: String) : Exception(message)

// ------------------------------------------------------------- ULID（§3）

/**
 * ULID 生成器：48bit Unix 毫秒 + 80bit 随机，Crockford Base32 编码为 26 字符。
 *
 * 自包含实现（不引第三方库），字符集 `0123456789ABCDEFGHJKMNPQRSTVWXYZ`
 * （不含 I/L/O/U），与 schema 正则 `^[0-9A-HJKMNP-TV-Z]{26}$` 一致。
 * 时钟与随机源可注入，便于确定性测试。
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10 // 48bit / 5bit → 10 字符（最高 2bit 恒 0）
    private const val RANDOM_CHARS = 16 // 80bit / 5bit → 16 字符
    private const val RANDOM_BYTES = 10

    /** ULID 时间戳上限（48bit 无符号最大值）。 */
    const val MAX_TIME_MS = 0xFFFFFFFFFFFFL

    val PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    private val defaultRandom = SecureRandom()

    /** 生成 ULID（当前时间 + 安全随机）。 */
    fun newUlid(): String = newUlid(System.currentTimeMillis(), defaultRandom)

    /** 生成 ULID（注入时钟/随机源；[random] 取 10 字节）。 */
    fun newUlid(timeMs: Long, random: SecureRandom): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return newUlid(timeMs, bytes)
    }

    /** 生成 ULID（完全确定性的时间 + 10 随机字节；测试向量用）。 */
    fun newUlid(timeMs: Long, randomBytes: ByteArray): String {
        require(randomBytes.size == RANDOM_BYTES) { "随机分量须为 10 字节（80bit）" }
        return encodeTime(timeMs) + encodeRandom(randomBytes)
    }

    /**
     * 时间分量编码：48bit 大端 → 10 字符（每字符 5bit，首字符仅低 3bit 有效）。
     * 标准测试向量：encodeTime(1469918176385) = "01ARYZ6S41"。
     */
    fun encodeTime(timeMs: Long): String {
        require(timeMs in 0..MAX_TIME_MS) { "ULID 时间戳越界: $timeMs" }
        val sb = StringBuilder(TIME_CHARS)
        var t = timeMs
        for (i in 0 until TIME_CHARS) {
            sb.append(ENCODING[(t and 0x1F).toInt()])
            t = t ushr 5
        }
        return sb.reverse().toString()
    }

    /** 随机分量编码：80bit（10 字节）大端位流 → 16 字符。 */
    fun encodeRandom(randomBytes: ByteArray): String {
        require(randomBytes.size == RANDOM_BYTES) { "随机分量须为 10 字节（80bit）" }
        val sb = StringBuilder(RANDOM_CHARS)
        var acc = 0
        var accBits = 0
        for (b in randomBytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            accBits += 8
            while (accBits >= 5) {
                accBits -= 5
                sb.append(ENCODING[(acc ushr accBits) and 0x1F])
            }
        }
        check(sb.length == RANDOM_CHARS)
        return sb.toString()
    }

    fun isValid(id: String): Boolean = PATTERN.matches(id)
}

// ------------------------------------------------------------- 带前缀实体 ID（§3）

/** V2 实体 ID 生成与校验：「前缀 + 26 字符 ULID」。 */
object IdsV2 {
    private const val ULID_BODY = "[0-9A-HJKMNP-TV-Z]{26}"

    val DEVICE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
    val TASK_ID = Regex("^task_$ULID_BODY$")
    val RUN_ID = Regex("^run_$ULID_BODY$")
    val ACTION_ID = Regex("^act_$ULID_BODY$")
    val EVENT_ID = Regex("^evt_$ULID_BODY$")
    val OBSERVATION_ID = Regex("^obs_$ULID_BODY$")
    val APPROVAL_ID = Regex("^aprv_$ULID_BODY$")
    val ATTACHMENT_ID = Regex("^att_$ULID_BODY$")
    val NOTIFICATION_ID = Regex("^notif_$ULID_BODY$")
    val IDEMPOTENCY_KEY = Regex("^[0-9A-Za-z:_-]{8,128}$")
    val CHALLENGE_HEX = Regex("^[0-9a-f]{64}$")
    val SIGNATURE_HEX = Regex("^[0-9a-f]{128}$")

    /** 消息 id / session_id / request_id / challenge_id 为裸 ULID。 */
    fun newMessageId(): String = Ulid.newUlid()
    fun newTaskId(): String = "task_" + Ulid.newUlid()
    fun newRunId(): String = "run_" + Ulid.newUlid()
    fun newActionId(): String = "act_" + Ulid.newUlid()
    fun newEventId(): String = "evt_" + Ulid.newUlid()
    fun newObservationId(): String = "obs_" + Ulid.newUlid()
    fun newApprovalId(): String = "aprv_" + Ulid.newUlid()
    fun newAttachmentId(): String = "att_" + Ulid.newUlid()
    fun newNotificationId(): String = "notif_" + Ulid.newUlid()
}

// ------------------------------------------------------------- 能力枚举（§5.1）

/**
 * V2 能力命名空间——**与 contracts/v2 §5.1 逐字一致，变更须同步**。
 * （Kotlin 无法运行时读 schema，本集合为 §5.1 的逐字抄录，共 32 项。）
 */
object CapabilitiesV2 {
    const val SCREEN_CONTEXT_READ = "screen.context.read"
    const val SCREEN_HISTORY_SEARCH = "screen.history.search"
    const val SCREEN_EVIDENCE_READ = "screen.evidence.read"
    const val SCREEN_LIVE_SUBSCRIBE = "screen.live.subscribe"
    const val PHONE_NOTIFICATION_POST = "phone.notification.post"
    const val PHONE_NOTIFICATION_READ = "phone.notification.read"
    const val PHONE_CALL_RING = "phone.call.ring"
    const val PHONE_CALL_AUDIO = "phone.call.audio"
    const val PHONE_DEVICE_SAFE_ACTION = "phone.device.safe_action"
    const val GLASSES_CARD_SHOW = "glasses.card.show"
    const val GLASSES_CARD_UPDATE = "glasses.card.update"
    const val GLASSES_CARD_DISMISS = "glasses.card.dismiss"
    const val DEVICE_OBSERVE = "device.observe"
    const val DEVICE_TAP = "device.tap"
    const val DEVICE_LONG_PRESS = "device.long_press"
    const val DEVICE_SWIPE = "device.swipe"
    const val DEVICE_INPUT_TEXT = "device.input_text"
    const val DEVICE_KEY = "device.key"
    const val DEVICE_WAIT = "device.wait"
    const val DEVICE_APP_LAUNCH = "device.app.launch"
    const val DEVICE_APP_LIST = "device.app.list"
    const val DEVICE_APP_INSTALL = "device.app.install"
    const val DEVICE_APP_UNINSTALL = "device.app.uninstall"
    const val DEVICE_SHELL = "device.shell"
    const val DEVICE_FILE_READ = "device.file.read"
    const val DEVICE_FILE_WRITE = "device.file.write"
    const val DEVICE_FILE_LIST = "device.file.list"
    const val DEVICE_LOGCAT = "device.logcat"
    const val DEVICE_STOP = "device.stop"
    const val DEVICE_OUTPUT_READ = "device.output.read"
    const val DEVICE_SCREEN_RECORD = "device.screen.record"
    const val DEVICE_STATUS = "device.status"

    /** §5.1 全量枚举（32 项，顺序与协议文档一致）。 */
    val ALL: Set<String> = linkedSetOf(
        SCREEN_CONTEXT_READ, SCREEN_HISTORY_SEARCH, SCREEN_EVIDENCE_READ,
        SCREEN_LIVE_SUBSCRIBE,
        PHONE_NOTIFICATION_POST, PHONE_NOTIFICATION_READ,
        PHONE_CALL_RING, PHONE_CALL_AUDIO, PHONE_DEVICE_SAFE_ACTION,
        GLASSES_CARD_SHOW, GLASSES_CARD_UPDATE, GLASSES_CARD_DISMISS,
        DEVICE_OBSERVE, DEVICE_TAP, DEVICE_LONG_PRESS,
        DEVICE_SWIPE, DEVICE_INPUT_TEXT, DEVICE_KEY,
        DEVICE_WAIT, DEVICE_APP_LAUNCH, DEVICE_APP_LIST,
        DEVICE_APP_INSTALL, DEVICE_APP_UNINSTALL,
        DEVICE_SHELL, DEVICE_FILE_READ, DEVICE_FILE_WRITE,
        DEVICE_FILE_LIST, DEVICE_LOGCAT, DEVICE_STOP,
        DEVICE_OUTPUT_READ, DEVICE_SCREEN_RECORD, DEVICE_STATUS,
    )

    /** §5.3：action.request.capability 可用子集 = 全部除 device.output.read（仅 sensor.query）。 */
    val ACTION_SUBSET: Set<String> = ALL - DEVICE_OUTPUT_READ

    /** §5.3：sensor.query.operation 可用子集（7 项）。 */
    val SENSOR_QUERY_SUBSET: Set<String> = linkedSetOf(
        SCREEN_CONTEXT_READ, SCREEN_HISTORY_SEARCH, SCREEN_EVIDENCE_READ,
        SCREEN_LIVE_SUBSCRIBE, DEVICE_OUTPUT_READ, DEVICE_APP_LIST, DEVICE_STATUS,
    )
}

// ------------------------------------------------------------- 回执状态机（§7.2）

/**
 * action.receipt 状态机：`received`（非终态）+ 五终态
 * （succeeded/failed/timeout/cancelled/unknown）。
 * 「first terminal wins」：终态一旦落库不可翻案（见 [ReceiptDispatcherV2]）。
 */
enum class ReceiptStatus(val wire: String, val terminal: Boolean) {
    RECEIVED("received", false),
    SUCCEEDED("succeeded", true),
    FAILED("failed", true),
    TIMEOUT("timeout", true),
    CANCELLED("cancelled", true),
    UNKNOWN("unknown", true),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun of(wire: String): ReceiptStatus? = byWire[wire]
    }
}

// ------------------------------------------------------------- 错误码（§15）

/**
 * V2 错误码单一事实源（§15，与 error.schema.json / action.receipt.schema.json
 * 枚举逐字一致，共 19 值；协议变更必须先改文档与 schema，再同步本文件）。
 */
object ErrorCodeV2 {
    const val PROTOCOL_VERSION = "protocol_version"
    const val AUTH_FAILED = "auth_failed"
    const val DEVICE_REVOKED = "device_revoked"
    const val SCOPE_DENIED = "scope_denied"
    const val CAPABILITY_UNKNOWN = "capability_unknown"
    const val EXPIRED = "expired"
    const val DUPLICATE = "duplicate"
    const val RATE_LIMITED = "rate_limited"
    const val UNAVAILABLE = "unavailable"
    const val PERMISSION_MISSING = "permission_missing"
    const val INVALID_MESSAGE = "invalid_message"
    const val INTERNAL = "internal"
    const val BACKGROUND_RESTRICTED = "background_restricted"
    const val TIMEOUT = "timeout"
    const val CANCELLED = "cancelled"
    const val STALE_OBSERVATION = "stale_observation"
    const val ELEMENT_NOT_FOUND = "element_not_found"
    const val APPROVAL_DENIED = "approval_denied"
    const val ESTOP_ACTIVE = "estop_active"

    val ALL: Set<String> = linkedSetOf(
        PROTOCOL_VERSION, AUTH_FAILED, DEVICE_REVOKED, SCOPE_DENIED,
        CAPABILITY_UNKNOWN, EXPIRED, DUPLICATE, RATE_LIMITED,
        UNAVAILABLE, PERMISSION_MISSING, INVALID_MESSAGE, INTERNAL,
        BACKGROUND_RESTRICTED, TIMEOUT, CANCELLED,
        STALE_OBSERVATION, ELEMENT_NOT_FOUND, APPROVAL_DENIED, ESTOP_ACTIVE,
    )
}

// ------------------------------------------------------------- 消息类型（§6）

/** V2 消息类型常量（envelope.schema.json 的 type 枚举）。 */
object MsgTypeV2 {
    const val AUTH_CHALLENGE = "auth.challenge"
    const val DEVICE_HELLO = "device.hello"
    const val DEVICE_HELLO_ACK = "device.hello_ack"
    const val DEVICE_HEARTBEAT = "device.heartbeat"
    const val EXPERIENCE_EVENT = "experience.event"
    const val EVENT_ACK = "event.ack"
    const val SENSOR_QUERY = "sensor.query"
    const val SENSOR_RESULT = "sensor.result"
    const val ACTION_REQUEST = "action.request"
    const val ACTION_RECEIPT = "action.receipt"
    const val DEVICE_CONFIRM_REQUEST = "device.confirm.request"
    const val DEVICE_CONFIRM_RESPONSE = "device.confirm.response"
    const val RUN_TRACE_EVENT = "run.trace.event"
    const val RECORDING_CONTROL = "recording.control"
    const val RECORDING_STATE = "recording.state"
    const val CALL_INVITE = "call.invite"
    const val CALL_ANSWER = "call.answer"
    const val CALL_HANGUP = "call.hangup"
    const val CALL_STATE = "call.state"
    const val ERROR = "error"
    const val NOTIFICATION_EVENT = "notification.event"
    const val NOTIFICATION_RECEIPT = "notification.receipt"
    const val NOTIFICATION_ACTION = "notification.action"
    const val NOTIFICATION_SYNC = "notification.sync"
    const val NOTIFICATION_REVOKE = "notification.revoke"
}

// ------------------------------------------------------------- 信封（§2）

/** V2 信封。payload 保持 Map 形态（逐消息语义校验由上层负责）。 */
data class EnvelopeV2(
    val v: String = PROTOCOL_VERSION_V2,
    val id: String,
    val type: String,
    val ts: Long,
    val seq: Long,
    val dir: String,
    val payload: Map<String, Any?>,
) {
    fun toJson(): String = EnvelopeCodecV2.encode(this)

    fun toJsonBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        /** 构造上行信封：v=2.0、id 为新生 ULID、dir=uplink。seq 必须来自本连接的 SeqCounter。 */
        fun uplink(type: String, seq: Long, payload: Map<String, Any?>, ts: Long): EnvelopeV2 =
            EnvelopeV2(
                v = PROTOCOL_VERSION_V2,
                id = Ulid.newUlid(),
                type = type,
                ts = ts,
                seq = seq,
                dir = DIR_UPLINK,
                payload = payload,
            )
    }
}

object EnvelopeCodecV2 {
    private val gson = Gson()
    private val V_PATTERN = Regex("^2\\.[0-9]+$")
    private val ANY_VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+$")
    private val INT_LITERAL = Regex("^-?[0-9]+$")

    fun encode(env: EnvelopeV2): String {
        // LinkedHashMap 保持 §2 字段顺序，便于人工抓包比对黄金示例。
        val root = LinkedHashMap<String, Any?>(8)
        root["v"] = env.v
        root["id"] = env.id
        root["type"] = env.type
        root["ts"] = env.ts
        root["seq"] = env.seq
        root["dir"] = env.dir
        root["payload"] = env.payload
        return gson.toJson(root)
    }

    /**
     * 解析并做信封级校验（逐消息 payload 校验由上层负责）。
     * - `v` 只要求匹配 `major.minor` 数字形态；**major 是否受支持由调用方判断**
     *   （未知 major 须回 error.protocol_version 后关闭，而非简单拒读，§4）。
     * - `id` 必须为 26 字符 ULID；未知 type 允许通过（前向兼容，§4 静默忽略）。
     */
    fun decode(json: String): EnvelopeV2 {
        val root: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw EnvelopeExceptionV2("frame is not a JSON object: ${e.message}")
        }
        val v = requiredString(root, "v")
        if (!ANY_VERSION_PATTERN.matches(v)) throw EnvelopeExceptionV2("bad v: $v")
        val id = requiredString(root, "id")
        if (!Ulid.isValid(id)) throw EnvelopeExceptionV2("bad id (not ULID)")
        val type = requiredString(root, "type")
        val ts = requiredLong(root, "ts")
        if (ts < 0 || ts > MAX_TS_MS) throw EnvelopeExceptionV2("ts out of range: $ts")
        val seq = requiredLong(root, "seq")
        if (seq < 0 || seq > MAX_SEQ) throw EnvelopeExceptionV2("seq out of range: $seq")
        val dir = requiredString(root, "dir")
        if (dir != DIR_UPLINK && dir != DIR_DOWNLINK) throw EnvelopeExceptionV2("bad dir: $dir")
        val payloadEl = root.get("payload") ?: throw EnvelopeExceptionV2("missing payload")
        if (!payloadEl.isJsonObject) throw EnvelopeExceptionV2("payload is not an object")
        @Suppress("UNCHECKED_CAST")
        val payload = elementToAny(payloadEl) as Map<String, Any?>
        return EnvelopeV2(v, id, type, ts, seq, dir, payload)
    }

    /** §2/§4：是否为受支持的 V2 版本（major == 2）。 */
    fun isSupportedVersion(v: String): Boolean = V_PATTERN.matches(v)

    /** 解析 "major.minor" 的 major；无法解析返回 null。 */
    fun majorOf(v: String): Int? = v.substringBefore('.').toIntOrNull()

    /** 解析 payload JSON 文本（离线队列重发用），数字保型：整数字面量 → Long，其余 → Double。 */
    fun payloadFromJson(json: String): Map<String, Any?> {
        val el = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw EnvelopeExceptionV2("payload is not valid JSON: ${e.message}")
        }
        if (!el.isJsonObject) throw EnvelopeExceptionV2("payload is not an object")
        @Suppress("UNCHECKED_CAST")
        return elementToAny(el) as Map<String, Any?>
    }

    /** 编码单个 payload（用于入队存储）。 */
    fun payloadToJson(payload: Map<String, Any?>): String = gson.toJson(payload)

    /** §1 单帧大小校验（UTF-8 字节）。超限抛 [EnvelopeExceptionV2]。 */
    fun checkFrameSize(json: String) {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size > MAX_FRAME_BYTES_V2) {
            throw EnvelopeExceptionV2("frame too large: $size > $MAX_FRAME_BYTES_V2")
        }
    }

    /**
     * JsonElement → Kotlin 值，数字保型。
     * 默认 Gson Map 解码把一切数字变成 Double，大整数再编码会变成科学计数法
     * （如 1.730000000001E12），无法通过服务端 integer 校验，故手写递归转换。
     */
    private fun elementToAny(el: JsonElement): Any? = when {
        el.isJsonNull -> null
        el.isJsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in el.asJsonObject.entrySet()) map[k] = elementToAny(v)
            map
        }
        el.isJsonArray -> el.asJsonArray.map { elementToAny(it) }
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> {
                    val raw = p.asString
                    if (INT_LITERAL.matches(raw)) {
                        raw.toLongOrNull() ?: p.asDouble
                    } else {
                        p.asDouble
                    }
                }
                else -> p.asString
            }
        }
        else -> throw EnvelopeExceptionV2("unsupported json element")
    }

    private fun requiredString(root: JsonObject, name: String): String {
        val el = root.get(name) ?: throw EnvelopeExceptionV2("missing $name")
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
            throw EnvelopeExceptionV2("$name is not a string")
        }
        return el.asString
    }

    private fun requiredLong(root: JsonObject, name: String): Long {
        val el = root.get(name) ?: throw EnvelopeExceptionV2("missing $name")
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            throw EnvelopeExceptionV2("$name is not a number")
        }
        return try {
            el.asLong
        } catch (e: NumberFormatException) {
            throw EnvelopeExceptionV2("$name is not an integer")
        }
    }
}

/** payload 中的整数字段（Gson Map 解码后为 Number，兼容 Long/Double）。 */
fun payloadLongV2(p: Map<String, Any?>, key: String): Long? = (p[key] as? Number)?.toLong()
