package com.wangfanlab.whale.modules.agent

/**
 * 设备自主体模块纯逻辑（core#58，可单测）：参数校验、频控、结果截断。
 *
 * 授权语义（不做命令白名单——issue 明确）：模块开关 + 持续 Root Shell 授权
 * 是两级独立开关；shell 能力必须两者皆开。
 */
object AgentLogic {

    const val MAX_COMMAND_CHARS = 4000
    const val MAX_TIMEOUT_S = 300
    const val DEFAULT_TIMEOUT_S = 30
    const val MAX_OUTPUT_CHARS = 2000 // receipt summary 上限
    const val CONTROL_PER_MIN = 30
    const val SHELL_PER_MIN = 10
    const val OBSERVE_PER_MIN = 20

    sealed interface Decision {
        data class Run(val args: Map<String, Any?>) : Decision
        data class Reject(val errorCode: String) : Decision
    }

    fun validate(
        capability: String,
        args: Map<String, Any?>?,
        moduleEnabled: Boolean,
        rootAuthorized: Boolean,
        allPaused: Boolean,
        estop: Boolean,
        recent: List<Long>,
        nowMs: Long,
    ): Decision {
        if (estop) return Decision.Reject("unavailable")
        if (allPaused) return Decision.Reject("unavailable")
        if (!moduleEnabled) return Decision.Reject("module_disabled")
        if (capability == CAP_SHELL && !rootAuthorized) return Decision.Reject("scope_denied")

        val perMin = when (capability) {
            CAP_CONTROL -> CONTROL_PER_MIN
            CAP_SHELL -> SHELL_PER_MIN
            else -> OBSERVE_PER_MIN
        }
        if (recent.count { nowMs - it < 60_000L } >= perMin) {
            return Decision.Reject("rate_limited")
        }

        when (capability) {
            CAP_CONTROL -> {
                val a = args ?: return Decision.Reject("invalid_message")
                val action = (a["action"] as? String)?.trim()
                if (action !in CONTROL_ACTIONS) return Decision.Reject("invalid_message")
                when (action) {
                    "tap", "swipe" -> {
                        // 坐标校验：x/y 必须为非负数
                        val nums = listOf("x", "y", "x2", "y2")
                            .mapNotNull { k -> (a[k] as? Number)?.toInt() }
                        if (action == "tap" && nums.size < 2) {
                            return Decision.Reject("invalid_message")
                        }
                        if (action == "swipe" && nums.size < 4) {
                            return Decision.Reject("invalid_message")
                        }
                        if (nums.any { it < 0 || it > 10000 }) {
                            return Decision.Reject("invalid_message")
                        }
                    }
                    "input_text" -> {
                        val text = (a["text"] as? String) ?: ""
                        if (text.isEmpty() || text.length > 2000) {
                            return Decision.Reject("invalid_message")
                        }
                    }
                    "launch_app" -> {
                        val pkg = (a["package"] as? String)?.trim() ?: ""
                        if (!Regex("^[A-Za-z0-9._]{2,128}$").matches(pkg)) {
                            return Decision.Reject("invalid_message")
                        }
                    }
                    "key" -> {
                        val key = (a["key"] as? String)?.trim()?.uppercase() ?: ""
                        if (key !in ALLOWED_KEYS) return Decision.Reject("invalid_message")
                    }
                    "media" -> {
                        val op = (a["media_op"] as? String)?.trim() ?: ""
                        if (op !in MEDIA_OPS) return Decision.Reject("invalid_message")
                    }
                }
            }
            CAP_SHELL -> {
                val a2 = args ?: emptyMap()
                val cmd = (a2["command"] as? String)
                    ?: (a2["script"] as? String) ?: ""
                if (cmd.isBlank() || cmd.length > MAX_COMMAND_CHARS) {
                    return Decision.Reject("invalid_message")
                }
                val timeout = (a2["timeout_s"] as? Number)?.toInt()
                if (timeout != null && (timeout < 1 || timeout > MAX_TIMEOUT_S)) {
                    return Decision.Reject("invalid_message")
                }
            }
            CAP_OBSERVE -> {
                val mode = (args?.get("mode") as? String) ?: "ui_tree"
                if (mode !in OBSERVE_MODES) return Decision.Reject("invalid_message")
            }
        }
        return Decision.Run(args ?: emptyMap())
    }

    /** 结果摘要截断（receipt result.summary ≤2000）。 */
    fun truncateSummary(s: String): String =
        if (s.length <= MAX_OUTPUT_CHARS) s
        else s.take(MAX_OUTPUT_CHARS - 20) + "…[truncated]"

    const val CAP_CONTROL = "device.agent.control"
    const val CAP_OBSERVE = "device.agent.observe"
    const val CAP_SHELL = "device.agent.shell"

    val CONTROL_ACTIONS = setOf("launch_app", "tap", "swipe", "key", "input_text", "media")
    val ALLOWED_KEYS = setOf(
        "HOME", "BACK", "ENTER", "TAB", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT",
        "DPAD_RIGHT", "DPAD_CENTER", "VOLUME_UP", "VOLUME_DOWN", "MUTE",
        "POWER", "WAKEUP", "SLEEP", "APP_SWITCH", "SEARCH",
    )
    val MEDIA_OPS = setOf("play", "pause", "play_pause", "next", "previous", "seek_forward", "seek_backward")
    val OBSERVE_MODES = setOf("ui_tree", "screenshot")
}
