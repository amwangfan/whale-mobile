package com.wangfanlab.whale

import android.app.Application
import com.wangfanlab.whale.runtime.v2.NodeRuntimeV2

/**
 * 应用入口（BG-01）。
 *
 * 进程创建时即建立 [NodeRuntimeV2] 单例：v2 WSS 长连、设备身份、上行事件
 * 队列与各模块执行器的唯一所有者。Flutter Activity/Engine 的创建与销毁只影响
 * UI 绑定（TransportBridge/TransportBridgeV2 注册/注销监听），不决定传输层生命周期。
 *
 * v1 协议栈（contracts/v1 WssClient/NodeRuntime/NodeService/BootReceiver）已整体
 * 下线；设备身份与密钥材料来源不变（v2 运行时自建 DeviceIdentity）。
 */
class WhaleApp : Application() {

    /** contracts/v2 运行时（唯一节点运行时）。 */
    lateinit var nodeRuntimeV2: NodeRuntimeV2
        private set

    override fun onCreate() {
        super.onCreate()
        nodeRuntimeV2 = NodeRuntimeV2.get(this)
        nodeRuntimeV2.start()
        // BG-02：进程新建（含开机广播拉起）时按策略恢复连接；
        // 尊重一键暂停/用户断开/设备撤销，不擅自重连。
        nodeRuntimeV2.recoverIfNeeded("process_start")
        // 调试便利（仅 debug 包）：把设备身份落到应用私有文件，
        // 供无 logcat 的 OEM 固件（如 MagicOS）经 run-as 读取配对信息。
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            try {
                val f = java.io.File(filesDir, "identity_debug.json")
                f.writeText(
                    """{"device_id":"${nodeRuntimeV2.identity.getDeviceId()}",""" +
                        """"public_key_hex":"${nodeRuntimeV2.identity.getPublicKeyHex()}"}""",
                )
            } catch (_: Throwable) {
            }
        }
    }
}
