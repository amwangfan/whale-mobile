package com.wangfanlab.whale.modules.agent2

/** Shared argument policy, isolated from Android capture implementations for JVM tests. */
data class ObserveOptions(val screenshot: Boolean, val uiTree: Boolean, val maxElements: Int) {
    companion object {
        fun parse(arguments: Map<String, Any?>): ObserveOptions {
            fun flag(name: String): Boolean {
                val value = arguments[name] ?: return true
                require(value is Boolean) { "$name must be boolean" }
                return value
            }
            val raw = arguments["max_elements"] ?: 400
            require(raw is Number && raw.toDouble().isFinite() &&
                raw.toDouble() % 1.0 == 0.0 && raw.toDouble() in 1.0..Int.MAX_VALUE.toDouble()) {
                "max_elements must be a positive integer"
            }
            return ObserveOptions(flag("screenshot"), flag("ui_tree"), raw.toInt().coerceAtMost(400))
        }
    }
}
