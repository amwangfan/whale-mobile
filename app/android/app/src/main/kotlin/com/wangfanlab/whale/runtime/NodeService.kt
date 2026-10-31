package com.wangfanlab.whale.runtime

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
 * 节点前台服务（BG-01）：[NodeRuntime] 的前台载体与统一启动/停止入口。
 *
 * 合规边界（Android 8+ 后台限制）：
 * - 只能由前台场景显式启动（UI 发起 connect / 用户开启开关），
 *   走 [Context.startForegroundService]；从不在后台隐式拉起。
 * - 启动后立即可见常驻通知（whale_status 渠道），通知上有「停止」动作
 *   （kill switch：断开 WSS + 退出前台 + 停服务），用户可随时撤销。
 * - API 29+ 声明 dataSync 前台服务类型；Android 15 对 dataSync 有
 *   时长限制（6h/24h），超限系统会停服务——届时连接降级为进程内存活，
 *   恢复策略属 BG-02 范围，本服务不做任何绕过。
 * - 进程内仅允许一个运行实例（[isRunning]），心跳发送者仍是 NodeRuntime
 *   单例，服务重启只是重新 attach。
 */
class NodeService : Service() {

    inner class LocalBinder : Binder() {
        val service: NodeService get() = this@NodeService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令：断开连接并退出前台")
                NodeRuntime.peek()?.disconnect()
                stopSelfInternal()
                return START_NOT_STICKY
            }
            else -> {
                promoteForeground()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running.set(true)
            Log.i(TAG, "NodeService 前台运行中")
        } catch (t: Throwable) {
            // FGS 启动限制（如后台启动禁令、Android 15 dataSync 额度）：
            // fail-soft —— 服务不前台化，运行时仍可随进程存活，不做任何绕过。
            Log.w(TAG, "前台服务启动被拒（系统限制），降级为普通服务: ${t.message}")
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
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
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
            .setContentTitle("Whale 节点运行中")
            .setContentText("设备控制通道保持连接；点「停止」立即断开")
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
            CHANNEL_ID, "Whale 节点状态", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "设备控制通道常驻连接状态（可在此关闭或从通知停止）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WhaleNodeService"
        private const val CHANNEL_ID = "whale_status"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.wangfanlab.whale.runtime.action.STOP"

        private val running = AtomicBoolean(false)

        /** 前台服务是否在运行（诊断/状态 API）。 */
        val isRunning: Boolean get() = running.get()

        /**
         * 从【前台场景】启动节点服务（如 UI 内发起连接）。Android 12+ 禁止
         * 后台启动 FGS，调用方须确保进程处于前台，否则系统抛异常——
         * 此处捕获并记录，不做绕过。
         */
        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, NodeService::class.java)
            try {
                app.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService 失败（可能处于后台）: ${t.message}")
            }
        }

        /** 停止前台服务（不主动断开 WSS；断开请用 ACTION_STOP 或 NodeRuntime.disconnect）。 */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, NodeService::class.java))
        }
    }
}
