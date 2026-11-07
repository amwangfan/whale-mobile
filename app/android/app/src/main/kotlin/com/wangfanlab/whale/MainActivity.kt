package com.wangfanlab.whale

import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.wangfanlab.whale.modules.recording.RecordingConsent
import com.wangfanlab.whale.transport.TransportBridge
import com.wangfanlab.whale.transport.v2.TransportBridgeV2
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    /** `whale/transport` 桥（v1 栈下线后的瘦身版：仅本地数据查询面 + 模块配置面）。 */
    private var transportBridge: TransportBridge? = null

    /** contracts/v2 桥（`whale/transport_v2`；唯一控制面通道）。 */
    private var transportBridgeV2: TransportBridgeV2? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        transportBridge = TransportBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        transportBridgeV2 = TransportBridgeV2(this, flutterEngine.dartExecutor.binaryMessenger)
        // contracts/v2 §12.2（必要集成）：冷启动 intent 携带录制授权请求时发起 consent
        maybeRequestRecordingConsent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 热启动：通知点击深链 extras 落到新 intent；
        // 更新 activity.intent 供桥层 getLaunchRoute 读取并消费。
        setIntent(intent)
        // contracts/v2 §12.2（必要集成）：热启动 intent 携带录制授权请求
        maybeRequestRecordingConsent(intent)
    }

    /**
     * MediaProjection consent hook（contracts/v2 §12.2 必要集成，非 Flutter 改动）：
     * intent 携带 [RecordingConsent.EXTRA_REQUEST_CONSENT] 时弹系统录屏授权；
     * 结果经 [RecordingConsent.onResult] 交给 RecordingService 消费（单次有效）。
     */
    private fun maybeRequestRecordingConsent(intent: Intent?) {
        if (intent?.getBooleanExtra(RecordingConsent.EXTRA_REQUEST_CONSENT, false) != true) return
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), RecordingConsent.REQUEST_CODE)
        } catch (t: Throwable) {
            android.util.Log.w("WhaleMain", "录制授权弹窗发起失败: ${t.message}")
        }
    }

    @Deprecated("startActivityForResult 配套回调（MediaProjection consent 仅支持此 API）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RecordingConsent.REQUEST_CODE) {
            RecordingConsent.onResult(resultCode, data)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        transportBridgeV2?.dispose()
        transportBridgeV2 = null
        transportBridge?.dispose()
        transportBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
