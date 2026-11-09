package com.wangfanlab.whale.modules.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.wangfanlab.whale.R
import com.wangfanlab.whale.modules.agent2.Agent2Module
import com.wangfanlab.whale.modules.capture.ExcludedPackagesStore
import com.wangfanlab.whale.transport.v2.ErrorCodeV2
import com.wangfanlab.whale.transport.v2.IdsV2
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection 内置录制服务（contracts/v2 §12.2；**仅 root 变体注册
 * device.screen.record 能力**，见 [registerScreenRecordTo]）。
 *
 * - 前台服务（mediaProjection 类型，API 29+ 声明）+ 可见通知（whale_recording
 *   渠道）+ 通知「停止」动作（§12.2：录制期必须有可见状态与停止入口）；
 * - 编码管线：MediaCodec H.264（video/avc）+ MediaMuxer MP4，VirtualDisplay →
 *   codec inputSurface，drain 线程把编码输出写入 muxer；
 * - 遮罩：录制中周期轮询前台包名（root dumpsys，经 [Agent2Module.ForegroundProbe]），
 *   命中排除集合（control options.exclude_packages ∪ capture 模块
 *   [ExcludedPackagesStore]）→ **暂停写入**（遮罩期间编码帧在 drain 处整体丢弃，
 *   视频时间轴断档；选择「暂停写入」而非「黑帧覆盖」：实现简单、零像素泄露，
 *   不需要额外 EGL 渲染管线——黑帧覆盖需自建 EGL 向 inputSurface 渲染，复杂度
 *   与出错面都更高）；
 * - 授权：MediaProjection 需用户当次授权（createScreenCaptureIntent  consent），
 *   由 MainActivity 的 consent hook 获取后经 [RecordingConsent] 传入；
 *   无授权起录 → failed/permission_missing（诚实拒绝，不绕过）；
 * - 产出：filesDir/recordings/<run_id>.mp4 + <run_id>.manifest.json（§12.3
 *   RecordingManifest 的最小字段集），file_ref=recording:<run_id>/video。
 *
 * 线程模型：控制入口（onStartCommand/控制帧）在主线程转交 [RecordingController]
 * （其方法全部 @Synchronized）；编码 drain 在独立 HandlerThread。
 */
class RecordingService : Service() {

    /** drain/遮罩轮询工作线程。 */
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    /** 当前录制会话（codec/muxer/virtualDisplay/projection）。 */
    private var session: RecordSession? = null

    override fun onCreate() {
        super.onCreate()
        attachService(this)
        ensureChannel()
        workerThread = HandlerThread("whale-recording").also { it.start() }
        worker = Handler(workerThread!!.looper)
    }

    override fun onDestroy() {
        attachService(null)
        stopSession()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 通知「停止」入口：停止录制并退出前台
                controllerHandle("stop")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> return START_STICKY
        }
    }

    // ------------------------------------------------------------- 驱动实现（RecordingDriver）

    /**
     * MediaProjection 驱动（[RecordingController.RecordingDriver] 的生产实现）。
     * 全部方法在 worker 线程执行（经 [runOnWorker] 同步等待）。
     */
    private inner class ProjectionDriver : RecordingController.RecordingDriver {
        override fun start(runId: String, options: RecordingController.Options): String? {
            if (!IdsV2.RUN_ID.matches(runId)) return ErrorCodeV2.INVALID_MESSAGE
            val consent = RecordingConsent.take()
                ?: return ErrorCodeV2.PERMISSION_MISSING
            return runOnWorker<String?> { startSessionLocked(runId, options, consent) }
                ?: ErrorCodeV2.INTERNAL
        }

        override fun pause(): String? = runOnWorker {
            session?.let { it.paused = true }
            null
        } ?: ErrorCodeV2.UNAVAILABLE

        override fun resume(): String? = runOnWorker {
            session?.let { it.paused = false }
            null
        } ?: ErrorCodeV2.UNAVAILABLE

        override fun stop(): String? = runOnWorker {
            stopSession()
            null
        } ?: ErrorCodeV2.UNAVAILABLE

        override fun setMasked(masked: Boolean) {
            runOnWorkerAsync { session?.masked = masked }
        }
    }

    /** 同步等待 worker 执行（控制路径短操作；超时 10s 防死锁）。 */
    private fun <T> runOnWorker(block: () -> T): T? {
        val w = worker ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val box = arrayOfNulls<Any?>(1)
        w.post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                Log.e(TAG, "worker 执行异常", t)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            @Suppress("UNCHECKED_CAST")
            box[0] as T?
        } else {
            null
        }
    }

    private fun runOnWorkerAsync(block: () -> Unit) {
        worker?.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "worker 异步任务异常", t)
            }
        }
    }

    // ------------------------------------------------------------- 录制会话（worker 线程）

    private inner class RecordSession(
        val runId: String,
        val outFile: File,
        val codec: MediaCodec,
        val muxer: MediaMuxer,
        val projection: MediaProjection,
        val virtualDisplay: VirtualDisplay,
    ) {
        @Volatile var paused: Boolean = false
        @Volatile var masked: Boolean = false
        val draining = AtomicBoolean(true)
        var videoTrack = -1
        var muxerStarted = false
    }

    /** 起录（worker 线程）：codec → muxer → projection → virtualDisplay → drain。 */
    private fun startSessionLocked(
        runId: String,
        options: RecordingController.Options,
        consent: Pair<Int, Intent>,
    ): String? {
        if (session != null) return ErrorCodeV2.UNAVAILABLE
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val outFile = File(dir, "$runId.mp4")
        val w: Int
        val h: Int
        run {
            val m = resources.displayMetrics
            // 编码分辨率上限 1280x720（等比缩放，控制码率与文件体积）
            val scale = minOf(1f, 1280f / maxOf(m.widthPixels, m.heightPixels).toFloat())
            w = ((m.widthPixels * scale).toInt() / 2) * 2
            h = ((m.heightPixels * scale).toInt() / 2) * 2
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
                ?: return ErrorCodeV2.UNAVAILABLE
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 先 startForeground（mediaProjection 类型）再取 projection（API 34+ 硬要求）
            promoteForeground(runId)
            val projection = try {
                mpm.getMediaProjection(consent.first, consent.second)
            } catch (t: SecurityException) {
                // consent 被消费/失效：清掉并诚实回报缺授权
                RecordingConsent.clear()
                codec.stop(); codec.release(); muxer.release(); inputSurface.release()
                return ErrorCodeV2.PERMISSION_MISSING
            } ?: run {
                codec.stop(); codec.release(); muxer.release(); inputSurface.release()
                return ErrorCodeV2.UNAVAILABLE
            }
            val vd = projection.createVirtualDisplay(
                "whale-rec", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null,
            ) ?: run {
                codec.stop(); codec.release(); muxer.release(); inputSurface.release()
                projection.stop()
                return ErrorCodeV2.UNAVAILABLE
            }
            val s = RecordSession(runId, outFile, codec, muxer, projection, vd)
            session = s
            startDrainLoop(s, startedAt, options)
            startMaskLoop(s)
            Log.i(TAG, "录制已开始 run=$runId ${w}x$h → ${outFile.name}")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "起录失败", t)
            try {
                session?.let { stopSession() }
            } catch (_: Throwable) {
            }
            ErrorCodeV2.INTERNAL
        }
    }

    /** 编码 drain 循环（独立线程）：INFO 处理 + 帧写出；暂停/遮罩期间丢帧不写 muxer。 */
    private fun startDrainLoop(
        s: RecordSession,
        startedAt: Long,
        options: RecordingController.Options,
    ) {
        Thread({
            val info = MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            while (s.draining.get()) {
                val idx = try {
                    s.codec.dequeueOutputBuffer(info, 100_000)
                } catch (t: Throwable) {
                    Log.w(TAG, "dequeue 异常（停止 drain）: ${t.message}")
                    break
                }
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        s.videoTrack = s.muxer.addTrack(s.codec.outputFormat)
                        s.muxer.start()
                        s.muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = s.codec.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buf != null && info.size > 0 && !isEos &&
                            s.muxerStarted && !s.paused && !s.masked
                        ) {
                            // 暂停写入遮罩语义：丢弃的帧不进入 muxer（时间轴断档）；
                            // pts 归零基准以首帧为准，保证单调
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                            info.presentationTimeUs = (info.presentationTimeUs - firstPtsUs)
                                .coerceAtLeast(0)
                            s.muxer.writeSampleData(s.videoTrack, buf, info)
                        }
                        try {
                            s.codec.releaseOutputBuffer(idx, false)
                        } catch (t: Throwable) {
                            Log.w(TAG, "releaseOutputBuffer 异常: ${t.message}")
                        }
                        if (isEos) break
                        if (isConfig) continue
                    }
                }
            }
        }, "whale-rec-drain").apply { isDaemon = true }.start()
    }

    /** 遮罩轮询（1s 节拍）：前台包名 → controller.onForegroundPackage；max_duration 到期自动 stop。 */
    private fun startMaskLoop(s: RecordSession) {
        val probe = foregroundProbeProvider?.invoke() ?: return
        val tick = object : Runnable {
            override fun run() {
                val cur = session
                if (cur == null || cur.runId != s.runId) return
                val pkg = try {
                    probe.current()
                } catch (t: Throwable) {
                    null
                }
                controller?.onForegroundPackage(pkg)
                val report = controller?.tick()
                if (report != null && report.state in setOf("stopped", "failed")) return
                worker?.postDelayed(this, 1_000)
            }
        }
        worker?.postDelayed(tick, 1_000)
    }

    /** 停录并封口（worker 线程）：EOS → drain 收尾 → muxer stop → manifest 落盘。 */
    private fun stopSession() {
        val s = session ?: return
        session = null
        s.draining.set(false)
        try {
            s.virtualDisplay.release()
        } catch (_: Throwable) {
        }
        try {
            s.codec.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        // 给 drain 线程收尾窗口（EOS 帧写出）
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
        try {
            s.codec.stop()
        } catch (_: Throwable) {
        }
        try {
            s.codec.release()
        } catch (_: Throwable) {
        }
        try {
            if (s.muxerStarted) s.muxer.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "muxer.stop 失败（文件可能不完整）: ${t.message}")
        }
        try {
            s.muxer.release()
        } catch (_: Throwable) {
        }
        try {
            s.projection.stop()
        } catch (_: Throwable) {
        }
        writeManifest(s)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "录制已停止 run=${s.runId} 文件=${s.outFile.name}（${s.outFile.length()}B）")
    }

    /** §12.3 RecordingManifest 最小字段集（与视频同目录落盘）。 */
    private fun writeManifest(s: RecordSession) {
        try {
            val f = File(s.outFile.parentFile, "${s.runId}.manifest.json")
            f.writeText(
                "{" +
                    "\"run_id\":\"${s.runId}\"," +
                    "\"recorder\":\"media_projection\"," +
                    "\"video_ref\":\"${RecordingController.fileRefOf(s.runId)}\"," +
                    "\"size_bytes\":${s.outFile.length()}," +
                    "\"status\":\"completed\"" +
                    "}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "manifest 落盘失败: ${t.message}")
        }
    }

    // ------------------------------------------------------------- 前台通知（可见状态 + 停止入口）

    private fun promoteForeground(runId: String) {
        val notification = buildNotification(runId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "前台化失败（系统限制，不绕过）: ${t.message}")
        }
    }

    private fun buildNotification(runId: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
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
            .setContentTitle("Whale 正在录制屏幕")
            .setContentText("run=$runId；点「停止」立即结束录制")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Whale 屏幕录制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "MediaProjection 录制进行中提示（含停止入口）"
                setShowBadge(false)
            },
        )
    }

    // ------------------------------------------------------------- 控制接线

    /** 经进程内控制器处理一条 op（通知停止入口用；缺 run 时停当前活动 run）。 */
    private fun controllerHandle(op: String) {
        val c = controller ?: return
        val runId = c.activeRunId() ?: return
        c.handle(RecordingController.ControlRequest(runId, op, RecordingController.Options(null, null, emptySet())))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉任务：录制继续（前台服务语义），通知仍在，停止入口可用
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "WhaleRecording"
        private const val CHANNEL_ID = "whale_recording"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.wangfanlab.whale.modules.recording.action.STOP"
        private const val BIT_RATE = 2_000_000
        private const val FRAME_RATE = 15
        private const val I_FRAME_INTERVAL_S = 2

        /** 进程内控制器（由注册入口创建；服务/控制帧共用）。 */
        @Volatile
        var controller: RecordingController? = null
            private set

        /** 前台包名探测来源（录制遮罩用；root 版默认 dumpsys 探针）。 */
        @Volatile
        var foregroundProbeProvider: (() -> Agent2Module.ForegroundProbe)? = null

        /**
         * 注册 device.screen.record 能力（**仅 root 变体调用**；daily 构建不调用
         * 本方法即不创建 handler）。返回进程内控制器（供录制控制帧接线复用）。
         *
         * @param stateSender recording.state 上行口（= WssClientV2.sendRecordingState）
         * @param consentGate 授权门（无 consent 时直接 failed/permission_missing）
         */
        @Synchronized
        fun registerScreenRecordTo(
            context: Context,
            dispatcher: com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2,
            stateSender: RecordingController.StateSender,
            shell: Agent2Module.RootShell,
        ) {
            controller = com.wangfanlab.whale.transport.v2.retainAndBind(controller, create = {
                val app = context.applicationContext
                foregroundProbeProvider = { Agent2Module.ForegroundProbe.rootShell(shell) }
                val driver = object : RecordingController.RecordingDriver {
                    // 驱动委托到服务进程内实例；服务未运行时start会拉起服务
                    override fun start(runId: String, options: RecordingController.Options): String? {
                        if (!RecordingConsent.hasConsent()) return ErrorCodeV2.PERMISSION_MISSING
                        val svc = activeService
                            ?: run {
                                // 服务未运行：显式拉起（FGS 启动限制下可能失败，由服务侧诚实回报）
                                try {
                                    app.startForegroundService(Intent(app, RecordingService::class.java))
                                } catch (t: Throwable) {
                                    return ErrorCodeV2.BACKGROUND_RESTRICTED
                                }
                                // 等服务 onCreate 窗口（轮询 ≤2s）
                                val deadline = System.currentTimeMillis() + 2_000
                                while (activeService == null && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(50)
                                }
                                activeService ?: return ErrorCodeV2.UNAVAILABLE
                            }
                        return svc.ProjectionDriver().start(runId, options)
                    }

                    override fun pause(): String? =
                        activeService?.ProjectionDriver()?.pause() ?: ErrorCodeV2.UNAVAILABLE

                    override fun resume(): String? =
                        activeService?.ProjectionDriver()?.resume() ?: ErrorCodeV2.UNAVAILABLE

                    override fun stop(): String? =
                        activeService?.ProjectionDriver()?.stop() ?: ErrorCodeV2.UNAVAILABLE

                    override fun setMasked(masked: Boolean) {
                        activeService?.ProjectionDriver()?.setMasked(masked)
                    }
                }
                RecordingController(
                    driver = driver,
                    stateSender = stateSender,
                    // 遮罩排除集合：control options ∪ capture 模块用户排除包名
                    localExcludedProvider = { ExcludedPackagesStore(app).get() },
                )
            }, bind = { c ->
                dispatcher.registerHandler(
                    com.wangfanlab.whale.transport.v2.CapabilitiesV2.DEVICE_SCREEN_RECORD,
                    c.asActionHandler(),
                )
            })
            Log.i(TAG, "device.screen.record 已注册（MediaProjection 内置录制）")
        }

        /** 处理下行 recording.control 帧（WssClientV2.Listener.onRecordingControl 接线口）。 */
        fun handleControlFrame(payload: Map<String, Any?>): RecordingController.StateReport? {
            val c = controller ?: return null
            val req = c.parseControl(payload) ?: return null
            return c.handle(req)
        }

        /** 服务进程内实例（驱动委托目标）。 */
        @Volatile
        private var activeService: RecordingService? = null

        internal fun attachService(service: RecordingService?) {
            activeService = service
        }
    }

}

/**
 * MediaProjection 用户授权令牌（进程内持有）。
 *
 * consent 只能由前台 Activity 经 createScreenCaptureIntent 获得（MainActivity
 * consent hook 写入）；API 34+ 单个 consent token 只能消费一次，消费即清除。
 */
object RecordingConsent {
    /** MainActivity onActivityResult 请求码。 */
    const val REQUEST_CODE = 0x57A1

    /** MainActivity intent extra：请求发起录制授权弹窗。 */
    const val EXTRA_REQUEST_CONSENT = "whale_request_recording_consent"

    @Volatile
    private var consent: Pair<Int, Intent>? = null

    @Synchronized
    fun onResult(resultCode: Int, data: Intent?) {
        consent = if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            Pair(resultCode, data)
        } else {
            null
        }
    }

    fun hasConsent(): Boolean = consent != null

    /** 取出并清除（单次消费语义）。 */
    @Synchronized
    fun take(): Pair<Int, Intent>? {
        val c = consent
        consent = null
        return c
    }

    @Synchronized
    fun clear() {
        consent = null
    }
}
