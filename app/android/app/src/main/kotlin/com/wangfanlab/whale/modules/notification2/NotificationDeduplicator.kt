package com.wangfanlab.whale.modules.notification2

/**
 * 通知去重 / 折叠 / 分组决策（修正方案 §2 核心原则：同一事件经多通道送达只展示一次）。
 *
 * - event_id 去重：持久化在 [NotificationInbox] 的 processed_events 表，
 *   **跨进程、跨重启有效**（WSS 与 FCM 同时送达、断线补传重发都只展示一次）。
 * - collapse_key：同键映射到同一系统通知 id，新通知**替换**旧通知
 *   （任务进度合并更新、不连弹；缺省回退 notification_id）。
 * - group_key：映射为通知分组（同组聚合展示）。
 */
class NotificationDeduplicator(
    private val inbox: NotificationInbox,
) {

    /** event_id 是否已处理过（含已展示/已过期/已失败——处理过就不再弹）。 */
    fun alreadyProcessed(eventId: String): Boolean = inbox.isProcessed(eventId)

    /** 标记 event_id 已处理（幂等）。 */
    fun markProcessed(eventId: String, nowMs: Long) = inbox.markProcessed(eventId, nowMs)

    /**
     * 系统通知 id：collapse_key 优先，缺省 notification_id。
     * 同 key → 同 id → NotificationManager.notify 覆盖更新而非新弹一条。
     */
    fun systemNotificationIdFor(event: NotificationEvent): Int =
        stableIdOf(event.collapseKey ?: event.notificationId)

    /** 撤回时按记录还原系统通知 id（须与展示时同一算法）。 */
    fun systemNotificationIdFor(record: NotificationInbox.Record): Int =
        stableIdOf(record.collapseKey ?: record.notificationId)

    /** 分组键（null 表示不分组）。 */
    fun groupKeyFor(event: NotificationEvent): String? =
        event.groupKey?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * 字符串 key → 稳定系统通知 id。Java String.hashCode 跨进程/重启确定，
         * 与 v1 NotificationModule.notificationIdOf 同一思路。碰撞影响仅为
         * 两条不同通知互相覆盖，概率可忽略（key 空间受 ULID 保护）。
         */
        fun stableIdOf(key: String): Int = key.hashCode()
    }
}
