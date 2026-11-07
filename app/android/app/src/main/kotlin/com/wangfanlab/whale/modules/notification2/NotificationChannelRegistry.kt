package com.wangfanlab.whale.modules.notification2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * 通知渠道注册表（修正方案 §3.2）：6 个渠道，category → channel 的映射唯一入口。
 *
 * Android 渠道语义（注释说明，依修正方案 §3.2 注意事项）：
 * - createNotificationChannel 对**同 id 已存在**的渠道是幂等 no-op，
 *   **不会覆盖用户在系统设置里改过的渠道行为**（重要度/铃声/震动等以用户为准）；
 *   因此每次启动重复调用本方法是安全的，且**绝不**通过删渠道重建来"恢复默认"。
 * - 渠道创建只需发生一次（首次）。这里做成幂等调用，由 NotificationEventHandler
 *   首次处理事件时触发；App 启动时也可主动调 [ensureChannels]。
 * - minSdk=26，渠道 API 恒可用，无需版本分支。
 */
class NotificationChannelRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(NotificationManager::class.java)

    /** 幂等创建全部渠道（仅首次真正创建；此后系统忽略，用户设置不被覆盖）。 */
    fun ensureChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CHAT, "聊天消息", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "新消息、角色回复" },
                NotificationChannel(CHANNEL_TASK, "任务通知", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "任务完成、失败、进度" },
                // 审批是唯一的高重要级渠道（修正方案 §3.2：不滥用 High）
                NotificationChannel(CHANNEL_APPROVAL, "等待审批", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "需要用户决定的操作" },
                NotificationChannel(CHANNEL_DEVICE, "设备状态", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Root 手机离线、连接恢复、异常" },
                NotificationChannel(CHANNEL_REMINDER, "提醒", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "定时任务和主动提醒" },
                NotificationChannel(CHANNEL_SYSTEM, "系统通知", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "更新、服务状态、重要提示" },
            )
        )
        Log.i(TAG, "通知渠道已确保存在（已存在的渠道不覆盖用户设置）")
    }

    /** category → channel 映射；未知 category 归入系统渠道（前向兼容）。 */
    fun channelFor(category: String): String = when (category) {
        NotificationEvent.CATEGORY_CHAT -> CHANNEL_CHAT
        NotificationEvent.CATEGORY_TASK_STARTED,
        NotificationEvent.CATEGORY_TASK_PROGRESS,
        NotificationEvent.CATEGORY_TASK_COMPLETED,
        NotificationEvent.CATEGORY_TASK_FAILED,
        -> CHANNEL_TASK
        NotificationEvent.CATEGORY_APPROVAL -> CHANNEL_APPROVAL
        NotificationEvent.CATEGORY_DEVICE_OFFLINE,
        NotificationEvent.CATEGORY_DEVICE_ONLINE,
        -> CHANNEL_DEVICE
        NotificationEvent.CATEGORY_REMINDER -> CHANNEL_REMINDER
        else -> CHANNEL_SYSTEM // memory.updated 与未知分类
    }

    /**
     * 渠道是否被用户关闭（importance == IMPORTANCE_NONE）。
     * 诚实性（§13.2）：渠道被关时端侧必须回 failed 而非伪报 displayed。
     */
    fun isChannelEnabled(channelId: String): Boolean {
        val ch = nm.getNotificationChannel(channelId) ?: return false
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        private const val TAG = "WhaleNotif2Channel"

        const val CHANNEL_CHAT = "whale_chat"
        const val CHANNEL_TASK = "whale_task"
        const val CHANNEL_APPROVAL = "whale_approval"
        const val CHANNEL_DEVICE = "whale_device"
        const val CHANNEL_REMINDER = "whale_reminder"
        const val CHANNEL_SYSTEM = "whale_system"

        val ALL_CHANNELS: Set<String> = setOf(
            CHANNEL_CHAT, CHANNEL_TASK, CHANNEL_APPROVAL,
            CHANNEL_DEVICE, CHANNEL_REMINDER, CHANNEL_SYSTEM,
        )
    }
}
