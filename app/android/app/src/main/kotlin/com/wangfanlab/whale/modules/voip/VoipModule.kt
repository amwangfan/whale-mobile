package com.wangfanlab.whale.modules.voip

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.transport.v2.LegacyActionExecutor
import com.wangfanlab.whale.voip.BridgePrefs
import com.wangfanlab.whale.voip.CallAccountHelper
import com.wangfanlab.whale.voip.FakeConnectionService
import com.wangfanlab.whale.voip.FileLog

/**
 * VoIP 模块（阶段二 B4 前半：信令整合，媒体仍走独立 PCM 桥 AudioBridge）。
 *
 * - 注册为 `phone.call.ring` 的 [LegacyActionExecutor]（经 NodeRuntimeV2 适配入 v2 分发器）：
 *   args{number,name,bridge_host,bridge_port} → 端侧校验链（[RingDecider]）→
 *   [CallAccountHelper.ring] → reply 终态（executed=响铃成功 / failed）。
 * - 消费下行 call.invite（schema payload 仅 session_id/media_params/expires_at/name?；
 *   号码/桥参数不经信令，走端侧 [BridgePrefs] 配置）与 call.hangup（远端挂断）。
 * - 契约会话（invite 绑定）状态走 call.* 信封上行（[CallSessionFlow]→signalSender）：
 *   接听 → call.answer{accepted=true}+call.state active；拒接 → answer{false}+state ended；
 *   本地挂断 → hangup normal+state ended；远端挂断 → 仅 state ended（不回声）；
 *   振铃窗 60s 超时 → hangup timeout+state failed；媒体失败 → hangup error+state failed。
 *   **防双轨**：会话态只走信封轨；experience.event 感知轨（tracker）仅服务无会话的
 *   action 响铃。媒体诊断事件 kind=call.media（不占用 call.state 词）。
 * - 呼出触发（呼出加回·契约定案 A）：[requestOutgoingCall] 置 pendingOutgoing
 *   （60s 窗）并经 triggerSender 上行呼叫请求（experience.event kind=call.request；
 *   契约无 call.dial，云端订阅该事件后回 call.invite）；窗内到达的 invite 走自动接听分支。
 * - 无会话的来电状态经 [FakeConnectionService.CallStateListener] 钩子回传：
 *   ringing → sensor.event kind=call.state；接听 → kind=call.answered；
 *   挂断 → kind=call.ended（含 duration_sec）。privacy_class=owner_private，
 *   号码在 summary 中脱敏（仅尾号）。
 *
 * 模块开关 whale_modules/voip_enabled **默认 false**（首次需显式开启）。
 */
class VoipModule(
    context: Context,
    /** 上行感知事件出口（运行时注入 → WssClientV2.sendExperienceEvent，离线入队）。 */
    private val eventSender: (CallEvent) -> Unit = {},
    /** 契约 call.* 信封上行出口（运行时注入 → WssClientV2.sendCallSignal）。 */
    private val signalSender: (CallSignal) -> Unit = {},
    /** 呼出触发上行（契约定案 A：运行时注入 → experience.event kind=call.request）。 */
    private val triggerSender: () -> Unit = {},
    private val decider: RingDecider = RingDecider(
        moduleEnabled = { isEnabled(context.applicationContext) },
        permissionGranted = { permissionGranted(context.applicationContext) },
        allPaused = { PauseFlags.isAllPaused(context.applicationContext) },
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
    /** 振铃窗调度器（注入便于测试）；返回取消句柄。默认主线程 Handler。 */
    private val timeoutScheduler: (delayMs: Long, action: () -> Unit) -> () -> Unit =
        { delayMs, action ->
            val handler = Handler(Looper.getMainLooper())
            val runnable = Runnable { action() }
            handler.postDelayed(runnable, delayMs)
            ({ handler.removeCallbacks(runnable) })
        },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : LegacyActionExecutor {

    private val appContext = context.applicationContext
    private val tracker = CallSessionTracker()

    /** 当前契约会话（invite 绑定；null=无会话，走 experience.event 感知轨）。 */
    private var flow: CallSessionFlow? = null
    private var cancelRingTimeout: (() -> Unit)? = null

    /** 呼出请求登记时刻（等待云端回呼 invite 的 60s 窗；契约无 corr_id，按窗相关）。 */
    private var pendingOutgoingAtMs: Long? = null

    init {
        // FakeConnectionService 状态钩子：Telecom 回调线程不定，sender 内部线程安全。
        // 防双轨：契约会话（flow != null）只产 call.* 信封；无会话才走 experience.event。
        FakeConnectionService.sCallStateListener =
            FakeConnectionService.CallStateListener { state, number, name, reason ->
                val f = flow
                if (f != null) {
                    val signals = when (state) {
                        "ringing" -> f.onRinging()
                        "active" -> {
                            cancelRingTimeout?.invoke()
                            cancelRingTimeout = null
                            f.onAnswered()
                        }
                        "ended" ->
                            if (reason == "rejected") f.onRejected() else f.onLocalEnded()
                        else -> emptyList()
                    }
                    emitSignals(signals)
                    if (state == "ended") endSession() // 挂断即撤销会话
                } else {
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
        // VOICE-01：媒体状态机（AudioBridge）→ call.state 事件 + 失败降级。
        // 状态：connecting/ready(mode=omni|agent|echo)/retrying/failed/stopped。
        com.wangfanlab.whale.voip.AudioBridge.sMediaListener =
            object : com.wangfanlab.whale.voip.AudioBridge.MediaStateListener {
                override fun onMediaState(state: String, mode: String, detail: String) {
                    FileLog.i("media state=$state mode=$mode $detail")
                    // 防双轨：媒体诊断为 call.media，不占用契约 call.state 词。
                    eventSender(
                        CallEvent(
                            kind = "call.media",
                            privacyClass = "owner_private",
                            summary = "media=$state mode=$mode ${detail.take(80)}".trim(),
                        ),
                    )
                    if (state == "failed") {
                        // 媒体重试耗尽：契约上行（hangup error + state failed）+
                        // 降级可见（通知）+ 主动挂断（不留死通话）
                        Log.w(TAG, "媒体失败，挂断并通知: $detail")
                        flow?.let { emitSignals(it.onError()) }
                        try {
                            com.wangfanlab.whale.modules.notifications.NotificationModule
                                .SystemNotifier(appContext)
                                .post(
                                    com.wangfanlab.whale.modules.notifications.NotificationSpec(
                                        op = com.wangfanlab.whale.modules.notifications.NotificationOp.POST,
                                        channelId = com.wangfanlab.whale.modules.notifications.NotificationModule.CHANNEL_CALL,
                                        title = "语音桥不可用",
                                        text = "通话媒体连接失败，已自动挂断（可稍后重试）",
                                        notificationTag = "voip-media-failed",
                                        openAppOnClick = true,
                                    ),
                                )
                        } catch (t: Throwable) {
                            Log.w(TAG, "降级通知失败", t)
                        }
                        com.wangfanlab.whale.voip.FakeConnectionService.hangupAll()
                        endSession() // 幂等：Telecom ended 回调通常已清
                    }
                }
            }
    }

    // ------------------------------------------------------------ ActionExecutor

    /** WssClient executor 线程调用；同步执行，经 reply 回终态（executed/failed）。 */
    override fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?, result: Map<String, Any?>?) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val args = payload["args"] as? Map<String, Any?>
        when (val decision = decider.onRequest(args)) {
            is RingDecision.Reject -> {
                Log.w(TAG, "phone.call.ring 校验未通过: ${decision.errorCode}")
                reply("failed", decision.errorCode, null, null)
            }

            is RingDecision.Execute -> try {
                tracker.bindSession(null) // action 触发的响铃无 call.invite 会话
                ringer(decision.spec)
                reply("executed", null, null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "phone.call.ring 执行异常", t)
                FileLog.e("ring execute", t)
                reply("failed", "internal", null, null)
            }
        }
    }

    // ------------------------------------------------------------ call.* 信令消费

    /**
     * 下行 call.invite（主线程，WssClient.Listener 分发）。校验媒体参数/有效期；
     * 门控不过或执行失败 → 上行 call.state failed（契约信封轨）。接受后：
     * 呼出请求 60s 窗内的 invite 走自动接听分支（不响铃/不发全屏通知，Telecom
     * 落账后立即 answerActive）；其余走正常振铃。振铃窗 60s（受 expires_at 截断）。
     */
    fun onCallInvite(payload: Map<String, Any?>) {
        when (val decision = CallInviteLogic.validate(payload, clockMs())) {
            is InviteDecision.Ignore ->
                Log.i(TAG, "call.invite 忽略: ${decision.reason}")

            is InviteDecision.Accept -> {
                if (!isEnabled(appContext) || PauseFlags.isAllPaused(appContext)) {
                    Log.w(TAG, "call.invite 到达但模块停用/全局暂停，不响铃")
                    emitSignals(listOf(CallSignalBuilder.state(decision.sessionId, "failed")))
                    return
                }
                if (!permissionGranted(appContext)) {
                    Log.w(TAG, "call.invite 到达但权限缺失，不响铃")
                    emitSignals(listOf(CallSignalBuilder.state(decision.sessionId, "failed")))
                    return
                }
                val now = clockMs()
                val autoAnswer = consumePendingOutgoing(now)
                // 主人令（B 反转）：号码原样透传（缺席按策略显 0/空，一行可切）；
                // name 穿透门——显式空串=匿名穿透，仅字段缺席回落内部轨占位 "Whale AI"；
                // UI 层恒匿名（FakeConnectionService 名称槽="来电"，真实名不进 UI）。
                val specNumber = IncomingDisplayPolicy.sanitizeNumber(payload["number"] as? String)
                val specName = CallInviteLogic.displayName(payload) ?: "Whale AI"
                Log.i(TAG, "call.invite 接受 session=${decision.sessionId} autoAnswer=$autoAnswer")
                FileLog.i(
                    "call.invite 接受 session=${decision.sessionId} autoAnswer=$autoAnswer " +
                        "number=$specNumber name=$specName",
                )
                endSession() // 防御：旧会话残留（异常路径）先撤销，取消旧振铃计时
                val f = CallSessionFlow(decision.sessionId, decision.expiresAt, autoAnswer)
                flow = f
                // 会话媒体凭据（首帧 AUTH 载荷=session_id）+ 自动接听标记交 Telecom 层
                // （连接创建时消费；不落盘——挂断即撤销）。
                FakeConnectionService.sPendingSessionAuth = decision.sessionId
                if (autoAnswer) FakeConnectionService.sAutoAnswerOnce = true
                cancelRingTimeout = timeoutScheduler(
                    CallSessionFlow.ringWindowMs(decision.expiresAt, now),
                ) { onRingTimeout(decision.sessionId) }
                try {
                    // 桥参数不经信令：走端侧配置（§4/§7）。
                    ringer(
                        RingSpec(
                            number = specNumber,
                            name = specName,
                            bridgeHost = BridgePrefs.getHost(appContext),
                            bridgePort = BridgePrefs.getPort(appContext),
                        )
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "call.invite 响铃失败", t)
                    FileLog.e("invite ring", t)
                    emitSignals(f.onError())
                    endSession()
                }
            }
        }
    }

    /**
     * 下行 call.hangup（主线程）：远端结束会话 → 挂断本地连接。契约会话内
     * 只回 state=ended（onRemoteHangup 标记，不回声 hangup 防回环）；
     * session_id 不匹配活动会话则忽略。无会话时保持既有兜底（action 响铃等）。
     */
    fun onCallHangup(payload: Map<String, Any?>) {
        val sessionId = payload["session_id"] as? String ?: return
        val f = flow
        if (f != null) {
            if (f.sessionId != sessionId) {
                Log.i(TAG, "call.hangup session=$sessionId 与活动会话不符，忽略")
                return
            }
            Log.i(TAG, "call.hangup session=$sessionId → 远端结束")
            FileLog.i("call.hangup 下行 session=$sessionId → 远端结束")
            f.onRemoteHangup()
            try {
                FakeConnectionService.hangupAll()
            } catch (t: Throwable) {
                Log.w(TAG, "call.hangup 执行异常", t)
            }
            endSession() // 幂等：Telecom ended 回调通常已清
        } else {
            Log.i(TAG, "call.hangup session=$sessionId → hangupAll（无活动会话）")
            try {
                FakeConnectionService.hangupAll()
            } catch (t: Throwable) {
                Log.w(TAG, "call.hangup 执行异常", t)
            }
        }
    }

    /**
     * 呼出触发（呼出加回主线·契约定案 A）：门控 → 登记 pendingOutgoing（60s 窗）→
     * 经 triggerSender 上行呼叫请求（experience.event kind=call.request）。
     * 云端回 call.invite 后走自动接听分支。
     * 返回 requested / unavailable / permission_missing（UI 展示用）。
     */
    fun requestOutgoingCall(): String {
        if (!isEnabled(appContext) || PauseFlags.isAllPaused(appContext)) return "unavailable"
        if (!permissionGranted(appContext)) return "permission_missing"
        pendingOutgoingAtMs = clockMs()
        triggerSender()
        FileLog.i("呼出请求已登记（等云端 call.invite 回呼，60s 窗）")
        return "requested"
    }

    /** 消费呼出请求窗：窗内→true（自动接听）；窗外/无请求→false。每次 invite 至多消费一次。 */
    private fun consumePendingOutgoing(nowMs: Long): Boolean {
        val at = pendingOutgoingAtMs ?: return false
        pendingOutgoingAtMs = null
        return nowMs - at in 0..CallSessionFlow.OUTGOING_PENDING_WINDOW_MS
    }

    private fun onRingTimeout(sessionId: String) {
        val f = flow ?: return
        if (f.sessionId != sessionId) return
        Log.w(TAG, "振铃窗到期未接听 session=$sessionId → hangup timeout")
        FileLog.i("振铃窗到期未接听 session=$sessionId → hangup timeout")
        emitSignals(f.onRingTimeout())
        try {
            FakeConnectionService.hangupAll()
        } catch (t: Throwable) {
            Log.w(TAG, "超时挂断异常", t)
        }
        endSession() // 幂等
    }

    /** 挂断即撤销会话：清 flow/振铃计时/会话凭据与自动接听标记（防泄入下一通话）。 */
    private fun endSession() {
        cancelRingTimeout?.invoke()
        cancelRingTimeout = null
        flow = null
        FakeConnectionService.sPendingSessionAuth = null
        FakeConnectionService.sAutoAnswerOnce = false
    }

    private fun emitSignals(signals: List<CallSignal>) {
        for (s in signals) {
            FileLog.i("call signal → ${s.type} ${s.payload["session_id"] ?: ""}")
            signalSender(s)
        }
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
