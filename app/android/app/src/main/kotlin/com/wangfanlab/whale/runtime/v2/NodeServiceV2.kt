package com.wangfanlab.whale.runtime.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangfanlab.whale.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 节点前台服务 V2：[NodeRuntimeV2] 的前台载体与统一启动/停止入口。
 *
 * 与 v1 [com.wangfanlab.whale.runtime.NodeService] 同合规边界（Android 8+
 * 后台限制）：只能由前台场景显式启动，常驻通知带「停止」动作（kill switch），
 * API 29+ 声明 dataSync 类型，Android 15 时长受限时按系统降级不绕过。
 *
 * 与 v1 服务并存（不同渠道/通知 id，互不覆盖）；v1 栈统一下线由编排者执行，
 * 届时本服务成为唯一前台载体。
 */
class NodeServiceV2 : Service() {

    inner class LocalBinder : Binder() {
        val service: NodeServiceV2 get() = this@NodeServiceV2
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令：断开 v2 连接并退出前台")
                NodeRuntimeV2.peek()?.disconnect()
                stopSelfInternal()
                return START_NOT_STICKY
            }
            else -> {
                promoteForeground()
                // START_STICKY 重建（intent=null）：进程被杀后系统拉起服务时
                // 按策略恢复连接（尊重暂停/断开/撤销）。
                if (intent == null) {
                    NodeRuntimeV2.peek()?.recoverIfNeeded("service_restart")
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------- 内部

    private fun promoteForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running.set(true)
            Log.i(TAG, "NodeServiceV2 前台运行中")
        } catch (t: Throwable) {
            // FGS 启动限制：fail-soft —— 服务不前台化，运行时仍可随进程存活，不做绕过。
            Log.w(TAG, "v2 前台服务启动被拒（系统限制），降级为普通服务: ${t.message}")
            running.set(true)
        }
    }

    private fun stopSelfInternal() {
        running.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, NodeServiceV2::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Whale 节点运行中（v2）")
            .setContentText("设备控制通道（协议 v2）保持连接；点「停止」立即断开")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "停止", stopIntent).build(),
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Whale 节点状态（v2）", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "设备控制通道（协议 v2）常驻连接状态（可在此关闭或从通知停止）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WhaleNodeServiceV2"
        private const val CHANNEL_ID = "whale_status_v2"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.wangfanlab.whale.runtime.v2.action.STOP"

        private val running = AtomicBoolean(false)

        /** 前台服务是否在运行（诊断/状态 API）。 */
        val isRunning: Boolean get() = running.get()

        /**
         * 从【前台场景】启动 v2 节点服务（如 UI 发起 connectV2）。Android 12+
         * 禁止后台启动 FGS，调用方须确保进程处于前台——此处捕获并记录，不做绕过。
         */
        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, NodeServiceV2::class.java)
            try {
                app.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService(v2) 失败（可能处于后台）: ${t.message}")
            }
        }

        /** 停止前台服务（不主动断开 WSS；断开请用 ACTION_STOP 或 NodeRuntimeV2.disconnect）。 */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, NodeServiceV2::class.java))
        }
    }
}
