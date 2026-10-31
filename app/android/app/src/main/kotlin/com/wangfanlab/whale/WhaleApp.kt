package com.wangfanlab.whale

import android.app.Application
import com.wangfanlab.whale.runtime.NodeRuntime

/**
 * 应用入口（BG-01）。
 *
 * 进程创建时即建立 [NodeRuntime] 单例：WSS 长连、设备身份、离线队列与
 * 各模块执行器的唯一所有者。Flutter Activity/Engine 的创建与销毁只影响
 * UI 绑定（[com.wangfanlab.whale.transport.TransportBridge] 注册/注销监听），
 * 不再决定传输层生命周期。
 */
class WhaleApp : Application() {

    lateinit var nodeRuntime: NodeRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        nodeRuntime = NodeRuntime.get(this)
        nodeRuntime.start()
        // BG-02：进程新建（含开机广播拉起）时按策略恢复连接；
        // 尊重一键暂停/用户断开/设备撤销，不擅自重连。
        nodeRuntime.recoverIfNeeded("process_start")
    }
}
