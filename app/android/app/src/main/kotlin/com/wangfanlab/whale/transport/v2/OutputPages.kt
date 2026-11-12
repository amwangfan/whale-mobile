package com.wangfanlab.whale.transport.v2

import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/** Byte offsets are independent of UTF-8 characters; base64 keeps split characters lossless. */
object OutputPages {
    const val MAX_PAGE_BYTES = 48 * 1024
    data class Request(val offset: Long, val maxBytes: Int) {
        init {
            require(offset in 0..9007199254740991L && maxBytes in 1..MAX_PAGE_BYTES)
        }
    }

    fun resolve(ref: String, filters: Map<String, Any?>,
        paged: ((String, Long, Int) -> Map<String, Any?>)?,
        legacySource: () -> Map<String, Any?>): Map<String, Any?> {
        if (filters["page_format"] != "bytes.v1") return legacySource()
        val page = request(filters)
        return paged?.invoke(ref, page.offset, page.maxBytes) ?: legacy(legacySource(), page)
    }

    fun reader(target: Any): ((String, Long, Int) -> Map<String, Any?>)? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == "readOutput" && it.parameterTypes.contentEquals(
                arrayOf(String::class.java, java.lang.Long.TYPE, java.lang.Integer.TYPE))
        } ?: return null
        return { ref, offset, maxBytes ->
            @Suppress("UNCHECKED_CAST")
            (method.invoke(target, ref, offset, maxBytes) as? Map<String, Any?>)
                ?: mapOf("error" to "internal")
        }
    }

    fun request(filters: Map<String, Any?>): Request {
        fun integer(key: String, default: Long, minimum: Long, maximum: Long): Long {
            if (!filters.containsKey(key)) return default
            val n = payloadLongV2(filters, key)
            require(n != null && n >= minimum && n <= maximum) {
                "$key is outside the supported range"
            }
            return n
        }
        return Request(integer("offset", 0, 0, 9007199254740991L),
            integer("max_bytes", MAX_PAGE_BYTES.toLong(), 1, Int.MAX_VALUE.toLong())
                .coerceAtMost(MAX_PAGE_BYTES.toLong()).toInt())
    }

    fun read(file: File, request: Request): Map<String, Any?> {
        require(request.offset >= 0 && request.maxBytes in 1..MAX_PAGE_BYTES)
        return RandomAccessFile(file, "r").use { input ->
            val total = input.length()
            val start = request.offset.coerceAtMost(total)
            input.seek(start)
            val bytes = ByteArray(minOf(request.maxBytes.toLong(), total - start).toInt())
            input.readFully(bytes)
            envelope(bytes, start, total)
        }
    }

    /** Compatibility for other existing output providers, without changing their APIs. */
    fun legacy(output: Map<String, Any?>, request: Request): Map<String, Any?> {
        if (output["error"] != null) return output
        require(listOf("truncated", "source_truncated").all {
            !output.containsKey(it) || output[it] is Boolean
        }) {
            "source truncation must be boolean"
        }
        val bytes = when {
            output["text"] is String -> (output["text"] as String).toByteArray(Charsets.UTF_8)
            output["data_base64"] is String -> Base64.getDecoder().decode(output["data_base64"] as String)
            output["bytes_b64"] is String -> Base64.getDecoder().decode(output["bytes_b64"] as String)
            else -> throw IllegalArgumentException("output encoding missing")
        }
        val start = request.offset.coerceAtMost(bytes.size.toLong()).toInt()
        val end = minOf(bytes.size.toLong(), start.toLong() + request.maxBytes).toInt()
        return envelope(bytes.copyOfRange(start, end), start.toLong(), bytes.size.toLong()) +
            ("source_truncated" to (output["truncated"] == true || output["source_truncated"] == true))
    }

    private fun envelope(bytes: ByteArray, offset: Long, total: Long): Map<String, Any?> = mapOf(
        "bytes_b64" to Base64.getEncoder().encodeToString(bytes),
        "offset" to offset, "next_offset" to offset + bytes.size,
        "total_bytes" to total, "truncated" to (offset + bytes.size < total),
    )
}
