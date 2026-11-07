package com.wangfanlab.whale.modules.agent2

import android.content.Context
import android.util.Log
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2

/**
 * agent2 模块的进程级装配入口（供 NodeRuntimeV2 反射探测装配，约定签名
 * `registerInto(dispatcher, context)`；也供未来桥层直接调用）。
 *
 * - 幂等：重复调用只注册一次 handler；
 * - daily/root 差异化由 [Agent2Module] 内部按 CapabilityRegistry 门控；
 * - [readOutput] 供 sensor.query device.output.read 路由转发（§10 引用白名单）。
 */
object Agent2Runtime {

    @Volatile
    private var module: Agent2Module? = null

    @Volatile
    private var registered = false

    /** 装配入口（反射约定签名）。 */
    @Synchronized
    fun registerInto(dispatcher: ReceiptDispatcherV2, context: Context) {
        if (registered) return
        val m = module ?: Agent2Module(context.applicationContext).also { module = it }
        m.registerTo(dispatcher)
        registered = true
        Log.i(TAG, "agent2 动作族执行器已装配")
    }

    /** device.output.read 拉取（未装配时返回 bad_ref）。 */
    fun readOutput(ref: String): Map<String, Any?> =
        module?.readOutput(ref) ?: mapOf("error" to "bad_ref")

    /** 仅供测试重置。 */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun resetForTesting() {
        module = null
        registered = false
    }

    private const val TAG = "WhaleAgent2Runtime"
}
