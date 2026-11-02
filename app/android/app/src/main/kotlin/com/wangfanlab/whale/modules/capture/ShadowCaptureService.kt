package com.wangfanlab.whale.modules.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.wangfanlab.whale.runtime.NodeRuntime

/**
 * 影子采集服务（SM-01，Issue #8）。
 *
 * AccessibilityService.takeScreenshot（API 30+）链路参照 ScreenMemo 已验证实现
 * （署名见 README 致谢），但按 Whale 隐私/模块体系重写：
 * - **影子模式**：截屏只用于计算 dHash 与统计（成功率/延迟/重复率/前台包名），
 *   位图立即回收，**不落库、不 OCR、不出设备**；
 * - 采集互斥：检测到独立 ScreenMemo 采集服务在运行（其无障碍服务已启用）时
 *   自动跳过（conflict_standalone_screenmemo），同一时刻只许一套采集；
 * - 隐私护栏：锁屏/输入法/用户排除包名/一键暂停/模块开关全部前置拦截；
 * - 生命周期归系统（无障碍服务独立绑定），运行状态经 companion 观测，
 *   模块开关变化即停即走。
 */
class ShadowCaptureService : AccessibilityService() {

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var tickRunnable: Runnable? = null
    private var lastHash: Long? = null
    private var lastCaptureMs: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // 只监听窗口切换（驱动 tick 重置），不读任何节点内容
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        workerThread = HandlerThread("whale-capture").also { it.start() }
        worker = Handler(workerThread!!.looper)
        startTicking()
    }

    /** 最近前台包名（来自窗口切换事件；不取节点内容，canRetrieveWindowContent=false 也可用）。 */
    @Volatile
    private var foregroundPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 窗口切换事件携带前台包名（event.packageName 不需要内容读取权限）；
        // 截屏由周期 tick 驱动（与 ScreenMemo 一致）。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) foregroundPkg = pkg
        }
    }

    override fun onInterrupt() = Unit

    private fun stopTicking() {
        tickRunnable?.let { worker?.removeCallbacks(it) }
        tickRunnable = null
    }

    override fun onDestroy() {
        stopTicking()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- 周期 tick

    private fun startTicking() {
        val r = object : Runnable {
            override fun run() {
                try {
                    tickOnce()
                } catch (t: Throwable) {
                    // 单帧异常不允许杀死 worker 线程（采集使命长期存活）
                    CaptureStats.noteFailure(-99)
                    android.util.Log.w("WhaleCapture", "tick 异常（已吞没，继续运行）", t)
                }
                worker?.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = r
        worker?.postDelayed(r, TICK_MS)
    }

    private fun tickOnce() {
        val stats = CaptureStats
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val locked = try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isDeviceLocked || km.isKeyguardLocked || pm?.isInteractive == false
        } catch (t: Throwable) {
            false
        }
        val foreground = currentForegroundPackage()
        val decision = CaptureDecider.shouldCapture(
            moduleEnabled = CaptureModule.isEnabled(this),
            allPaused = NodeRuntime.isAllPaused(this),
            conflictWithStandalone = CaptureModule.standaloneScreenMemoActive(this),
            deviceLocked = locked,
            foregroundPackage = foreground,
            excludedPackages = CaptureModule.excludedPackages(this),
            nowMs = System.currentTimeMillis(),
            lastCaptureMs = lastCaptureMs,
        )
        when (decision) {
            is CaptureDecider.Decision.Skip -> stats.noteSkip(decision.reason)
            CaptureDecider.Decision.Capture -> takeOneShot(foreground!!)
        }
        // 每分钟一次统计心跳（诊断可观测；不含内容数据）
        tickCount++
        if (tickCount % 60 == 0L) {
            android.util.Log.i(
                "WhaleCapture",
                "影子统计 ${CaptureStats.snapshot()}",
            )
            // OEM 无 logcat 固件（如 MagicOS）观测通道：仅 debug 包落私有文件
            try {
                val dbg = (applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (dbg) {
                    val st = CaptureStats.snapshot()
                    val json = st.entries.joinToString(",", "{", "}") { (k, v) ->
                        val key = "\"" + k + "\""
                        if (v is String) key + ":\"" + v + "\"" else "$key:$v"
                    }
                    java.io.File(filesDir, "capture_stats.json").writeText(json)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private var tickCount = 0L

    private fun currentForegroundPackage(): String? = foregroundPkg

    // ------------------------------------------------------------- 截屏（API 30+）

    private fun takeOneShot(targetPackage: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 低版本明确降级：不崩溃、不降级到 Root/反射，只计数并跳过
            CaptureStats.noteSkip("api_below_30")
            return
        }
        lastCaptureMs = System.currentTimeMillis()
        val exec = java.util.concurrent.Executor { r -> worker?.post(r) }
        CaptureStats.noteAttempt()
        val persist = CaptureModule.isPersistEnabled(this)
        ShotTaker30.take(this, exec, keepBitmap = persist) { hash, latencyMs, errorCode, bitmap ->
            when {
                hash != null -> {
                    val duplicate = CaptureDecider.isDuplicate(lastHash, hash)
                    if (!duplicate) {
                        lastHash = hash
                        CaptureStats.noteUnique(latencyMs)
                        // SM-02：非影子模式（persist 开启）→ 存图 + OCR + 索引
                        if (persist && bitmap != null) {
                            val rowId = com.wangfanlab.whale.runtime.NodeRuntime.peek()
                                ?.capturePipeline?.process(
                                    bitmap = bitmap,
                                    dhash = hash,
                                    packageName = targetPackage,
                                    appLabel = targetPackage,
                                    captureTimeMs = System.currentTimeMillis(),
                                ) ?: -1
                            CaptureStats.notePersisted(rowId >= 0)
                        }
                    } else {
                        CaptureStats.noteDuplicate(latencyMs)
                    }
                }
                else -> CaptureStats.noteFailure(errorCode ?: -1)
            }
            bitmap?.recycle() // 管线用完/影子未用：统一在此回收
        }
    }

    companion object {
        private const val TICK_MS = 1_000L // tick 频率；采集间隔由 CaptureDecider.intervalMs 控制

        @Volatile
        var serviceInstance: ShadowCaptureService? = null
            private set

        val isRunning: Boolean get() = serviceInstance != null
    }
}

/**
 * 影子采集统计（内存环形计数 + 最近状态；不含任何内容数据，可安全展示/上报）。
 */
object CaptureStats {
    @Volatile
    var attempts: Long = 0
        private set

    @Volatile
    var unique: Long = 0
        private set

    @Volatile
    var duplicates: Long = 0
        private set

    @Volatile
    var failures: Long = 0
        private set

    @Volatile
    var lastLatencyMs: Long = 0
        private set

    @Volatile
    var lastSkipReason: String? = null
        private set

    @Volatile
    var skipsByReason: Map<String, Long> = emptyMap()
        private set

    @Synchronized
    fun noteAttempt() {
        attempts++
    }

    @Synchronized
    fun noteUnique(latencyMs: Long) {
        unique++
        lastLatencyMs = latencyMs
    }

    @Synchronized
    fun noteDuplicate(latencyMs: Long) {
        duplicates++
        lastLatencyMs = latencyMs
    }

    @Synchronized
    fun noteFailure(code: Int) {
        failures++
    }

    @Volatile
    var persisted: Long = 0
        private set

    @Synchronized
    fun notePersisted(ok: Boolean) {
        if (ok) persisted++
    }

    @Synchronized
    fun noteSkip(reason: String) {
        lastSkipReason = reason
        skipsByReason = skipsByReason.toMutableMap().apply {
            put(reason, (get(reason) ?: 0L) + 1L)
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "attempts" to attempts,
        "unique" to unique,
        "duplicates" to duplicates,
        "failures" to failures,
        "last_latency_ms" to lastLatencyMs,
        "last_skip_reason" to lastSkipReason,
        "skips_by_reason" to skipsByReason,
        "persisted" to persisted,
    )

    @Synchronized
    fun reset() {
        attempts = 0
        unique = 0
        duplicates = 0
        failures = 0
        lastLatencyMs = 0
        lastSkipReason = null
        skipsByReason = emptyMap()
    }
}
