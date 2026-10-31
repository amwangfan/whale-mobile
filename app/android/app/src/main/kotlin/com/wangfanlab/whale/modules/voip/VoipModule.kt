package com.wangfanlab.whale.modules.voip

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.wangfanlab.whale.runtime.NodeRuntime
import com.wangfanlab.whale.transport.WssClient
import com.wangfanlab.whale.voip.BridgePrefs
import com.wangfanlab.whale.voip.CallAccountHelper
import com.wangfanlab.whale.voip.FakeConnectionService
import com.wangfanlab.whale.voip.FileLog

/**
 * VoIP 模块（阶段二 B4 前半：信令整合，媒体仍走独立 PCM 桥 AudioBridge）。
 *
 * - 注册为 `phone.call.ring` 的 [WssClient.ActionExecutor]：
 *   args{number,name,bridge_host,bridge_port} → 端侧校验链（[RingDecider]）→
 *   [CallAccountHelper.ring] → reply 终态（executed=响铃成功 / failed）。
 * - 消费下行 call.invite（schema payload 仅 session_id/media_params/expires_at；
 *   号码/桥参数不经信令，走端侧 [BridgePrefs] 配置）与 call.hangup（远端挂断）。
 * - 来电状态经 [FakeConnectionService.CallStateListener] 钩子回传：
 *   ringing → sensor.event kind=call.state；接听 → kind=call.answered；
 *   挂断 → kind=call.ended（含 duration_sec）。privacy_class=owner_private，
 *   号码在 summary 中脱敏（仅尾号）。
 *
 * 模块开关 whale_modules/voip_enabled **默认 false**（首次需显式开启）。
 */
class VoipModule(
    context: Context,
    /** 上行事件出口（TransportBridge 注入 → WssClient.sendSensorEvent，离线入队）。 */
    private val eventSender: (CallEvent) -> Unit = {},
    private val decider: RingDecider = RingDecider(
        moduleEnabled = { isEnabled(context.applicationContext) },
        permissionGranted = { permissionGranted(context.applicationContext) },
        allPaused = { NodeRuntime.isAllPaused(context.applicationContext) },
        defaultBridgeHost = { BridgePrefs.getHost(context.applicationContext) },
        defaultBridgePort = { BridgePrefs.getPort(context.applicationContext) },
    ),
    /** 响铃执行出口（测试/替换用）；默认走 Telecom 自管来电。 */
    private val ringer: (RingSpec) -> Unit = { spec ->
        CallAccountHelper.ring(
            context.applicationContext, spec.number, spec.name,
            spec.bridgeHost, spec.bridgePort,
        )
    },
) : WssClient.ActionExecutor {

    private val appContext = context.applicationContext
    private val tracker = CallSessionTracker()

    init {
        // FakeConnectionService 状态钩子：Telecom 回调线程不定，eventSender 内部线程安全。
        FakeConnectionService.sCallStateListener =
            FakeConnectionService.CallStateListener { state, number, name, reason ->
                val event = when (state) {
                    "ringing" -> tracker.onRinging(number, name)
                    "active" -> tracker.onActive()
                    "ended" -> tracker.onEnded(reason ?: "local")
                    else -> null
                }
                if (event != null) {
                    FileLog.i("call event → ${event.kind} ${event.summary}")
                    eventSender(event)
                }
            }
    }

    // ------------------------------------------------------------ ActionExecutor

    /** WssClient executor 线程调用；同步执行，经 reply 回终态（executed/failed）。 */
    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?>
        when (val decision = decider.onRequest(args)) {
            is RingDecision.Reject -> {
                Log.w(TAG, "phone.call.ring 校验未通过: ${decision.errorCode}")
                reply("failed", decision.errorCode, null)
            }

            is RingDecision.Execute -> try {
                tracker.bindSession(null) // action 触发的响铃无 call.invite 会话
                ringer(decision.spec)
                reply("executed", null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "phone.call.ring 执行异常", t)
                FileLog.e("ring execute", t)
                reply("failed", "internal", null)
            }
        }
    }

    // ------------------------------------------------------------ call.* 信令消费

    /**
     * 下行 call.invite（主线程，WssClient.Listener 分发）。校验媒体参数/有效期；
     * 模块开关关或全局暂停 → 忽略并记日志（invite 无 receipt 通道，失败经
     * sensor.event call.state state=failed 上行）。
     */
    fun onCallInvite(payload: Map<String, Any?>) {
        when (val decision = CallInviteLogic.validate(payload, System.currentTimeMillis())) {
            is InviteDecision.Ignore ->
                Log.i(TAG, "call.invite 忽略: ${decision.reason}")

            is InviteDecision.Accept -> {
                if (!isEnabled(appContext) || NodeRuntime.isAllPaused(appContext)) {
                    Log.w(TAG, "call.invite 到达但模块停用/全局暂停，不响铃")
                    emitRaw(
                        "call.state",
                        "state=failed session=${decision.sessionId} reason=unavailable",
                    )
                    return
                }
                if (!permissionGranted(appContext)) {
                    Log.w(TAG, "call.invite 到达但权限缺失，不响铃")
                    emitRaw(
                        "call.state",
                        "state=failed session=${decision.sessionId} reason=permission_missing",
                    )
                    return
                }
                Log.i(TAG, "call.invite 接受 session=${decision.sessionId}，触发响铃")
                tracker.bindSession(decision.sessionId)
                try {
                    // schema 不携带号码/桥参数：显示名占位，桥走端侧配置（§4/§7）。
                    ringer(
                        RingSpec(
                            number = "***",
                            name = "Whale AI",
                            bridgeHost = BridgePrefs.getHost(appContext),
                            bridgePort = BridgePrefs.getPort(appContext),
                        )
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "call.invite 响铃失败", t)
                    FileLog.e("invite ring", t)
                    emitRaw(
                        "call.state",
                        "state=failed session=${decision.sessionId} reason=internal",
                    )
                }
            }
        }
    }

    /** 下行 call.hangup（主线程）：远端结束会话 → 挂断本地连接（ended 事件经钩子回传）。 */
    fun onCallHangup(payload: Map<String, Any?>) {
        val sessionId = payload["session_id"] as? String ?: return
        Log.i(TAG, "call.hangup session=$sessionId → hangupAll")
        try {
            FakeConnectionService.hangupAll()
        } catch (t: Throwable) {
            Log.w(TAG, "call.hangup 执行异常", t)
        }
    }

    private fun emitRaw(kind: String, summary: String) {
        eventSender(CallEvent(kind = kind, summary = summary))
    }

    companion object {
        private const val TAG = "WhaleVoipModule"
        const val CAPABILITY_RING = "phone.call.ring"

        /** 模块开关存储（与 TransportBridge 通用模块开关共用 whale_modules）。 */
        const val PREFS_NAME = "whale_modules"
        const val KEY_ENABLED = "voip_enabled"

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 宿主默认关闭（B4 默认不启用，首次需用户在模块卡显式打开）。 */
        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /**
         * 端侧权限门：RECORD_AUDIO（运行时，PCM 桥采音）+ MANAGE_OWN_CALLS
         * （声明即授予，自管 ConnectionService 前提）。READ_PHONE_STATE 仅
         * 部分 Telecom API 需要，不作硬门。
         */
        fun permissionGranted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) ==
                PackageManager.PERMISSION_GRANTED

        /** UI 展示用权限状态。 */
        fun permissionStatus(context: Context): String =
            if (permissionGranted(context)) "granted" else "denied"

        /**
         * 发起运行时权限申请（RECORD_AUDIO + READ_PHONE_STATE；fire-and-forget，
         * 与 NotificationModule.requestPermission 同模式）。MANAGE_OWN_CALLS 为
         * normal 级无需申请。
         */
        fun requestPermissions(activity: Activity?, context: Context): String {
            val status = permissionStatus(context)
            if (status != "granted" && activity != null) {
                try {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_PHONE_STATE,
                        ),
                        REQUEST_CODE_VOIP_PERMISSIONS,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "权限申请发起失败", t)
                }
            }
            return status
        }

        private const val REQUEST_CODE_VOIP_PERMISSIONS = 0x564F // 'VO'
    }
}
