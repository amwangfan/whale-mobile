package com.wangfanlab.whale.runtime.v2

import android.app.ActivityManager
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
 * startForegroundService 看门狗纪律（nova 回归修复 2026-11-25）：
 * `startForegroundService()` 后系统要求 ~5s 内成功 `startForeground()`，
 * 否则抛 RemoteServiceException 杀进程。本服务三条防线：
 * 1. **尽早前台化**：onCreate 即 promoteForeground（不等 onStartCommand
 *    的第二个主线程消息——冷启动主线程被 Flutter 引擎首帧挤占时多一跳多一分险）；
 * 2. **失败即自毁**：startForeground 被拒（Android 12+ 后台启动禁令 / OEM
 *    策略）→ 立即 stopSelf() 撤掉看门狗，绝不留「普通服务」残躯等死；
 * 3. **不误触启动**：[start] 仅在进程前台态调用 startForegroundService；
 *    后台场景（开机广播/通知动作拉起进程）跳过——连接进程内继续（进程死则断，
 *    诚实降级，不在后台赌 FGS 时窗）。
 */
class NodeServiceV2 : Service() {

    inner class LocalBinder : Binder() {
        val service: NodeServiceV2 get() = this@NodeServiceV2
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 防线 1：尽早前台化（onStartCommand 里再 promote 一次是幂等更新）。
        promoteForeground("onCreate")
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
                promoteForeground("onStartCommand")
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

    private fun promoteForeground(why: String) {
        if (running.get()) return // 幂等：已前台化
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
            Log.i(TAG, "NodeServiceV2 前台运行中（$why）")
        } catch (t: Throwable) {
            onPromoteForegroundFailed(t, why)
        }
    }

    /**
     * 防线 2：前台化失败 → 立即 stopSelf() 撤掉系统看门狗。
     *
     * startForegroundService 启动的服务若不在时限内成功 startForeground，
     * 系统对进程抛 RemoteServiceException（服务侧捕获不了）；stopSelf 销毁
     * 服务后看门狗撤销。运行时仍可随进程存活（进程死则连接断，诚实降级）。
     */
    internal fun onPromoteForegroundFailed(t: Throwable, why: String) {
        Log.w(TAG, "v2 前台化被拒（$why，系统限制/OEM 策略），服务自毁防看门狗: ${t.message}")
        running.set(false)
        stopSelf()
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

        /** 测试钩子：静态 running 跨用例复位（勿用于生产路径）。 */
        @androidx.annotation.VisibleForTesting
        internal fun resetRunningForTest() {
            running.set(false)
        }

        /**
         * 从【前台场景】启动 v2 节点服务（如 UI 发起 connectV2）。
         *
         * 防线 3：进程不在前台态时不调用 startForegroundService——Android 12+
         * 后台启动禁令在多数 ROM 抛 ForegroundServiceStartNotAllowedException
         * （可捕获），但部分 OEM（如 MagicOS）允许启动成功却在服务前台化时拒绝，
         * 留下看门狗在 ~5s 后杀进程。宁可不启动，不赌时窗。
         *
         * @return 是否实际发起了 startForegroundService（跳过/失败=false）。
         */
        fun start(context: Context): Boolean {
            val app = context.applicationContext
            val importance = ForegroundStartGuard.ownProcessImportance(app)
            if (!ForegroundStartGuard.mayStartForegroundService(importance)) {
                Log.i(TAG, "进程非前台态（importance=$importance），跳过 v2 前台服务启动" +
                    "（连接进程内继续，进程死则断）")
                return false
            }
            val intent = Intent(app, NodeServiceV2::class.java)
            return try {
                app.startForegroundService(intent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService(v2) 失败: ${t.message}")
                false
            }
        }

        /** 停止前台服务（不主动断开 WSS；断开请用 ACTION_STOP 或 NodeRuntimeV2.disconnect）。 */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, NodeServiceV2::class.java))
        }
    }
}

/**
 * 前台启动门卫（可纯 JVM 单测的判定核心）。
 *
 * 允许门槛：`importance <= IMPORTANCE_FOREGROUND_SERVICE`（前台 100 /
 * 前台服务 125）；TOP_SLEEPING（325）/VISIBLE/PERCEPTIBLE/BACKGROUND 一律
 * 不允许——那些状态下 FGS 时窗赌不起。
 */
object ForegroundStartGuard {

    /** importance 判定（null=取不到进程态，按不允许处理——宁缺毋滥）。 */
    fun mayStartForegroundService(importance: Int?): Boolean {
        return importance != null &&
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    /** 本进程 importance（runningAppProcesses 查自身；取不到返回 null）。 */
    fun ownProcessImportance(context: Context): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val pkg = context.packageName
        return am.runningAppProcesses?.firstOrNull { it.processName == pkg }?.importance
    }
}
