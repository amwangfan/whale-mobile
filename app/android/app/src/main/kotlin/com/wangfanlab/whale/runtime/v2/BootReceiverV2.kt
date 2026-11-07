package com.wangfanlab.whale.runtime.v2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机恢复入口（BG-02，contracts/v2 面；接替 v1 runtime/BootReceiver）。
 *
 * BOOT_COMPLETED 是系统保护广播，进程被拉起时处于后台：
 * - 恢复决策经 [com.wangfanlab.whale.runtime.RecoveryPolicy]
 *   （曾连接 && 未暂停 && 未撤销，见 [NodeRuntimeV2.recoverIfNeeded]）；
 * - Android 15 起 dataSync 前台服务不允许从开机广播启动——
 *   [NodeServiceV2.start] 内部捕获降级为进程内连接，不绕过系统限制；
 * - 用户主动断开/一键暂停/设备撤销后，本接收器不做任何动作。
 */
class BootReceiverV2 : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED：评估 v2 连接恢复")
        // 进程可能是全新启动：先确保 v2 运行时存在，再按策略恢复。
        val runtime = NodeRuntimeV2.get(context)
        runtime.start()
        runtime.recoverIfNeeded("boot")
    }

    companion object {
        private const val TAG = "WhaleBootReceiverV2"
    }
}
