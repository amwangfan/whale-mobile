package com.wangfanlab.whale.modules.authconfirm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.R
import com.wangfanlab.whale.runtime.NodeRuntime

/**
 * 手机可信确认模块（AUTH-01，Issue #15）。
 *
 * Core 下发 device.confirm.request → 本模块校验（结构/有效期/设备绑定）→
 * 高优通知 + 全屏确认 Activity（锁屏可显示）→ 用户明确同意/拒绝：
 * - 同意：Ed25519 签名 challenge_id（UTF-8 字节）→ device.confirm.response；
 * - 拒绝/超时（expires_at 到达）：approved=false + reason（user_denied /
 *   expired），绝不默认批准；
 * - 已配对不等于已确认：每个 challenge 独立 nonce、一次性消费（core 侧
 *   原子核销）；群聊来源的 challenge 只展示来源，不自动提权。
 */
class AuthConfirmModule(
    private val context: Context,
    private val identityProvider: () -> com.wangfanlab.whale.transport.DeviceIdentity,
    private val responder: (challengeId: String, approved: Boolean, signature: String?, reason: String?) -> Unit,
) {

    data class Challenge(
        val challengeId: String,
        val deviceId: String,
        val principalId: String,
        val channel: String,
        val conversationId: String?,
        val scopes: List<String>,
        val actionFingerprint: String?,
        val expiresAt: Long,
    )

    @Volatile
    private var pending: Challenge? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var expiryRunnable: Runnable? = null

    /** WssClient 下行分发入口（主线程）。 */
    fun onConfirmRequest(payload: Map<String, Any?>) {
        val c = parse(payload) ?: run {
            Log.w(TAG, "confirm.request 结构非法，忽略")
            return
        }
        val now = System.currentTimeMillis()
        if (c.expiresAt <= now) {
            Log.w(TAG, "confirm 挑战已过期 challenge=${c.challengeId}")
            respond(c, approved = false, reason = "expired")
            return
        }
        if (c.deviceId != identityProvider().getDeviceId()) {
            // 挑战绑定设备不符：拒绝（不泄露任何信息）
            Log.w(TAG, "confirm 挑战 device_id 不匹配")
            respond(c, approved = false, reason = "device_mismatch")
            return
        }
        pending = c
        ensureChannel()
        notifyUser(c)
        ConfirmActivity.show(context, c)
        scheduleExpiry(c)
        Log.i(TAG, "confirm 挑战已呈现 challenge=${c.challengeId} scopes=${c.scopes}")
    }

    /** 用户同意：签名 challenge_id 并回执。 */
    fun approve() {
        val c = pending ?: return
        pending = null
        cancelExpiry()
        val sig = identityProvider().signHex(c.challengeId.toByteArray(Charsets.UTF_8))
        respond(c, approved = true, signature = sig, reason = null)
        cancelNotification()
        Log.i(TAG, "confirm 已批准 challenge=${c.challengeId}")
    }

    /** 用户拒绝。 */
    fun deny() {
        val c = pending ?: return
        pending = null
        cancelExpiry()
        respond(c, approved = false, reason = "user_denied")
        cancelNotification()
        Log.i(TAG, "confirm 已拒绝 challenge=${c.challengeId}")
    }

    /** 当前待处理挑战（Activity 展示用）。 */
    fun currentPending(): Challenge? = pending

    private fun respond(
        c: Challenge,
        approved: Boolean,
        reason: String? = null,
        signature: String? = null,
    ) {
        responder(c.challengeId, approved, signature, reason)
    }

    private fun scheduleExpiry(c: Challenge) {
        cancelExpiry()
        val delay = (c.expiresAt - System.currentTimeMillis()).coerceAtLeast(1000)
        val r = Runnable {
            if (pending?.challengeId == c.challengeId) {
                pending = null
                respond(c, approved = false, reason = "expired")
                cancelNotification()
                Log.i(TAG, "confirm 挑战超时按拒绝处理 challenge=${c.challengeId}")
            }
        }
        expiryRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    private fun cancelExpiry() {
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        expiryRunnable = null
    }

    private fun notifyUser(c: Challenge) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        val intent = ConfirmActivity.buildIntent(context, c)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val n = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Whale 操作确认")
            .setContentText("${c.channel} 请求授权 ${c.scopes.joinToString(",")}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun cancelNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        nm?.cancel(NOTIF_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Whale 操作确认", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        private const val TAG = "WhaleAuthConfirm"
        private const val CHANNEL_ID = "whale_confirm"
        private const val NOTIF_ID = 43001

        fun parse(payload: Map<String, Any?>): Challenge? {
            val challengeId = payload["challenge_id"] as? String ?: return null
            val deviceId = payload["device_id"] as? String ?: return null
            val principalId = payload["principal_id"] as? String ?: return null
            val channel = payload["channel"] as? String ?: return null
            @Suppress("UNCHECKED_CAST")
            val scopes = (payload["scopes"] as? List<*>)?.filterIsInstance<String>()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val expiresAt = (payload["expires_at"] as? Number)?.toLong() ?: return null
            return Challenge(
                challengeId = challengeId,
                deviceId = deviceId,
                principalId = principalId,
                channel = channel,
                conversationId = payload["conversation_id"] as? String,
                scopes = scopes,
                actionFingerprint = payload["action_fingerprint"] as? String,
                expiresAt = expiresAt,
            )
        }
    }
}
