package com.wangfanlab.whale.transport.v2

import java.io.File
import java.util.Base64

/** Shared reference resolution and file access for legacy and negotiated output reads. */
class AgentOutputFiles(private val out: File, private val ui: File, private val shots: File,
                       private val tailChars: Int) {
    init { require(tailChars > 0) }

    fun read(ref: String, page: OutputPages.Request? = null): Map<String, Any?> {
        val resolved = resolve(ref) ?: return mapOf("error" to "bad_ref")
        val (root, name, image) = resolved
        val file = File(root, name).canonicalFile
        if (file.parentFile != root.canonicalFile) return mapOf("error" to "bad_ref")
        if (!file.isFile) return mapOf("error" to "not_found")
        if (page != null) return OutputPages.read(file, page)
        if (image) {
            val bytes = file.readBytes()
            return mapOf("content_type" to "image_jpeg", "data_base64" to
                Base64.getEncoder().encodeToString(bytes), "size_bytes" to bytes.size.toLong())
        }
        // Decode incrementally, retaining exactly the old UTF-16 character tail semantics.
        val tail = StringBuilder()
        var truncated = false
        file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                tail.append(buffer, 0, count)
                if (tail.length > tailChars) {
                    truncated = true
                    tail.delete(0, tail.length - tailChars)
                }
            }
        }
        return mapOf("content_type" to "text", "text" to tail.toString(), "truncated" to truncated)
    }

    private data class Resolved(val root: File, val name: String, val image: Boolean = false)
    private fun resolve(ref: String): Resolved? {
        if (".." in ref || '\\' in ref) return null
        return when {
            Regex("^agent:out:[A-Za-z0-9_.-]{1,128}$").matches(ref) ->
                Resolved(out, ref.removePrefix("agent:out:"))
            Regex("^agent:ui:obs_[0-9A-HJKMNP-TV-Z]{26}$").matches(ref) ->
                Resolved(ui, ref.removePrefix("agent:ui:") + ".json")
            Regex("^agent:shot:shot-[0-9]+[.](jpg|png)$").matches(ref) ->
                Resolved(shots, ref.removePrefix("agent:shot:"), true)
            else -> null
        }
    }
}
