package com.wangfanlab.whale.modules.glasses

import android.content.Context
import android.util.Log
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.notifications.NotificationOp
import com.wangfanlab.whale.modules.notifications.NotificationSpec
import com.wangfanlab.whale.runtime.NodeRuntime
import com.wangfanlab.whale.transport.WssClient

/**
 * 眼镜 HUD 桥模块（GL-01，Issue #13；默认关）。
 *
 * 桥接传输可插拔（[GlassesTransport]）：当前默认 [NullTransport]
 * （雷鸟 iO 实物桥接通道待用户确认 Turbo-IO/厂商 API 细节后接入）。
 * 无桥时按 B5 设计降级为手机通知，回执 provider_ref=degraded:phone_notification
 * ——绝不伪报 shown。
 */
interface GlassesTransport {
    /** 桥是否可用（已连接眼镜）。 */
    fun isConnected(): Boolean

    /**
     * 转发卡片到眼镜。返回 true=桥已接受转发（不代表已显示——
     * 厂商桥无 display ack 时只能到此级）；false=桥不可达。
     */
    fun show(card: GlassesLogic.Card): Boolean
    fun update(cardId: String, fields: Map<String, Any?>): Boolean
    fun dismiss(cardId: String): Boolean
}

/** 默认空传输：无桥。 */
class NullTransport : GlassesTransport {
    override fun isConnected(): Boolean = false
    override fun show(card: GlassesLogic.Card): Boolean = false
    override fun update(cardId: String, fields: Map<String, Any?>): Boolean = false
    override fun dismiss(cardId: String): Boolean = false
}

class GlassesModule(
    private val context: Context,
    private val transport: GlassesTransport = NullTransport(),
    private val notifier: NotificationModule.Notifier = NotificationModule.SystemNotifier(context.applicationContext),
) : WssClient.ActionExecutor {

    private val lock = Any()
    private val recentShows = ArrayDeque<Long>()

    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?) -> Unit,
    ) {
        val capability = payload["capability"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?> ?: emptyMap()
        val now = System.currentTimeMillis()

        if (capability == CAPABILITY_SHOW) {
            val decision = synchronized(lock) {
                val d = GlassesLogic.validateShow(
                    args,
                    allPaused = NodeRuntime.isAllPaused(context),
                    moduleEnabled = isEnabled(context),
                    recentTimestamps = recentShows.toList(),
                    nowMs = now,
                )
                if (d is GlassesLogic.Decision.Show) {
                    recentShows.addLast(now)
                    while (recentShows.size > 64) recentShows.removeFirst()
                }
                d
            }
            when (decision) {
                is GlassesLogic.Decision.Reject ->
                    reply("failed", decision.errorCode, null)
                is GlassesLogic.Decision.Show -> handleShow(decision.card, reply)
            }
            return
        }

        // update / dismiss
        val cardId = (args["card_id"] as? String)?.trim()
        if (cardId.isNullOrEmpty()) {
            reply("failed", "invalid_message", null)
            return
        }
        val ok = if (capability == CAPABILITY_UPDATE) {
            transport.update(cardId, args)
        } else {
            transport.dismiss(cardId)
        }
        if (ok) {
            reply("executed", null, "forwarded_no_display_ack")
        } else {
            reply("failed", "unavailable", null)
        }
    }

    private fun handleShow(
        card: GlassesLogic.Card,
        reply: (String, String?, String?) -> Unit,
    ) {
        if (transport.isConnected() && transport.show(card)) {
            // 桥已转发；无 display ack 就明确标 forwarded_no_display_ack
            reply("executed", null, "forwarded_no_display_ack")
            return
        }
        // 降级：手机通知（B5 设计），标注 degraded
        try {
            notifier.post(
                NotificationSpec(
                    op = NotificationOp.POST,
                    channelId = NotificationModule.CHANNEL_REMINDER,
                    title = card.title.ifBlank { "Whale 提醒" },
                    text = card.body,
                    notificationTag = "glasses-${card.cardId}",
                    openAppOnClick = true,
                ),
            )
            reply("executed", null, "degraded:phone_notification")
            Log.i(TAG, "眼镜不可达，已降级手机通知 card=${card.cardId}")
        } catch (t: Throwable) {
            Log.w(TAG, "降级通知失败", t)
            reply("failed", "unavailable", null)
        }
    }

    companion object {
        private const val TAG = "WhaleGlasses"
        const val CAPABILITY_SHOW = "glasses.card.show"
        const val CAPABILITY_UPDATE = "glasses.card.update"
        const val CAPABILITY_DISMISS = "glasses.card.dismiss"

        const val KEY_ENABLED = "glasses_enabled"

        fun isEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }
}
