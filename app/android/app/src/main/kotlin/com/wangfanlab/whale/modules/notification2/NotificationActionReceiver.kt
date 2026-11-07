package com.wangfanlab.whale.modules.notification2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知按钮/点击/划掉的广播接收器（修正方案 §3.4）。
 *
 * 三个动作：
 * - [ACTION_BUTTON]：操作按钮（如审批的 approve/reject）→ Inbox 状态置
 *   action_submitted → 组包 notification.action 写本地 Outbox → 尝试上行 →
 *   重渲染「处理中」视觉反馈（无按钮 + 不确定进度）。
 *   **注意：按钮点击不产生任何本地授权**——审批类动作的真正裁决在 core
 *   （approval_id 一次性核销，contracts v2 §14），端侧只负责回传意图与反馈。
 * - [ACTION_OPEN]：点击正文 → Inbox 置 opened + 回执 opened → 按
 *   [NotificationDeepLinkRouter] 拉起 MainActivity（Flutter 侧读取 extras 路由）。
 * - [ACTION_DISMISSED]：用户划掉 → Inbox 置 dismissed + 回执 dismissed。
 *
 * 依赖注入：进程可能被系统拉起（Flutter/Service 均未运行），依赖经
 * [dependencies] 静态注入——由 Application/后台服务在 onCreate 装配；
 * 未装配时按钮动作落 Outbox 不丢失，上行留待下次进程启动冲刷。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    /** 运行期依赖（静态注入；进程重建后需重新装配）。 */
    class Dependencies(
        val inbox: NotificationInbox,
        val dedup: NotificationDeduplicator,
        val renderer: NotificationRenderer,
        val facade: NotificationManagerFacade,
        val uplink: NotificationUplinkSender,
        val deviceIdProvider: () -> String,
        val clock: () -> Long = { System.currentTimeMillis() },
        /** goAsync 窗口内执行发送的协程作用域（测试可注入 Unconfined 实现同步断言）。 */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    override fun onReceive(context: Context, intent: Intent) {
        val deps = dependencies
        if (deps == null) {
            Log.w(TAG, "依赖未装配（dependencies==null），忽略 ${intent.action}")
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        deps.scope.launch {
            try {
                when (intent.action) {
                    ACTION_BUTTON -> handleButton(appContext, intent, deps)
                    ACTION_OPEN -> handleOpen(appContext, intent, deps)
                    ACTION_DISMISSED -> handleDismissed(intent, deps)
                    else -> Log.w(TAG, "未知 action=${intent.action}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "通知动作处理失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    // ------------------------------------------------------------  按钮操作

    private suspend fun handleButton(context: Context, intent: Intent, deps: Dependencies) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val deviceId = deps.deviceIdProvider()
        val now = deps.clock()

        // 1. 状态迁移：action_submitted（回执状态机 §13.2）
        deps.inbox.updateStatusByEvent(eventId, NotificationInbox.Status.ACTION_SUBMITTED, now)

        // 2. 组包 notification.action → 先落 Outbox（进程被杀也不丢）→ 尝试上行
        val payload = NotificationProtocol.buildAction(
            eventId = eventId,
            notificationId = notificationId,
            deviceId = deviceId,
            actionId = actionId,
            submittedAt = now,
        )
        deps.inbox.enqueueAction(eventId, notificationId, actionId, payload, now)
        flushOutbox(deps)

        // 3. 处理中视觉反馈：同系统 id 覆盖，隐藏按钮 + 不确定进度
        val record = deps.inbox.findByEventId(eventId)
        if (record != null) {
            val event = record.toEvent()
            val feedback = deps.renderer.render(
                event = event,
                contentIntent = openPendingIntent(context, event),
                deleteIntent = dismissedPendingIntent(context, event),
                actionIntentFactory = { action -> actionPendingIntent(context, event, action) },
                feedbackText = FEEDBACK_PENDING,
            )
            deps.facade.post(deps.dedup.systemNotificationIdFor(record), null, feedback)
        }
        Log.i(TAG, "通知操作已提交 action=$actionId notif=$notificationId（裁决在 core）")
    }

    /** 冲刷 Outbox：成功删除，失败计数并停止（保序）。 */
    private suspend fun flushOutbox(deps: Dependencies) {
        for (entry in deps.inbox.pendingActions()) {
            try {
                deps.uplink.send(entry.payloadJson)
                deps.inbox.deleteAction(entry.rowId)
            } catch (t: Throwable) {
                deps.inbox.incrementActionAttempts(entry.rowId)
                Log.w(TAG, "action 上行失败，保留 Outbox: ${t.message}")
                break
            }
        }
    }

    // ------------------------------------------------------------  点击 / 划掉

    private suspend fun handleOpen(context: Context, intent: Intent, deps: Dependencies) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val now = deps.clock()
        deps.inbox.updateStatusByEvent(eventId, NotificationInbox.Status.OPENED, now)
        deps.inbox.markRead(notificationId)
        sendReceipt(deps, eventId, notificationId, NotificationInbox.Status.OPENED.wire)

        val record = deps.inbox.findByEventId(eventId)
        val launch = NotificationDeepLinkRouter.buildLaunchIntent(
            context, record?.toTarget(), notificationId, eventId,
        )
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "拉起 MainActivity 失败: ${t.message}")
        }
    }

    private suspend fun handleDismissed(intent: Intent, deps: Dependencies) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        deps.inbox.updateStatusByEvent(
            eventId, NotificationInbox.Status.DISMISSED, deps.clock(),
        )
        sendReceipt(deps, eventId, notificationId, NotificationInbox.Status.DISMISSED.wire)
    }

    private suspend fun sendReceipt(
        deps: Dependencies,
        eventId: String,
        notificationId: String,
        status: String,
    ) {
        try {
            deps.uplink.send(
                NotificationProtocol.buildReceipt(
                    eventId = eventId,
                    notificationId = notificationId,
                    deviceId = deps.deviceIdProvider(),
                    status = status,
                    atMs = deps.clock(),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "回执上行失败 status=$status event=$eventId: ${t.message}")
        }
    }

    // ------------------------------------------------------------  record → event

    private fun NotificationInbox.Record.toTarget(): NotificationTarget? {
        val json = targetJson ?: return null
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            NotificationTarget(
                type = obj.get("type")?.asString ?: return null,
                id = obj.get("id")?.asString,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 Inbox 记录重建渲染用事件（actions 置空——反馈态不需要按钮）。 */
    private fun NotificationInbox.Record.toEvent(): NotificationEvent = NotificationEvent(
        eventId = eventId,
        notificationId = notificationId,
        deviceId = "",
        category = category,
        title = title,
        body = body,
        priority = "normal",
        createdAt = createdAt,
        expiresAt = expiresAt,
        target = toTarget(),
        actions = emptyList(),
        groupKey = groupKey,
        collapseKey = collapseKey,
    )

    companion object {
        private const val TAG = "WhaleNotif2Action"

        const val ACTION_BUTTON = "com.wangfanlab.whale.notification2.ACTION_BUTTON"
        const val ACTION_OPEN = "com.wangfanlab.whale.notification2.ACTION_OPEN"
        const val ACTION_DISMISSED = "com.wangfanlab.whale.notification2.ACTION_DISMISSED"

        const val EXTRA_EVENT_ID = "whale_event_id"
        const val EXTRA_NOTIFICATION_ID = "whale_notification_id"
        const val EXTRA_ACTION_ID = "whale_action_id"

        /** 「处理中」反馈文案。 */
        const val FEEDBACK_PENDING = "已提交，等待 Core 裁决"

        @Volatile
        var dependencies: Dependencies? = null

        /** 装配运行期依赖（Application/后台服务 onCreate 调用）。 */
        fun wire(deps: Dependencies) {
            dependencies = deps
        }

        fun unwire() {
            dependencies = null
        }

        /** 操作按钮 PendingIntent（requestCode 按 notif+action 稳定，UPDATE_CURRENT 幂等）。 */
        fun actionPendingIntent(
            context: Context,
            event: NotificationEvent,
            action: NotificationAction,
        ): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = ACTION_BUTTON
                putExtra(EXTRA_EVENT_ID, event.eventId)
                putExtra(EXTRA_NOTIFICATION_ID, event.notificationId)
                putExtra(EXTRA_ACTION_ID, action.actionId)
            }
            return PendingIntent.getBroadcast(
                context,
                (event.notificationId + "/" + action.actionId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** 点击正文 PendingIntent（经本接收器回执 opened 后拉起 MainActivity）。 */
        fun openPendingIntent(context: Context, event: NotificationEvent): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_OPEN
                putExtra(EXTRA_EVENT_ID, event.eventId)
                putExtra(EXTRA_NOTIFICATION_ID, event.notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                (event.notificationId + "/open").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** 用户划掉 PendingIntent（deleteIntent）。 */
        fun dismissedPendingIntent(context: Context, event: NotificationEvent): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_DISMISSED
                putExtra(EXTRA_EVENT_ID, event.eventId)
                putExtra(EXTRA_NOTIFICATION_ID, event.notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                (event.notificationId + "/dismissed").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
