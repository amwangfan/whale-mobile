package com.wangfanlab.whale.modules.notification2

import android.content.Context
import android.util.Log

/**
 * notification.event / notification.revoke 全链处理（修正方案 §4.2 / contracts v2 §13）。
 *
 * 处理链（顺序即语义）：
 *   解析 → device_id 核对 → event_id 去重（持久，跨进程/重启）→ 过期检查
 *   → 标记已处理 → 落 Inbox → 权限/渠道校验 → 渲染 → 提交系统 → 上行回执
 *
 * 回执诚实性（§13.2）：
 * - displayed 只在真正提交给 NotificationManager 之后上报，**绝不伪报**；
 * - 权限缺失 / 渠道被用户关闭 → 回 failed（原因 permission_missing / channel_disabled
 *   记本地日志；receipt 协议无 error 字段，只有 status）；
 * - 过期 → 落库为 expired 并回 expired，不弹出（可留通知历史）；
 * - 重复 event_id（WSS/FCM 双通道、断线补传）→ 不重复弹出，
 *   按 Inbox 当前状态重发回执（让 core 幂等收敛，不再补传）。
 *
 * 上行通过注入的 [NotificationUplinkSender]（suspend fun send(payload: String)），
 * 本模块不依赖 WssClientV2；payload 为完整 v2 信封 JSON。
 */
class NotificationEventHandler(
    context: Context,
    private val inbox: NotificationInbox,
    private val dedup: NotificationDeduplicator,
    private val renderer: NotificationRenderer,
    private val facade: NotificationManagerFacade,
    private val channels: NotificationChannelRegistry,
    private val permissions: NotificationPermissionManager,
    private val uplink: NotificationUplinkSender,
    /** 本机 device_id 提供者（device_id 不匹配的事件直接忽略）。 */
    private val deviceIdProvider: () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val appContext = context.applicationContext

    /** 处理结果（调用方/测试断言用）。 */
    sealed interface HandleResult {
        data class Displayed(val eventId: String) : HandleResult
        data class Duplicate(val eventId: String) : HandleResult
        data class Expired(val eventId: String) : HandleResult
        data class Failed(val eventId: String, val reason: String) : HandleResult
        data class Revoked(val notificationId: String, val found: Boolean) : HandleResult
        data object Invalid : HandleResult
        data object DeviceMismatch : HandleResult
    }

    init {
        // 渠道幂等创建（仅首次真正创建；绝不覆盖用户已改的渠道设置，见 Registry 注释）
        channels.ensureChannels()
    }

    // ------------------------------------------------------------  event

    suspend fun handleEvent(json: String): HandleResult {
        val event = NotificationEventParser.parseEvent(json)
        if (event == null) {
            Log.w(TAG, "notification.event 解析失败，丢弃（传输层负责协议错误回执）")
            return HandleResult.Invalid
        }
        val now = clock()

        // 1. device_id 核对：不是发给本机的事件不处理（多设备路由安全网）
        if (event.deviceId != deviceIdProvider()) {
            Log.w(TAG, "device_id 不匹配（${event.deviceId}），忽略 ${event.eventId}")
            return HandleResult.DeviceMismatch
        }

        // 2. event_id 去重：已处理 → 不重复弹出，按当前状态重发回执让 core 收敛
        if (dedup.alreadyProcessed(event.eventId)) {
            Log.i(TAG, "重复事件 ${event.eventId}，不重复展示，按当前状态重发回执")
            val current = inbox.findByEventId(event.eventId)?.status
            sendReceipt(event, current?.toReceiptWire() ?: NotificationInbox.Status.RECEIVED.wire)
            return HandleResult.Duplicate(event.eventId)
        }

        // 3. 过期检查：过期不弹出，落库留历史，回执 expired
        if (event.expiresAt <= now) {
            dedup.markProcessed(event.eventId, now)
            inbox.insert(event, NotificationInbox.Status.EXPIRED, now)
            sendReceipt(event, NotificationInbox.Status.EXPIRED.wire)
            Log.i(TAG, "事件 ${event.eventId} 已过期，不展示")
            return HandleResult.Expired(event.eventId)
        }

        // 4. 标记已处理 + 落 Inbox（received）
        dedup.markProcessed(event.eventId, now)
        inbox.insert(event, NotificationInbox.Status.RECEIVED, now)

        // 5. 权限 / 渠道校验（诚实性：失败回 failed，不伪报 displayed）
        if (!permissions.isGranted()) {
            return fail(event, now, "permission_missing")
        }
        val channelId = channels.channelFor(event.category)
        if (!channels.isChannelEnabled(channelId)) {
            return fail(event, now, "channel_disabled")
        }

        // 6. 渲染 + 提交系统
        val notification = renderer.render(
            event = event,
            contentIntent = NotificationActionReceiver.openPendingIntent(appContext, event),
            deleteIntent = NotificationActionReceiver.dismissedPendingIntent(appContext, event),
            actionIntentFactory = { action ->
                NotificationActionReceiver.actionPendingIntent(appContext, event, action)
            },
        )
        val sysId = dedup.systemNotificationIdFor(event)
        if (!facade.post(sysId, null, notification)) {
            return fail(event, now, "internal")
        }

        // 7. 状态迁移 + displayed 回执
        inbox.updateStatusByEvent(event.eventId, NotificationInbox.Status.DISPLAYED, clock())
        sendReceipt(event, NotificationInbox.Status.DISPLAYED.wire)
        Log.i(TAG, "通知已展示 event=${event.eventId} notif=${event.notificationId}")
        return HandleResult.Displayed(event.eventId)
    }

    private suspend fun fail(
        event: NotificationEvent,
        now: Long,
        reason: String,
    ): HandleResult.Failed {
        Log.w(TAG, "通知展示失败 event=${event.eventId} reason=$reason")
        inbox.updateStatusByEvent(event.eventId, NotificationInbox.Status.FAILED, now)
        sendReceipt(event, NotificationInbox.Status.FAILED.wire)
        return HandleResult.Failed(event.eventId, reason)
    }

    // ------------------------------------------------------------  revoke

    /** notification.revoke：撤下系统通知 + Inbox 状态置 revoked + 回执 dismissed。 */
    suspend fun handleRevoke(json: String): HandleResult {
        val revoke = NotificationEventParser.parseRevoke(json)
        if (revoke == null) {
            Log.w(TAG, "notification.revoke 解析失败，丢弃")
            return HandleResult.Invalid
        }
        if (revoke.deviceId != deviceIdProvider()) {
            Log.w(TAG, "revoke device_id 不匹配，忽略 ${revoke.eventId}")
            return HandleResult.DeviceMismatch
        }
        val now = clock()
        // 撤回指令自身也按 event_id 去重（重放幂等）
        if (dedup.alreadyProcessed(revoke.eventId)) {
            return HandleResult.Duplicate(revoke.eventId)
        }
        dedup.markProcessed(revoke.eventId, now)

        val records = inbox.findByNotificationId(revoke.notificationId)
        if (records.isEmpty()) {
            Log.i(TAG, "revoke 目标 ${revoke.notificationId} 本机无记录，no-op")
            return HandleResult.Revoked(revoke.notificationId, found = false)
        }

        // 撤下系统通知（同一 collapse 映射 id 可能与展示时多条记录共享，去重撤销）
        records.map { dedup.systemNotificationIdFor(it) }.distinct()
            .forEach { facade.cancel(it, null) }
        renderer.clearChatHistory(
            records.first().groupKey ?: revoke.notificationId,
        )
        inbox.updateStatusByNotification(
            revoke.notificationId, NotificationInbox.Status.REVOKED, now,
        )
        // 回执：协议 receipt 无 revoked 值，映射为 dismissed（通知确实已从通知栏消失）
        for (record in records) {
            sendReceipt(
                eventId = record.eventId,
                notificationId = record.notificationId,
                deviceId = revoke.deviceId,
                status = NotificationInbox.Status.DISMISSED.wire,
            )
        }
        Log.i(TAG, "通知已撤回 notif=${revoke.notificationId} reason=${revoke.reason}")
        return HandleResult.Revoked(revoke.notificationId, found = true)
    }

    // ------------------------------------------------------------  outbox flush

    /**
     * 冲刷操作 Outbox（WSS 重连成功后由传输层调用一次）。
     * @return 成功发送条数。
     */
    suspend fun flushActionOutbox(): Int {
        var sent = 0
        for (entry in inbox.pendingActions()) {
            try {
                uplink.send(entry.payloadJson)
                inbox.deleteAction(entry.rowId)
                sent++
            } catch (t: Throwable) {
                inbox.incrementActionAttempts(entry.rowId)
                Log.w(TAG, "action 上行失败，保留待重试 row=${entry.rowId}: ${t.message}")
                break // 保序：头阻塞即停
            }
        }
        return sent
    }

    /** notification.sync 组包（重连后由传输层调用并上行）。 */
    fun buildSyncMessage(nowMs: Long = clock()): String =
        NotificationProtocol.buildSync(
            deviceId = deviceIdProvider(),
            lastEventId = inbox.lastProcessedEventId(),
            syncedAt = nowMs,
        )

    // ------------------------------------------------------------  receipt

    private suspend fun sendReceipt(event: NotificationEvent, status: String) =
        sendReceipt(event.eventId, event.notificationId, event.deviceId, status)

    private suspend fun sendReceipt(
        eventId: String,
        notificationId: String,
        deviceId: String,
        status: String,
    ) {
        try {
            uplink.send(
                NotificationProtocol.buildReceipt(
                    eventId = eventId,
                    notificationId = notificationId,
                    deviceId = deviceId,
                    status = status,
                    atMs = clock(),
                ),
            )
        } catch (t: Throwable) {
            // 回执失败不阻断本地流程；core 侧超时未收会按离线补传重发，
            // 端侧靠 event_id 去重保证不重复弹出。
            Log.w(TAG, "回执上行失败 status=$status event=$eventId: ${t.message}")
        }
    }

    /** 本地状态 → 协议回执值（本地扩展 REVOKED 映射为 dismissed）。 */
    private fun NotificationInbox.Status.toReceiptWire(): String = when (this) {
        NotificationInbox.Status.REVOKED -> NotificationInbox.Status.DISMISSED.wire
        else -> wire
    }

    private companion object {
        const val TAG = "WhaleNotif2Handler"
    }
}
