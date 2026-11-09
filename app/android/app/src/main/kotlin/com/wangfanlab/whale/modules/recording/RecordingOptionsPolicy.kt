package com.wangfanlab.whale.modules.recording

import com.wangfanlab.whale.transport.v2.payloadLongV2

/** Validate before converting types, so invalid exclusions never silently become an empty list. */
object RecordingOptionsPolicy {
    data class Options(val profile: String?, val maxDurationMs: Long?, val excluded: Set<String>)

    fun parse(value: Any?): Options? {
        if (value == null) return Options(null, null, emptySet())
        if (value !is Map<*, *> || value.keys.any { it !in setOf("profile", "max_duration_ms", "exclude_packages") }) return null
        val profile = value["profile"]
        if (value.containsKey("profile") && (profile !is String || profile.length > 32)) return null
        @Suppress("UNCHECKED_CAST")
        val duration = payloadLongV2(value as Map<String, Any?>, "max_duration_ms")
        if (value.containsKey("max_duration_ms") && (duration == null || duration !in 1000L..3_600_000L)) return null
        val rawExcluded = value["exclude_packages"]
        if (value.containsKey("exclude_packages") && (rawExcluded !is List<*> || rawExcluded.size > 32 ||
                rawExcluded.any { it !is String || it.length > 128 })) return null
        val excluded = (rawExcluded as? List<*>)?.map { it as String }?.toSet() ?: emptySet()
        return Options(profile as? String, duration, excluded)
    }
}
