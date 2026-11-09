package com.wangfanlab.whale.modules.recording

import android.content.Context
import android.util.Log
import com.wangfanlab.whale.CapabilityRegistry
import com.wangfanlab.whale.modules.agent2.Agent2Module
import com.wangfanlab.whale.modules.agent.AgentShell
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2
import com.wangfanlab.whale.transport.v2.IdentityRegistration
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * recording 模块的进程级装配入口（供 NodeRuntimeV2 反射探测装配，约定签名
 * `registerInto(dispatcher, context)`）。
 *
 * - **仅 root 变体注册** device.screen.record（daily 构建调用本方法为 no-op，
 *   不创建 handler——分发器 fail-closed 回 failed/unavailable，§5.2/§16）；
 * - recording.state 上行口由 [attachStateSender] 注入（= WssClientV2.
 *   sendRecordingState；接线前产生的状态回报进 [pendingStates] 缓冲，接线后补发，
 *   保证状态不错过）；
 * - 下行 recording.control 帧经 [handleControlFrame] 进入
 *   （NodeRuntimeV2.onRecordingControl 接线口）。
 */
object RecordingRuntime {

    private val registration = IdentityRegistration<ReceiptDispatcherV2>()

    @Volatile
    private var controller: RecordingController? = null

    /** 接线后的真实上行口；未接线时回报进缓冲。 */
    @Volatile
    private var stateSender: RecordingController.StateSender? = null

    /** 接线前的状态回报缓冲（有界 32 条，防离线期无限堆积）。 */
    private val pendingStates = ConcurrentLinkedQueue<RecordingController.StateReport>()

    /**
     * 装配入口（反射约定签名）。root 变体注册 device.screen.record handler 并
     * 创建进程内 [RecordingController]；daily 变体安静跳过。
     */
    @Synchronized
    fun registerInto(dispatcher: ReceiptDispatcherV2, context: Context) {
        registration.ensure(dispatcher) {
            if (!CapabilityRegistry.isRootEdition()) {
                Log.i(TAG, "daily 构建：不注册 device.screen.record（§5.2 门控）")
                return@ensure
            }
            val app = context.applicationContext
            val shell = Agent2Module.RootShell.fromAgentShell(
                AgentShell(File(app.filesDir, "agent2/out")),
            )
            RecordingService.registerScreenRecordTo(
                context = app,
                dispatcher = dispatcher,
                stateSender = RecordingController.StateSender { runId, state, fileRef, durationMs, errorCode ->
                    dispatchState(RecordingController.StateReport(runId, state, fileRef, durationMs, errorCode))
                },
                shell = shell,
            )
            controller = RecordingService.controller
            Log.i(TAG, "recording 模块已装配（root 变体）")
        }
    }

    /** 注入 recording.state 上行口（WssClientV2.sendRecordingState 的方法引用）。 */
    @Synchronized
    fun attachStateSender(sender: RecordingController.StateSender) {
        stateSender = sender
        // 补发接线前缓冲的状态回报
        while (true) {
            val r = pendingStates.poll() ?: break
            try {
                sender.send(r.runId, r.state, r.fileRef, r.durationMs, r.errorCode)
            } catch (t: Throwable) {
                Log.w(TAG, "补发 recording.state 失败: ${t.message}")
            }
        }
    }

    private fun dispatchState(report: RecordingController.StateReport) {
        val s = stateSender
        if (s != null) {
            try {
                s.send(report.runId, report.state, report.fileRef, report.durationMs, report.errorCode)
            } catch (t: Throwable) {
                Log.w(TAG, "recording.state 上行失败: ${t.message}")
            }
        } else {
            pendingStates.add(report)
            while (pendingStates.size > 32) pendingStates.poll()
        }
    }

    /**
     * 下行 recording.control 帧入口（NodeRuntimeV2.onRecordingControl 接线调用）。
     * 结构非法/未装配时返回 null（调用方可回 error.invalid_message 或忽略）。
     */
    fun handleControlFrame(payload: Map<String, Any?>): RecordingController.StateReport? {
        val c = controller ?: run {
            Log.w(TAG, "recording.control 到达但模块未装配（daily?），忽略")
            return null
        }
        val req = c.parseControl(payload) ?: run {
            Log.w(TAG, "recording.control 结构非法: ${payload.keys}")
            return null
        }
        return c.handle(req)
    }

    /** 当前控制器（诊断/测试）。 */
    fun currentController(): RecordingController? = controller

    /** 仅供测试重置。 */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun resetForTesting() {
        registration.clear()
        controller = null
        stateSender = null
        pendingStates.clear()
    }

    private const val TAG = "WhaleRecRuntime"
}
