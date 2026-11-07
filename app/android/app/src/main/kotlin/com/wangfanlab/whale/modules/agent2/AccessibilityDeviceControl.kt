package com.wangfanlab.whale.modules.agent2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.wangfanlab.whale.modules.capture.ShadowCaptureService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 无障碍动作后端。沿用 ShadowCaptureService 的系统绑定实例，
 * 不额外启动常驻服务，也不通过 localhost 暴露新的设备控制 API。
 *
 * 注意：只有在开始执行之前确认后端不可用，调用方才能选择其他后端。
 * 手势发出后超时或取消代表效果不明，绝不能自动在 Root 上重放。
 */
interface AccessibilityDeviceControl {
    enum class Status { SUCCEEDED, FAILED, UNKNOWN, UNAVAILABLE }
    data class Result(val status: Status, val reason: String = "")

    fun available(): Boolean
    fun dumpUiTree(): String?
    fun execute(capability: String, args: Map<String, Any?>, point: Pair<Int, Int>?): Result
    fun foregroundPackage(): String?
    /** 非 Root 下仅列出 Android 允许查询的 launcher 应用，不宣称是完整安装清单。 */
    fun listLaunchableApps(filter: String): List<String>?
}

class AndroidAccessibilityDeviceControl(private val context: Context) : AccessibilityDeviceControl {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun available(): Boolean = ShadowCaptureService.serviceInstance != null

    override fun foregroundPackage(): String? =
        ShadowCaptureService.serviceInstance?.observedForegroundPackage

    override fun dumpUiTree(): String? {
        val svc = ShadowCaptureService.serviceInstance ?: return null
        return onMain {
            val root = svc.rootInActiveWindow ?: return@onMain null
            val xml = StringBuilder(16 * 1024)
            xml.append("<hierarchy rotation=\"0\">")
            var count = 0
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_DEPTH || count >= MAX_NODES) return
                count++
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                xml.append("<node")
                attribute(xml, "class", node.className?.toString() ?: "")
                attribute(xml, "resource-id", node.viewIdResourceName ?: "")
                attribute(xml, "content-desc", node.contentDescription?.toString() ?: "")
                attribute(xml, "text", node.text?.toString()?.take(Agent2Logic.MAX_ELEMENT_TEXT_CHARS) ?: "")
                attribute(xml, "bounds", "[" + bounds.left + "," + bounds.top +
                    "][" + bounds.right + "," + bounds.bottom + "]")
                attribute(xml, "clickable", node.isClickable.toString())
                attribute(xml, "scrollable", node.isScrollable.toString())
                xml.append('>')
                val n = minOf(node.childCount, MAX_CHILDREN)
                for (i in 0 until n) visit(node.getChild(i), depth + 1)
                xml.append("</node>")
            }
            visit(root, 0)
            xml.append("</hierarchy>")
            xml.toString()
        }
    }

    override fun execute(
        capability: String,
        args: Map<String, Any?>,
        point: Pair<Int, Int>?,
    ): AccessibilityDeviceControl.Result {
        val svc = ShadowCaptureService.serviceInstance
            ?: return result(AccessibilityDeviceControl.Status.UNAVAILABLE, "无障碍服务未连接")
        return when (capability) {
            "device.tap", "device.long_press", "device.swipe" -> {
                val p = point ?: return result(AccessibilityDeviceControl.Status.FAILED, "缺少坐标")
                val duration = when (capability) {
                    "device.tap" -> 60L
                    "device.long_press" -> (args["duration_ms"] as? Number)?.toLong() ?: 800L
                    else -> (args["duration_ms"] as? Number)?.toLong() ?: 300L
                }.coerceAtLeast(50L)
                val path = Path().apply {
                    moveTo(p.first.toFloat(), p.second.toFloat())
                    if (capability == "device.swipe") {
                        lineTo((args["x2"] as Number).toFloat(), (args["y2"] as Number).toFloat())
                    }
                }
                gesture(svc, path, duration)
            }
            "device.key" -> {
                val action = when ((args["key"] as String).trim().uppercase()) {
                    "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    else -> return result(
                        AccessibilityDeviceControl.Status.UNAVAILABLE,
                        "该按键需 Root/ADB 后端",
                    )
                }
                val ok = onMain { svc.performGlobalAction(action) }
                when (ok) {
                    true -> result(AccessibilityDeviceControl.Status.SUCCEEDED, "已发送全局按键动作")
                    false -> result(AccessibilityDeviceControl.Status.FAILED, "系统拒绝全局按键动作")
                    null -> result(AccessibilityDeviceControl.Status.UNKNOWN, "按键回调超时")
                }
            }
            "device.input_text" -> {
                val text = args["text"] as? String
                    ?: return result(AccessibilityDeviceControl.Status.FAILED, "缺少文字")
                val ok = onMain {
                    val root = svc.rootInActiveWindow ?: return@onMain false
                    val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (target?.isEditable != true) return@onMain false
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
                when (ok) {
                    true -> result(AccessibilityDeviceControl.Status.SUCCEEDED, "已设置焦点输入框内容")
                    false -> result(AccessibilityDeviceControl.Status.FAILED, "未找到可编辑的焦点输入框")
                    null -> result(AccessibilityDeviceControl.Status.UNKNOWN, "输入动作状态不明")
                }
            }
            "device.app.launch" -> {
                val pkg = (args["package"] as? String)?.trim()
                    ?: return result(AccessibilityDeviceControl.Status.FAILED, "缺少包名")
                val ok = onMain {
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@onMain false
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    true
                }
                when (ok) {
                    true -> result(AccessibilityDeviceControl.Status.SUCCEEDED, "已请求系统启动应用")
                    false -> result(AccessibilityDeviceControl.Status.FAILED, "无法获取启动入口")
                    null -> result(AccessibilityDeviceControl.Status.UNKNOWN, "启动结果不明")
                }
            }
            else -> result(AccessibilityDeviceControl.Status.UNAVAILABLE, "无障碍后端不支持该动作")
        }
    }

    override fun listLaunchableApps(filter: String): List<String>? {
        // 不依赖无障碍节点读权限；Android 11+ 需要 manifest <queries> 声明 launcher 查询。
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
                .filter { info ->
                    val isSystem = (info.activityInfo?.applicationInfo?.flags ?: 0) and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                    filter == "all" || (filter == "system" && isSystem) ||
                        (filter == "third_party" && !isSystem)
                }
                .mapNotNull { it.activityInfo?.packageName }
                .distinct().sorted()
        } catch (_: Exception) {
            null
        }
    }

    private fun gesture(
        svc: ShadowCaptureService,
        path: Path,
        durationMs: Long,
    ): AccessibilityDeviceControl.Result {
        val completed = CountDownLatch(1)
        val state = AtomicReference<AccessibilityDeviceControl.Status>()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                state.set(AccessibilityDeviceControl.Status.SUCCEEDED)
                completed.countDown()
            }
            override fun onCancelled(description: GestureDescription?) {
                // 可能已经执行了部分动作；不可自动重放。
                state.set(AccessibilityDeviceControl.Status.UNKNOWN)
                completed.countDown()
            }
        }
        val submitted = onMain {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            svc.dispatchGesture(gesture, callback, mainHandler)
        }
        if (submitted == false) return result(
            AccessibilityDeviceControl.Status.FAILED, "系统拒绝手势",
        )
        if (submitted == null) return result(
            AccessibilityDeviceControl.Status.UNKNOWN, "发送手势超时",
        )
        return if (completed.await(durationMs + 4_000L, TimeUnit.MILLISECONDS)) {
            result(state.get() ?: AccessibilityDeviceControl.Status.UNKNOWN)
        } else {
            result(AccessibilityDeviceControl.Status.UNKNOWN, "等待手势执行结果超时")
        }
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (_: Exception) { null }
        }
        val done = CountDownLatch(1)
        val output = AtomicReference<T?>()
        if (!mainHandler.post {
                try { output.set(block()) } catch (_: Exception) { /* report unknown */ }
                finally { done.countDown() }
            }
        ) return null
        if (!done.await(3, TimeUnit.SECONDS)) return null
        return output.get()
    }

    private fun attribute(sb: StringBuilder, name: String, value: String) {
        sb.append(' ').append(name).append("=\"")
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("&apos;")
                else -> if (c >= ' ' || c == '\t' || c == '\n') sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun result(
        status: AccessibilityDeviceControl.Status,
        reason: String = "",
    ) = AccessibilityDeviceControl.Result(status, reason)

    companion object {
        private const val MAX_NODES = 400
        private const val MAX_CHILDREN = 80
        private const val MAX_DEPTH = 36
    }
}
