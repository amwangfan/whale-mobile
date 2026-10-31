package com.wangfanlab.whale.modules.glasses

/**
 * 眼镜 HUD 桥纯逻辑（GL-01，Issue #13，可单测）。
 *
 * 回执分级（绝不伪报 shown）：
 * - 桥不可用/未连接 → failed/unavailable；
 * - 桥已转发但无显示确认 → executed + provider_ref="forwarded_no_display_ack"；
 * - 桥断连 → 降级手机通知 → executed + provider_ref="degraded:phone_notification"。
 *
 * 内容规则：title ≤40、body ≤120；owner_sensitive 默认不上镜；
 * 频率 ≤6/min。
 */
object GlassesLogic {

    const val MAX_TITLE_CHARS = 40
    const val MAX_BODY_CHARS = 120
    const val RATE_LIMIT_PER_MIN = 6

    data class Card(
        val cardId: String,
        val title: String,
        val body: String,
        val ttlMs: Long,
        val privacyClass: String,
    )

    sealed interface Decision {
        data class Show(val card: Card) : Decision
        data class Reject(val errorCode: String) : Decision
    }

    /** 校验 show 参数（长度/隐私级/频控）。 */
    fun validateShow(
        args: Map<String, Any?>,
        allPaused: Boolean,
        moduleEnabled: Boolean,
        recentTimestamps: List<Long>,
        nowMs: Long,
    ): Decision {
        if (allPaused) return Decision.Reject("unavailable")
        if (!moduleEnabled) return Decision.Reject("module_disabled")
        val cardId = (args["card_id"] as? String)?.trim()
            ?: return Decision.Reject("invalid_message")
        if (cardId.length !in 4..64) return Decision.Reject("invalid_message")
        val title = ((args["title"] as? String) ?: "").take(MAX_TITLE_CHARS)
        val body = ((args["body"] as? String) ?: "").take(MAX_BODY_CHARS)
        if (title.isBlank() && body.isBlank()) return Decision.Reject("invalid_message")
        val privacy = (args["privacy_class"] as? String) ?: "owner_private"
        if (privacy == "owner_sensitive" || privacy == "device_secret") {
            // owner_sensitive 默认不上镜（需逐笔授权，V1.1 再议）
            return Decision.Reject("scope_denied")
        }
        val ttl = (args["ttl_ms"] as? Number)?.toLong()?.coerceIn(3_000L, 300_000L)
            ?: 30_000L
        // 频控：最近 60s 已超上限 → rate_limited
        val recent = recentTimestamps.count { nowMs - it < 60_000L }
        if (recent >= RATE_LIMIT_PER_MIN) return Decision.Reject("rate_limited")
        return Decision.Show(Card(cardId, title, body, ttl, privacy))
    }
}
