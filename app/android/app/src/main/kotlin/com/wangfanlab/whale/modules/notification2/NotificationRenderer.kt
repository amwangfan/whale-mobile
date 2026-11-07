package com.wangfanlab.whale.modules.notification2

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

/**
 * 通知渲染器（修正方案 §3.3 五类样式）。
 *
 * - 聊天：MessagingStyle，多条消息按 mergeKey（group_key ?: notification_id）合并；
 * - 任务：进度样式——body 尾部 "NN%" 解析为确定进度，否则 task.progress 用不确定进度；
 *   合并更新不连弹：同 collapse_key → 同系统通知 id 覆盖 + setOnlyAlertOnce；
 * - 审批：BigText + 批准/拒绝等操作按钮（PendingIntent → NotificationActionReceiver）；
 * - 设备/提醒：BigText 摘要样式。
 *
 * title/body 截断已在解析层按协议上限完成（≤200 / ≤2000），此处不再二次截断。
 * 渲染只组装 Notification 对象；提交给系统由 [NotificationManagerFacade] 完成。
 */
class NotificationRenderer(
    private val context: Context,
    private val channels: NotificationChannelRegistry,
    /** 聊天合并历史存储（可注入内存实现做测试；默认进程内内存实现）。 */
    private val chatHistory: ChatHistoryStore = InMemoryChatHistoryStore(),
) {

    private val appContext = context.applicationContext

    /** 聊天合并历史抽象（按会话追加消息，供 MessagingStyle 重建）。 */
    interface ChatHistoryStore {
        fun append(key: String, sender: String, text: String, tsMs: Long): List<Message>
        fun clear(key: String)
    }

    data class Message(val sender: String, val text: String, val tsMs: Long)

    /** 进程内内存实现（进程被杀后首条消息退化为单条样式，可接受）。 */
    class InMemoryChatHistoryStore(private val maxMessages: Int = 8) : ChatHistoryStore {
        private val map = LinkedHashMap<String, MutableList<Message>>()

        @Synchronized
        override fun append(key: String, sender: String, text: String, tsMs: Long): List<Message> {
            val list = map.getOrPut(key) { mutableListOf() }
            list.add(Message(sender, text, tsMs))
            while (list.size > maxMessages) list.removeAt(0)
            return list.toList()
        }

        @Synchronized
        override fun clear(key: String) {
            map.remove(key)
        }
    }

    /**
     * 渲染通知。
     *
     * @param contentIntent 点击正文（→ ActionReceiver.ACTION_OPEN → DeepLink → MainActivity）
     * @param deleteIntent  用户划掉（→ ActionReceiver.ACTION_DISMISSED → 回执 dismissed）
     * @param actionIntentFactory 按 action_id 生成操作按钮 PendingIntent
     * @param feedbackText  非空时进入「处理中」反馈态：隐藏操作按钮 + 不确定进度
     *                      （审批按钮点击后由 ActionReceiver 重渲染，真正裁决在 core）。
     */
    fun render(
        event: NotificationEvent,
        contentIntent: PendingIntent,
        deleteIntent: PendingIntent,
        actionIntentFactory: (NotificationAction) -> PendingIntent,
        feedbackText: String? = null,
    ): Notification {
        val channelId = channels.channelFor(event.category)
        val builder = Notification.Builder(appContext, channelId)
            .setContentTitle(event.title)
            .setSmallIcon(smallIconRes())
            .setShowWhen(true)
            .setWhen(event.createdAt)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(true)
            .setCategory(systemCategoryOf(event))

        dedupGroupKey(event)?.let { builder.setGroup(it) }

        when {
            feedbackText != null -> {
                // 处理中视觉反馈：无按钮、不确定进度、不重复提醒
                builder.setContentText(event.body)
                    .setSubText(feedbackText)
                    .setProgress(0, 0, true)
                    .setOnlyAlertOnce(true)
            }

            event.isChat -> applyChatStyle(builder, event)

            event.category.startsWith("task.") -> applyTaskStyle(builder, event)

            else -> {
                // 审批 / 设备 / 提醒 / 系统：BigText 摘要
                builder.setContentText(event.body)
                if (event.body.isNotEmpty()) {
                    builder.setStyle(Notification.BigTextStyle().bigText(event.body))
                }
            }
        }

        // 操作按钮（审批类带批准/拒绝；协议上限 4 个；处理中反馈态隐藏）
        if (feedbackText == null) {
            for (action in event.actions) {
                builder.addAction(
                    Notification.Action.Builder(null, action.label, actionIntentFactory(action))
                        .build(),
                )
            }
        }

        return builder.build()
    }

    /** 聊天：MessagingStyle 多条合并（mergeKey = group_key ?: notification_id）。 */
    private fun applyChatStyle(builder: Notification.Builder, event: NotificationEvent) {
        val mergeKey = event.groupKey ?: event.notificationId
        val messages = chatHistory.append(mergeKey, event.title, event.body, event.createdAt)
        val style = Notification.MessagingStyle("我")
            .setConversationTitle(event.title)
        for (m in messages) {
            // CharSequence 发送者构造（Person API 需 28+，minSdk=26 用兼容重载）
            @Suppress("DEPRECATION")
            style.addMessage(Notification.MessagingStyle.Message(m.text, m.tsMs, m.sender))
        }
        builder.setStyle(style)
        builder.setContentText(event.body)
    }

    /** 任务：进度样式。task.progress 合并更新不连弹（onlyAlertOnce）。 */
    private fun applyTaskStyle(builder: Notification.Builder, event: NotificationEvent) {
        builder.setContentText(event.body)
        if (event.body.isNotEmpty()) {
            builder.setStyle(Notification.BigTextStyle().bigText(event.body))
        }
        val percent = parsePercent(event.body)
        when {
            percent != null -> builder.setProgress(100, percent, false)
            event.isTaskProgress -> builder.setProgress(0, 0, true) // 无百分比→不确定进度
        }
        if (event.isTaskProgress) {
            // 进度更新走同 collapse_key 覆盖（见 NotificationDeduplicator），不重复响铃/震动
            builder.setOnlyAlertOnce(true)
        }
    }

    /** 从 body 尾部解析 "NN%"（0-100）；无则 null。 */
    private fun parsePercent(body: String): Int? {
        val m = PERCENT_REGEX.find(body) ?: return null
        return m.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }

    private fun dedupGroupKey(event: NotificationEvent): String? =
        event.groupKey?.takeIf { it.isNotBlank() }

    private fun smallIconRes(): Int =
        appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun systemCategoryOf(event: NotificationEvent): String = when {
        event.isChat -> Notification.CATEGORY_MESSAGE
        event.isApproval -> Notification.CATEGORY_REMINDER // 需用户决定，类提醒语义
        event.isTaskProgress -> Notification.CATEGORY_PROGRESS
        event.category.startsWith("task.") -> Notification.CATEGORY_STATUS
        event.category.startsWith("device.") -> Notification.CATEGORY_STATUS
        else -> Notification.CATEGORY_REMINDER
    }

    /** 撤回/终态时清理聊天合并历史。 */
    fun clearChatHistory(mergeKey: String) = chatHistory.clear(mergeKey)

    private companion object {
        val PERCENT_REGEX = Regex("(\\d{1,3})\\s*%")
    }
}
