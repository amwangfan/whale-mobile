package com.wangfanlab.whale.transport

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * contracts/v1 §2 通用信封的编解码与校验。
 *
 * 本文件为纯 JVM 实现（仅依赖 Gson），不引用 android.*，可在本地单元测试运行。
 * 协议事实源：contracts/v1/PROTOCOL.md 与 contracts/v1/messages 下各消息 schema；
 * 端侧不导入 core 代码，两侧各自校验。
 */

const val PROTOCOL_VERSION = "1.0"
const val SUPPORTED_MAJOR = 1

/** §2 约束：单帧 ≤ 256 KiB（UTF-8 字节数）。 */
const val MAX_FRAME_BYTES = 256 * 1024

const val DIR_UPLINK = "uplink"
const val DIR_DOWNLINK = "downlink"

/** ts/seq 的 schema 上限（2100-01-01 / 2^53-1）。 */
const val MAX_TS_MS = 4102444800000L
const val MAX_SEQ = 9007199254740991L

class EnvelopeException(message: String) : Exception(message)

/** 协议消息 ID / 事件幂等键生成器（schema: ^[0-9A-Za-z-]{16,64}$，与 dev_gateway 的 uuid4().hex 对齐）。 */
object Ids {
    private val ID_PATTERN = Regex("^[0-9A-Za-z-]{16,64}$")

    /** 32 字符小写 hex（UUIDv4 去横线）。 */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 上行 sensor.event 的幂等键（§4/§6：幂等键 = event_id，core 按它去重）。
     * 调用方也可自行生成后传入，离线队列按 event_id 做 INSERT OR IGNORE 幂等。
     */
    fun newEventId(): String = newId()

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
}

/** 每方向各自单调递增的序号（§2）。每条 WS 连接从 1 重新开始。 */
class SeqCounter(start: Long = 0) {
    private val value = AtomicLong(start)

    /** 返回下一个序号（首次调用返回 start+1）。 */
    fun next(): Long = value.incrementAndGet()

    /** 当前已发出的最大序号（尚未发送时为 start）。 */
    fun current(): Long = value.get()

    /** 记录对端序号并校验单调性；返回是否为预期值（乱序/跳变返回 false，仅提示不拒绝）。 */
    fun observeIncoming(seq: Long): Boolean {
        while (true) {
            val cur = value.get()
            if (seq <= cur) return false // 重复或乱序
            if (value.compareAndSet(cur, seq)) return seq == cur + 1
        }
    }
}

data class Envelope(
    val v: String = PROTOCOL_VERSION,
    val id: String,
    val type: String,
    val ts: Long,
    val seq: Long,
    val dir: String,
    val payload: Map<String, Any?>,
) {
    fun toJson(): String = EnvelopeCodec.encode(this)

    fun toJsonBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        /** 构造上行信封。seq 必须来自本连接的 SeqCounter。 */
        fun uplink(type: String, seq: Long, payload: Map<String, Any?>, ts: Long): Envelope =
            Envelope(
                v = PROTOCOL_VERSION,
                id = Ids.newId(),
                type = type,
                ts = ts,
                seq = seq,
                dir = DIR_UPLINK,
                payload = payload,
            )
    }
}

object EnvelopeCodec {
    private val gson = Gson()
    private val ID_PATTERN = Regex("^[0-9A-Za-z-]{16,64}$")
    private val V_PATTERN = Regex("^[0-9]+\\.[0-9]+$")
    private val INT_LITERAL = Regex("^-?[0-9]+$")

    fun encode(env: Envelope): String {
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

    /** 解析并做信封级校验（逐消息 payload 校验由上层负责）。未知 type 允许通过（前向兼容）。 */
    fun decode(json: String): Envelope {
        val root: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw EnvelopeException("frame is not a JSON object: ${e.message}")
        }
        val v = requiredString(root, "v")
        if (!V_PATTERN.matches(v)) throw EnvelopeException("bad v: $v")
        val id = requiredString(root, "id")
        if (!ID_PATTERN.matches(id)) throw EnvelopeException("bad id")
        val type = requiredString(root, "type")
        val ts = requiredLong(root, "ts")
        if (ts < 0 || ts > MAX_TS_MS) throw EnvelopeException("ts out of range: $ts")
        val seq = requiredLong(root, "seq")
        if (seq < 0 || seq > MAX_SEQ) throw EnvelopeException("seq out of range: $seq")
        val dir = requiredString(root, "dir")
        if (dir != DIR_UPLINK && dir != DIR_DOWNLINK) throw EnvelopeException("bad dir: $dir")
        val payloadEl = root.get("payload")
            ?: throw EnvelopeException("missing payload")
        if (!payloadEl.isJsonObject) throw EnvelopeException("payload is not an object")
        @Suppress("UNCHECKED_CAST")
        val payload = elementToAny(payloadEl) as Map<String, Any?>
        return Envelope(v, id, type, ts, seq, dir, payload)
    }

    /** 解析 payload JSON 文本（离线队列重发用），数字保型：整数字面量 → Long，其余 → Double。 */
    fun payloadFromJson(json: String): Map<String, Any?> {
        val el = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw EnvelopeException("payload is not valid JSON: ${e.message}")
        }
        if (!el.isJsonObject) throw EnvelopeException("payload is not an object")
        @Suppress("UNCHECKED_CAST")
        return elementToAny(el) as Map<String, Any?>
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
        else -> throw EnvelopeException("unsupported json element")
    }

    /** 编码单个 payload（用于入队存储）。 */
    fun payloadToJson(payload: Map<String, Any?>): String = gson.toJson(payload)

    /** §2 单帧大小校验（UTF-8 字节）。超限抛 EnvelopeException。 */
    fun checkFrameSize(json: String) {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size > MAX_FRAME_BYTES) {
            throw EnvelopeException("frame too large: $size > $MAX_FRAME_BYTES")
        }
    }

    /** 解析 "major.minor" 的 major；无法解析返回 null。 */
    fun majorOf(v: String): Int? = v.substringBefore('.').toIntOrNull()

    private fun requiredString(root: JsonObject, name: String): String {
        val el = root.get(name) ?: throw EnvelopeException("missing $name")
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
            throw EnvelopeException("$name is not a string")
        }
        return el.asString
    }

    private fun requiredLong(root: JsonObject, name: String): Long {
        val el = root.get(name) ?: throw EnvelopeException("missing $name")
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            throw EnvelopeException("$name is not a number")
        }
        return try {
            el.asLong
        } catch (e: NumberFormatException) {
            throw EnvelopeException("$name is not an integer")
        }
    }
}
