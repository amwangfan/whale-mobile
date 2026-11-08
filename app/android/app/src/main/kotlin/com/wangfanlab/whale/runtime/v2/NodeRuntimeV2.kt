package com.wangfanlab.whale.runtime.v2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangfanlab.whale.BuildConfig
import com.wangfanlab.whale.transport.v2.OutputPages
import com.wangfanlab.whale.CapabilityRegistry
import com.wangfanlab.whale.modules.capture.CaptureModule
import com.wangfanlab.whale.modules.capture.CapturePipeline
import com.wangfanlab.whale.modules.capture.CaptureStore
import com.wangfanlab.whale.modules.capture.NativeScreenQueryHandler
import com.wangfanlab.whale.modules.capture.SegmentAggregator
import com.wangfanlab.whale.modules.capture.SegmentReadyEmitter
import com.wangfanlab.whale.modules.capture.ocr.OcrEngine
import com.wangfanlab.whale.modules.glasses.GlassesModule
import com.wangfanlab.whale.modules.notification2.NotificationActionReceiver
import com.wangfanlab.whale.modules.notification2.NotificationChannelRegistry
import com.wangfanlab.whale.modules.notification2.NotificationDeduplicator
import com.wangfanlab.whale.modules.notification2.NotificationEventHandler
import com.wangfanlab.whale.modules.notification2.NotificationInbox
import com.wangfanlab.whale.modules.notification2.NotificationManagerFacade
import com.wangfanlab.whale.modules.notification2.NotificationPermissionManager
import com.wangfanlab.whale.modules.notification2.NotificationRenderer
import com.wangfanlab.whale.modules.notification2.NotificationUplinkSender
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.runtime.RecoveryPolicy
import com.wangfanlab.whale.runtime.SensorQueryDispatch
import com.wangfanlab.whale.runtime.UiEventHub
import com.wangfanlab.whale.transport.DeviceIdentity
import com.wangfanlab.whale.transport.v2.ActionDedupStoreV2
import com.wangfanlab.whale.transport.v2.EnvelopeCodecV2
import com.wangfanlab.whale.transport.v2.ErrorCodeV2
import com.wangfanlab.whale.transport.v2.EventOutboxV2
import com.wangfanlab.whale.transport.v2.IdentityQueueCipher
import com.wangfanlab.whale.transport.v2.LegacyActionExecutor
import com.wangfanlab.whale.transport.v2.MsgTypeV2
import com.wangfanlab.whale.transport.v2.QueueCipher
import com.wangfanlab.whale.transport.v2.ReceiptDispatcherV2
import com.wangfanlab.whale.transport.v2.ReceiptStatus
import com.wangfanlab.whale.transport.v2.TransportV2Api
import com.wangfanlab.whale.transport.v2.TransportV2Facade
import com.wangfanlab.whale.transport.v2.WssClientV2
import com.wangfanlab.whale.transport.v2.payloadLongV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 节点运行时 V2（contracts/v2 装配）：WssClientV2 / ReceiptDispatcherV2 /
 * EventOutboxV2 / notification2 通知链 / 各模块执行器的唯一所有者。
 *
 * 纪律（v1 栈已下线，本运行时为唯一节点运行时）：
 * - 生命周期归属 Application 进程（[com.wangfanlab.whale.WhaleApp.onCreate]
 *   经 [get] 创建并 [start]）；同一进程只允许一个实例，二次创建抛错——
 *   同一设备身份绝不允许两个 v2 心跳发送者；
 * - Flutter Activity/Engine 重建只重新绑定 UI 监听（[addUiListener]），
 *   不重建运行时；[NodeServiceV2] 只是本运行时的前台载体；
 * - 全局暂停/一键暂停沿用既有语义（whale_modules/all_paused，
 *   [PauseFlags.isAllPaused]）：paused 时 action 校验链前置回
 *   failed/unavailable（ReceiptDispatcherV2.isPaused），drop_on_pause 事件
 *   清除不补传（§16），sensor.query 直接回 paused；
 * - 设备撤销/认证失败为终态：清恢复上下文，不擅自重连。
 *
 * 装配内容：
 * - DeviceIdentity 自建（v1 下线后无共享方；密钥材料来源不变）；
 * - 采集存储/段聚合/持久化管线（captureStore/segmentAggregator/capturePipeline）
 *   由本运行时持有，段封口经 [SegmentReadyEmitter] 写入 [EventOutboxV2]；
 * - 执行器注册到 [ReceiptDispatcherV2]：phone.notification.post（既有
 *   NotificationModule，经 [adaptV1Executor] 适配 v2 回执状态机）、
 *   phone.device.safe_action、phone.call.ring、glasses.card.show/update/dismiss；
 * - sensor.query：device.output.read → agent 输出拉取；
 *   screen.* 在数据源为 builtin 时 → [NativeScreenQueryHandler]（无 UI 也可答）；
 *   其余 → Dart 委托 → fail-closed unavailable；
 * - notification.event/revoke → notification2 [NotificationEventHandler]
 *   （渲染 → 系统通知 → 诚实回执）；重连（ONLINE）后冲刷 action Outbox
 *   （[NotificationEventHandler.flushActionOutbox]）并经
 *   [NotificationEventHandler.buildSyncMessage] 补发 notification.sync
 *   （客户端自身在 hello_ack 后也发一次会话内 sync，二者幂等无害）。
 */
class NodeRuntimeV2 private constructor(
    private val appContext: Context,
    private val mainHandler: Handler,
    /** 设备身份（生产路径复用 v1/自建；测试经 [installForTesting] 注入种子身份）。 */
    val identity: DeviceIdentity,
    queueCipher: QueueCipher?,
) : WssClientV2.Listener {

    // ------------------------------------------------------------- UI 监听扇出

    /** UI 侧监听（TransportBridgeV2 注册）。回调已在主线程。 */
    fun interface UiListener {
        fun onEvent(event: Map<String, Any?>)
    }

    /**
     * sensor.query 处理器（Dart 侧经 TransportBridgeV2 注册）。
     * 返回 true 表示接管（稍后经 [sendSensorResult] 回复）；false 表示不接管。
     */
    fun interface SensorQueryHandler {
        fun onSensorQuery(payload: Map<String, Any?>): Boolean
    }

    private val uiEventHub = UiEventHub()

    /** Dart 侧 sensor.query 委托；null = 无处理器，fail-closed 回 unavailable。 */
    @Volatile
    private var sensorQueryHandler: SensorQueryHandler? = null

    @Volatile
    private var deviceStatus: String = "online"

    /** 通知协程作用域（handler/receiver 的 suspend 入口；SupervisorJob 隔离单点失败）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------- 运行时组件（唯一实例）

    /** v2 上行事件队列（独立 DB，与 v1 队列物理隔离）。 */
    val outbox: EventOutboxV2 =
        EventOutboxV2(appContext, cipher = queueCipher, deviceIdProvider = { identity.getDeviceId() })

    /** v2 下行动作幂等表（终态回执加密落库，重复键原样回放）。 */
    val dedupStore: ActionDedupStoreV2 = ActionDedupStoreV2(appContext, cipher = queueCipher)

    /** 采集存储（v1 下线后由本运行时持有；同一 DB 单 OpenHelper）。 */
    val captureStore: CaptureStore = CaptureStore(appContext)

    /** 动态段聚合器（同包名且间隔 <120s 归段，换段时封口旧段）。 */
    val segmentAggregator: SegmentAggregator = SegmentAggregator(captureStore)

    /** 通知收件箱（通知历史页数据源 + 事件去重持久层 + action Outbox）。 */
    val inbox: NotificationInbox = NotificationInbox(appContext)

    // ---- v1 模块执行器（适配进 v2 分发器；模块文件不改，状态词经映射收敛） ----

    val notificationModule: NotificationModule = NotificationModule(appContext)

    val safeActionModule: SafeActionModule = SafeActionModule(appContext)

    val glassesModule: GlassesModule = GlassesModule(appContext)

    /** VoIP：事件出口接到 v2 experience.event（call.state 等，脱敏摘要在模块内完成）。 */
    val voipModule: VoipModule = VoipModule(
        appContext,
        eventSender = { ev ->
            sendExperienceEvent(
                kind = ev.kind,
                occurredAt = System.currentTimeMillis(),
                source = SOURCE_VOIP,
                summary = ev.summary,
                privacyClass = ev.privacyClass,
            )
        },
    )

    /** 通知监听（phone.notification.read）：事件出口接到 v2 队列，私人历史暂停不补传。 */
    val notificationReadModule: NotificationReadModule = NotificationReadModule(
        appContext,
        eventSender = { summary, occurredAt ->
            sendExperienceEvent(
                kind = "notification.posted",
                occurredAt = occurredAt,
                source = SOURCE_NOTIFICATION_READ,
                summary = summary,
                privacyClass = "owner_private",
                dropOnPause = true,
            )
        },
    )

    /** v1 设备自主体（device.output.read 的输出拉取数据源；v2 动作族由 agent2 接管）。 */
    val agentModule: com.wangfanlab.whale.modules.agent.AgentModule =
        com.wangfanlab.whale.modules.agent.AgentModule(appContext)

    /** 手机可信确认（§14）：responder 适配 v2（签名由客户端内部完成）。 */
    val authConfirmModule: com.wangfanlab.whale.modules.authconfirm.AuthConfirmModule =
        com.wangfanlab.whale.modules.authconfirm.AuthConfirmModule(
            context = appContext,
            identityProvider = { identity },
            responder = { challengeId, approved, _, reason ->
                client.sendConfirmResponse(challengeId, approved, reason)
            },
        )

    // ---- notification2 通知链（§13） ----

    private val notificationChannels = NotificationChannelRegistry(appContext)
    private val notificationDedup = NotificationDeduplicator(inbox)
    private val notificationRenderer = NotificationRenderer(appContext, notificationChannels)
    private val notificationFacade = NotificationManagerFacade(appContext)
    private val notificationPermissions = NotificationPermissionManager(appContext)

    /**
     * notification2 上行适配器：完整 v2 信封 JSON → WssClientV2 类型化上行口。
     * （WssClientV2 只暴露类型化发送口，seq/id 由连接级重写；离线时抛异常，
     * 让 action Outbox 保序重试——通知回执无独立持久队列，离线丢失由 core
     * 补传重发 + 端侧 event_id 去重兜底。）
     */
    private val notificationUplink = NotificationUplinkSender { json ->
        if (getStateInfo()["state"] != "online") {
            throw IOException("v2 通道不在线，通知上行暂缓（Outbox 保留）")
        }
        val env = EnvelopeCodecV2.decode(json)
        val p = env.payload
        when (env.type) {
            MsgTypeV2.NOTIFICATION_RECEIPT -> client.sendNotificationReceipt(
                eventId = p["event_id"] as String,
                notificationId = p["notification_id"] as String,
                status = p["status"] as String,
                receivedAt = payloadLongV2(p, "received_at") ?: System.currentTimeMillis(),
            )
            MsgTypeV2.NOTIFICATION_ACTION -> client.sendNotificationAction(
                eventId = p["event_id"] as String,
                notificationId = p["notification_id"] as String,
                actionId = p["action_id"] as String,
            )
            MsgTypeV2.NOTIFICATION_SYNC -> client.sendNotificationSync()
            else -> throw IOException("不支持的通知上行类型: ${env.type}")
        }
    }

    /** notification.event / revoke 全链处理器（渲染→落库→回执）。 */
    val notificationEventHandler: NotificationEventHandler = NotificationEventHandler(
        context = appContext,
        inbox = inbox,
        dedup = notificationDedup,
        renderer = notificationRenderer,
        facade = notificationFacade,
        channels = notificationChannels,
        permissions = notificationPermissions,
        uplink = notificationUplink,
        deviceIdProvider = { identity.getDeviceId() },
    )

    /** 段封口 → experience.event（v2 上行队列）。 */
    val segmentReadyEmitter: SegmentReadyEmitter = SegmentReadyEmitter(outbox)

    /**
     * 采集持久化管线（SM-02/Phase 3；v1 下线后由本运行时持有）：
     * 唯一帧 → 存 JPEG → OCR → 索引 → 段聚合；旧段封口即写入 v2 上行队列。
     * 仅在 persist 开启时由 ShadowCaptureService 调用（构造无副作用）。
     */
    val capturePipeline: CapturePipeline = CapturePipeline(
        appContext,
        captureStore,
        OcrEngine.mlKit,
        segmentAggregator = segmentAggregator,
        onSegmentReady = { segment -> segmentReadyEmitter.onSegmentReady(segment) },
    )

    @Volatile
    private var client: WssClientV2 = newClient()

    @Volatile
    private var started = false

    @Volatile
    private var shutdown = false

    /** 传输面 facade（桥层/未来 Dart V2 客户端的薄代理目标）。 */
    val api: TransportV2Api get() = TransportV2Facade(client)

    private fun newClient(): WssClientV2 = WssClientV2(
        identity, outbox, dedupStore, this, mainHandler,
    ).apply {
        heartbeatStatusProvider = { WssClientV2.HeartbeatStatusV2(status = deviceStatus) }
        hasUiHandlerOverride = { uiEventHub.hasListeners }
        // 全局暂停（A1 一键暂停）：paused 时 action 立即 failed/unavailable。
        dispatcher.isPaused = { PauseFlags.isAllPaused(appContext) }
        registerExecutors(dispatcher)
        registerAgentHandlers(dispatcher)
    }

    /**
     * v1 模块执行器注册（v2 §5.3 动作子集内的既有能力）。
     * v1 执行器读 payload.args/capability/correlation_id，回执状态词
     * （executed/failed）在此映射为 v2 五终态（succeeded/failed）。
     */
    private fun registerExecutors(d: ReceiptDispatcherV2) {
        d.registerHandler(NotificationModule.CAPABILITY, adaptV1Executor(notificationModule))
        d.registerHandler(SafeActionModule.CAPABILITY, adaptV1Executor(safeActionModule))
        d.registerHandler(VoipModule.CAPABILITY_RING, adaptV1Executor(voipModule))
        d.registerHandler(GlassesModule.CAPABILITY_SHOW, adaptV1Executor(glassesModule))
        d.registerHandler(GlassesModule.CAPABILITY_UPDATE, adaptV1Executor(glassesModule))
        d.registerHandler(GlassesModule.CAPABILITY_DISMISS, adaptV1Executor(glassesModule))
        Log.i(TAG, "v2 执行器已注册: notification.post/safe_action/call.ring/glasses.*")
    }

    /**
     * agent2 / recording 执行器注册点（并行任务产物，可能尚未存在）。
     *
     * 为避免编译期依赖并行任务，这里用反射探测其装配入口：约定 agent2/recording
     * 模块各自暴露一个object/类，声明
     * `fun registerInto(dispatcher: ReceiptDispatcherV2, context: Context)`，
     * 探测到即注册（device.tap/device.observe/device.shell 等 v2 动作族与
     * recording.control 消费）；探测不到则安静跳过——无执行器的能力由
     * ReceiptDispatcherV2 按 §16 fail-closed 回 failed/unavailable，绝不悬挂。
     *
     * 接线方：modules/agent2（Agent2Logic 已就绪，执行器/模块壳待并行任务）、
     * modules/recording（§12.2 recording.control，MediaProjection 录制）。
     */
    fun registerAgentHandlers(dispatcher: ReceiptDispatcherV2 = client.dispatcher) {
        val candidates = listOf(
            "com.wangfanlab.whale.modules.agent2.Agent2Module",
            "com.wangfanlab.whale.modules.agent2.Agent2Runtime",
            "com.wangfanlab.whale.modules.recording.RecordingModule",
            "com.wangfanlab.whale.modules.recording.RecordingRuntime",
        )
        var wired = 0
        for (name in candidates) {
            try {
                val cls = Class.forName(name)
                // 不用 cls.kotlin（kotlin-reflect 未打包进 APK）；Kotlin object
                // 走 INSTANCE 字段，普通类走 Context 构造或无参构造。
                val target = runCatching {
                    cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull() ?: runCatching {
                    cls.getConstructor(Context::class.java).newInstance(appContext)
                }.getOrElse {
                    cls.getDeclaredConstructor().newInstance()
                }
                val register = cls.methods.firstOrNull { m ->
                    m.name == "registerInto" && m.parameterTypes.size == 2
                }
                if (register != null) {
                    register.invoke(target, dispatcher, appContext)
                    Log.i(TAG, "已注册并行任务执行器: $name")
                    wired++
                    wireParallelHooks(cls, target)
                }
            } catch (_: ClassNotFoundException) {
                // 并行任务产物尚未存在：安静跳过（fail-closed 由分发器兜底）
            } catch (t: Throwable) {
                Log.w(TAG, "agent2/recording 执行器注册失败（$name），跳过: ${t.message}")
            }
        }
        if (wired == 0) {
            Log.d(TAG, "agent2/recording 执行器尚未就绪（并行任务），本次跳过注册")
        }
    }

    /**
     * 并行任务装配成功后的补充接线（同按方法名探测，缺哪个跳哪个）：
     * - `readOutput(String)`：device.output.read 的数据源（agent2 输出仓库）；
     * - `handleControlFrame(Map)`：recording.control 下行帧入口；
     * - `attachStateSender(StateSender)`：recording.state 上行口
     *   （StateSender 为并行任务的 fun interface，经动态代理适配到
     *   [WssClientV2.sendRecordingState]，不产生编译依赖）。
     */
    private fun wireParallelHooks(cls: Class<*>, target: Any) {
        OutputPages.reader(target)?.let { agent2ReadOutputPageHook = it }
        cls.methods.firstOrNull { it.name == "readOutput" && it.parameterTypes.size == 1 }
            ?.let { m ->
                agent2ReadOutputHook = { ref ->
                    @Suppress("UNCHECKED_CAST")
                    (m.invoke(target, ref) as? Map<String, Any?>)
                        ?: mapOf("error" to "internal")
                }
                Log.i(TAG, "device.output.read 已接线到 ${cls.simpleName}.readOutput")
            }
        cls.methods.firstOrNull {
            it.name == "handleControlFrame" && it.parameterTypes.size == 1
        }?.let { m ->
            recordingControlHook = { payload ->
                @Suppress("UNCHECKED_CAST")
                m.invoke(target, payload as Map<String, Any?>)
            }
            Log.i(TAG, "recording.control 已接线到 ${cls.simpleName}.handleControlFrame")
        }
        cls.methods.firstOrNull {
            it.name == "attachStateSender" && it.parameterTypes.size == 1
        }?.let { m ->
            val senderIf = m.parameterTypes[0]
            if (senderIf.isInterface) {
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    senderIf.classLoader, arrayOf(senderIf),
                ) { _, method, args ->
                    if (method.name == "send" && args != null && args.size == 5) {
                        client.sendRecordingState(
                            runId = args[0] as String,
                            state = args[1] as String,
                            fileRef = args[2] as String?,
                            durationMs = (args[3] as? Number)?.toLong(),
                            errorCode = args[4] as String?,
                        )
                    }
                    null
                }
                m.invoke(target, proxy)
                Log.i(TAG, "recording.state 上行口已接线（${cls.simpleName}.attachStateSender）")
            }
        }
    }

    /** 既有（v1 契约）模块执行器 → v2 ActionHandler 适配（终态映射 + result/provider_ref 透传）。 */
    private fun adaptV1Executor(executor: LegacyActionExecutor): ReceiptDispatcherV2.ActionHandler =
        ReceiptDispatcherV2.ActionHandler { request, reply ->
            val payload = HashMap<String, Any?>(request.raw)
            payload["capability"] = request.capability
            payload["args"] = request.arguments
            try {
                executor.execute(payload) { state, errorCode, providerRef, result ->
                    val status = when (state) {
                        "executed" -> ReceiptStatus.SUCCEEDED
                        "failed" -> ReceiptStatus.FAILED
                        else -> ReceiptStatus.UNKNOWN
                    }
                    reply(
                        ReceiptDispatcherV2.ActionOutcome(
                            status = status,
                            // v1 错误码收敛到 §15 枚举（module_disabled/paused 等并入 unavailable）
                            errorCode = errorCode?.let { v2ErrorCodeOf(it) }
                                ?: if (status == ReceiptStatus.FAILED) ErrorCodeV2.INTERNAL else null,
                            resultSummary = result?.get("summary") as? String,
                            outputRef = result?.get("output_ref") as? String,
                            // v1 执行器无独立验证步骤：按 §7.3 必须标 skipped，绝不伪报 passed
                            verificationStatus = "skipped",
                            providerRef = providerRef,
                        ),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "v1 执行器适配层异常", t)
                reply(
                    ReceiptDispatcherV2.ActionOutcome(
                        ReceiptStatus.FAILED, ErrorCodeV2.INTERNAL,
                    ),
                )
            }
        }

    // ------------------------------------------------------------- 生命周期

    /**
     * 启动运行时（Application.onCreate 调用一次；幂等）。
     * 触碰各模块完成系统钩子注册；采集管线的段封口出口在构造期已接到
     * [segmentReadyEmitter]（见 [capturePipeline]）。
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        shutdown = false
        // 通知动作接收器的运行期依赖（进程被广播拉起时 Flutter/Service 可能不存在）
        NotificationActionReceiver.wire(
            NotificationActionReceiver.Dependencies(
                inbox = inbox,
                dedup = notificationDedup,
                renderer = notificationRenderer,
                facade = notificationFacade,
                uplink = notificationUplink,
                deviceIdProvider = { identity.getDeviceId() },
                scope = scope,
            ),
        )
        registerNetworkCallback()
        Log.i(TAG, "NodeRuntimeV2 已启动 device_id=${identity.getDeviceId()}")
        // 配对登记需要公钥（owner 在 core 侧粘贴）；启动即打印便于运维取回。
        Log.i(TAG, "设备公钥（配对用）: ${identity.getPublicKeyHex()}")
    }

    /**
     * 连接恢复（BG-02 v2 面）：进程新建时按 [RecoveryPolicy] 决策是否重连。
     * 尊重一键暂停与设备撤销；持久化只保留 URL，能力集随版本漂移总是上报当前全集。
     */
    @Synchronized
    fun recoverIfNeeded(why: String): Boolean {
        val prefs = appContext.getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE)
        val wasConnected = prefs.getBoolean(KEY_WAS_CONNECTED, false)
        val revoked = prefs.getBoolean(KEY_REVOKED, false)
        val paused = PauseFlags.isAllPaused(appContext)
        if (!RecoveryPolicy.shouldRecover(wasConnected, paused, revoked)) {
            Log.i(TAG, "v2 恢复评估[$why]：不恢复（wasConnected=$wasConnected paused=$paused revoked=$revoked）")
            return false
        }
        val url = prefs.getString(KEY_GATEWAY_URL, null)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "v2 恢复评估[$why]：缺少已存网关地址，放弃")
            return false
        }
        Log.i(TAG, "v2 恢复评估[$why]：发起重连 url=$url")
        if (com.wangfanlab.whale.transport.TransportBridge.isBgServiceEnabled(appContext)) {
            NodeServiceV2.start(appContext)
        }
        connect(url)
        return true
    }

    // ---- 网络恢复回调（与 v1 同策略：默认网络可用时立即重连） ----

    @Volatile
    private var networkAvailable: Boolean = true

    private var networkCallbackRegistered = false

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            networkAvailable = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    networkAvailable = true
                    client.reconnectNow()
                }

                override fun onLost(network: android.net.Network) {
                    networkAvailable = cm.activeNetwork != null
                }
            })
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "v2 网络回调注册失败（降级为纯心跳看门狗）: ${t.message}")
        }
    }

    /** kill switch：断开连接并销毁 WSS 客户端；幂等；之后 [connect] 会重建客户端。 */
    @Synchronized
    fun shutdown() {
        if (shutdown) return
        shutdown = true
        client.destroy()
        emitEvent(mapOf("kind" to "state", "state" to "disconnected"))
        Log.i(TAG, "NodeRuntimeV2 已关闭（kill switch）")
    }

    val isShutdown: Boolean get() = shutdown

    // ------------------------------------------------------------- 连接与状态

    /**
     * 建立 v2 连接（capabilities=CapabilityRegistry.current、
     * app_flavor=BuildConfig.FLAVOR、features 实测；HelloConfig 由本函数组装）。
     */
    fun connect(url: String, appVersion: String = detectAppVersion()) {
        if (shutdown) {
            // kill switch 后允许显式重连（用户主动操作）
            shutdown = false
            client = newClient()
        }
        appContext.getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAS_CONNECTED, true)
            .putBoolean(KEY_REVOKED, false)
            .putString(KEY_GATEWAY_URL, url)
            .apply()
        client.connect(url, buildHelloConfig(appVersion))
    }

    /** 组装 device.hello 配置（能力集按构建变体；features 实测）。 */
    fun buildHelloConfig(appVersion: String = detectAppVersion()): WssClientV2.HelloConfig =
        WssClientV2.HelloConfig.detect(
            context = appContext,
            capabilities = CapabilityRegistry.current,
            appVersion = appVersion,
        )

    fun disconnect() {
        // 用户主动断开：清除恢复标志，重启/开机不再自动重连。
        appContext.getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAS_CONNECTED, false)
            .apply()
        client.disconnect()
    }

    /**
     * 心跳状态（online/connected_only/paused）。paused 时按 §16 清除 outbox 中
     * drop_on_pause 事件（暂停期不补传私人历史）。一键暂停标志（all_paused）
     * 与 v1 共用 whale_modules/all_paused，由桥层写。
     */
    fun setStatus(status: String) {
        require(status in setOf("online", "connected_only", "paused")) { "非法心跳状态: $status" }
        deviceStatus = status
        if (status == "paused") {
            val dropped = client.clearDropOnPauseEvents()
            if (dropped > 0) Log.i(TAG, "进入 paused，丢弃 drop_on_pause 事件 $dropped 条")
        }
    }

    fun getStateInfo(): Map<String, Any?> {
        val info = LinkedHashMap<String, Any?>(client.getStateInfo())
        info["runtime_started"] = started
        info["node_service_running"] = NodeServiceV2.isRunning
        info["network_available"] = networkAvailable
        info["app_flavor"] = CapabilityRegistry.flavor
        val prefs = appContext.getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE)
        info["was_connected"] = prefs.getBoolean(KEY_WAS_CONNECTED, false)
        return info
    }

    /** 配对码展示数据（§1 配对：设备上屏 → 主人在 core 可信管理端确认登记）。 */
    fun getPairingInfo(): Map<String, Any?> = mapOf(
        "device_id" to identity.getDeviceId(),
        "public_key_hex" to identity.getPublicKeyHex(),
        "app_flavor" to CapabilityRegistry.flavor,
        "protocol_version" to com.wangfanlab.whale.transport.v2.PROTOCOL_VERSION_V2,
        "capabilities" to CapabilityRegistry.current.toList(),
    )

    private fun detectAppVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    // ------------------------------------------------------------- 上行 API（代理到唯一 client）

    fun sendExperienceEvent(
        kind: String,
        occurredAt: Long,
        source: String,
        summary: String,
        privacyClass: String,
        evidenceRef: String? = null,
        taskId: String? = null,
        runId: String? = null,
        extras: Map<String, Any?>? = null,
        dropOnPause: Boolean = false,
        eventId: String = com.wangfanlab.whale.transport.v2.IdsV2.newEventId(),
    ): String = client.sendExperienceEvent(
        kind, occurredAt, source, summary, privacyClass,
        evidenceRef, taskId, runId, extras, dropOnPause, eventId,
    )

    fun sendActionReceipt(
        actionId: String,
        status: ReceiptStatus,
        errorCode: String? = null,
        resultSummary: String? = null,
        outputRef: String? = null,
        verificationStatus: String? = null,
        verificationSummary: String? = null,
        providerRef: String? = null,
    ) = client.sendActionReceipt(
        actionId, status, errorCode,
        resultSummary, outputRef, verificationStatus, verificationSummary, providerRef,
    )

    fun sendNotificationReceipt(eventId: String, notificationId: String, status: String) =
        client.sendNotificationReceipt(eventId, notificationId, status)

    fun sendNotificationAction(eventId: String, notificationId: String, actionId: String) =
        client.sendNotificationAction(eventId, notificationId, actionId)

    fun sendSensorResult(
        requestId: String,
        status: String,
        data: List<Any?>,
        truncated: Boolean,
        source: String,
    ) = client.sendSensorResult(requestId, status, data, truncated, source)

    // ------------------------------------------------------------- UI 绑定

    fun addUiListener(listener: UiListener) {
        val wrapper = UiEventHub.Listener { event -> listener.onEvent(event) }
        uiListenerWrappers[listener] = wrapper
        uiEventHub.add(wrapper)
    }

    fun removeUiListener(listener: UiListener) {
        uiListenerWrappers[listener]?.let { uiEventHub.remove(it) }
        uiListenerWrappers.remove(listener)
    }

    /** add 时建立的 UiListener → Hub 包装映射（remove 按原包装注销）。 */
    private val uiListenerWrappers =
        java.util.concurrent.ConcurrentHashMap<UiListener, UiEventHub.Listener>()

    /** UI 是否已绑定（诊断用；亦作无原生执行器动作的 UI 委托存在性）。 */
    val hasUiListener: Boolean get() = uiEventHub.hasListeners

    fun setSensorQueryHandler(handler: SensorQueryHandler?) {
        sensorQueryHandler = handler
        Log.i(TAG, "v2 sensor.query Dart 委托: ${handler != null}")
    }

    private fun emitEvent(event: Map<String, Any?>) {
        uiEventHub.emit(event)
    }

    // ------------------------------------------------------------- WssClientV2.Listener（主线程回调）

    override fun onStateChanged(state: WssClientV2.State, info: Map<String, Any?>) {
        val event = HashMap<String, Any?>(info)
        event["kind"] = "state"
        event["state"] = WssClientV2.stateName(state)
        emitEvent(event)
        if (state == WssClientV2.State.ONLINE) {
            // §13.3：重连成功后冲刷通知 action Outbox（保序），并补发
            // notification.sync（buildSyncMessage：带 Inbox 持久化的
            // last_event_id；客户端自身在 hello_ack 也发会话内 sync，幂等）。
            scope.launch {
                try {
                    val flushed = notificationEventHandler.flushActionOutbox()
                    if (flushed > 0) Log.i(TAG, "通知 action Outbox 冲刷 $flushed 条")
                    notificationUplink.send(notificationEventHandler.buildSyncMessage())
                } catch (t: Throwable) {
                    Log.w(TAG, "通知 Outbox 冲刷/sync 发送失败: ${t.message}")
                }
            }
        }
    }

    override fun onActionRequest(payload: Map<String, Any?>) {
        // 无原生执行器动作的 UI 委托路径（终态由业务层经 sendActionReceipt 补发）
        val event = HashMap<String, Any?>()
        event["kind"] = "action_request"
        event.putAll(payload)
        emitEvent(event)
    }

    override fun onActionTerminal(actionId: String, capability: String, status: String, errorCode: String?) {
        emitEvent(
            mapOf(
                "kind" to "action_receipt",
                "action_id" to actionId,
                "capability" to capability,
                "status" to status,
                "error_code" to errorCode,
            ),
        )
    }

    /** SM-03：内置数据源时 screen.* 查询走本机 CaptureStore（无 UI 也可答）。 */
    private val nativeScreenQuery by lazy {
        NativeScreenQueryHandler(appContext, captureStore) { requestId, status, data, truncated, source ->
            client.sendSensorResult(requestId, status, data, truncated, source)
        }
    }

    /**
     * sensor.query 处理器链（与 v1 同语义）：
     * device.output.read（agent 输出拉取，最先路由）→ builtin 数据源原生应答 →
     * 全局暂停 → Dart 委托 → fail-closed unavailable。
     */
    override fun onSensorQuery(payload: Map<String, Any?>): Boolean {
        val requestId = payload["request_id"] as? String ?: return false
        val operation = payload["operation"] as? String ?: ""
        // device.output.read：agent 输出拉取（agent2 就绪后可在其内部接管同一操作）
        if (operation == "device.output.read") {
            if (PauseFlags.isAllPaused(appContext)) {
                client.sendSensorResult(requestId, "paused", emptyList(), false, "agent.local")
                return true
            }
            @Suppress("UNCHECKED_CAST")
            val filters = payload["filters"] as? Map<String, Any?> ?: emptyMap()
            val ref = (filters["output_ref"] as? String)
                ?: (filters["image_refs"] as? List<*>)?.filterIsInstance<String>()?.firstOrNull()
            if (ref.isNullOrBlank()) {
                client.sendSensorResult(
                    requestId, "error",
                    listOf(mapOf("error" to "missing_ref")), false, "agent.local",
                )
                return true
            }
            // agent2 已装配时优先走其输出仓库（§10 引用白名单）；否则回退 v1 agentModule
            val hook = agent2ReadOutputHook
            val output = try {
                val page = OutputPages.request(filters)
                agent2ReadOutputPageHook?.invoke(ref, page.offset, page.maxBytes)
                    ?: OutputPages.legacy(hook?.invoke(ref) ?: agentModule.readOutput(ref), page)
            } catch (e: IllegalArgumentException) {
                mapOf("error" to "invalid_argument")
            } catch (e: Exception) {
                Log.w(TAG, "device.output.read failed", e)
                mapOf("error" to "unavailable")
            }
            val status = when (output["error"] as? String) {
                null -> "ok"
                "module_disabled", "scope_denied" -> "denied"
                "unavailable" -> "unavailable"
                else -> "error"
            }
            client.sendSensorResult(requestId, status, listOf(output), output["truncated"] == true, "agent.local")
            return true
        }
        // SM-03：数据源为 builtin 且操作受支持 → 原生直接回答（先于 Dart 委托）
        if (!PauseFlags.isAllPaused(appContext) &&
            CaptureModule.dataSource(appContext) == CaptureModule.SOURCE_BUILTIN &&
            NativeScreenQueryHandler.supports(operation)
        ) {
            return nativeScreenQuery.handle(payload)
        }
        return when (SensorQueryDispatch.decide(
            allPaused = PauseFlags.isAllPaused(appContext),
            hasDelegate = sensorQueryHandler != null,
        )) {
            SensorQueryDispatch.Decision.PAUSED -> {
                Log.i(TAG, "全局暂停中，sensor.query 回 paused request_id=$requestId")
                client.sendSensorResult(requestId, "paused", emptyList(), false, MCP_RESULT_SOURCE)
                true
            }
            SensorQueryDispatch.Decision.DELEGATE -> try {
                sensorQueryHandler!!.onSensorQuery(payload)
            } catch (t: Throwable) {
                Log.e(TAG, "v2 sensor.query 处理器异常 request_id=$requestId", t)
                false
            }
            SensorQueryDispatch.Decision.UNAVAILABLE -> false
        }
    }

    override fun onNotificationEvent(payload: Map<String, Any?>) {
        // 渲染/落库/回执全链（suspend；IO 协程，失败仅记日志不回伪回执）
        val json = EnvelopeCodecV2.payloadToJson(payload)
        scope.launch {
            try {
                notificationEventHandler.handleEvent(json)
            } catch (t: Throwable) {
                Log.e(TAG, "notification.event 处理异常", t)
            }
        }
        val event = HashMap<String, Any?>()
        event["kind"] = "notification_event"
        event.putAll(payload)
        emitEvent(event)
    }

    override fun onNotificationRevoke(payload: Map<String, Any?>) {
        val json = EnvelopeCodecV2.payloadToJson(payload)
        scope.launch {
            try {
                notificationEventHandler.handleRevoke(json)
            } catch (t: Throwable) {
                Log.e(TAG, "notification.revoke 处理异常", t)
            }
        }
        val event = HashMap<String, Any?>()
        event["kind"] = "notification_revoke"
        event.putAll(payload)
        emitEvent(event)
    }

    override fun onRecordingControl(payload: Map<String, Any?>) {
        // §12.2 recording.control：modules/recording 为并行任务产物。
        // 已装配（反射钩子）→ 交模块处理；未装配 → 仅转发 UI（不伪报执行）。
        val hook = recordingControlHook
        if (hook != null) {
            try {
                hook(payload)
            } catch (t: Throwable) {
                Log.e(TAG, "recording.control 处理异常 op=${payload["op"]}", t)
            }
        } else {
            Log.i(TAG, "recording.control 到达（modules/recording 未装配），仅转发 UI op=${payload["op"]}")
        }
        val event = HashMap<String, Any?>()
        event["kind"] = "recording_control"
        event.putAll(payload)
        emitEvent(event)
    }

    override fun onCallInvite(payload: Map<String, Any?>) {
        voipModule.onCallInvite(payload)
        emitEvent(
            mapOf(
                "kind" to "call_invite",
                "session_id" to payload["session_id"],
                "expires_at" to payload["expires_at"],
            ),
        )
    }

    override fun onCallHangup(payload: Map<String, Any?>) {
        voipModule.onCallHangup(payload)
        emitEvent(
            mapOf(
                "kind" to "call_hangup",
                "session_id" to payload["session_id"],
            ),
        )
    }

    override fun onConfirmRequest(payload: Map<String, Any?>) {
        authConfirmModule.onConfirmRequest(payload)
        emitEvent(
            mapOf(
                "kind" to "confirm_request",
                "challenge_id" to payload["challenge_id"],
                "channel" to payload["channel"],
                "scopes" to payload["scopes"],
            ),
        )
    }

    override fun onProtocolError(code: String, message: String, refId: String) {
        // 撤销/认证失败为终态：标记 revoked，清除恢复上下文（不擅自重连）。
        if (code == ErrorCodeV2.DEVICE_REVOKED || code == ErrorCodeV2.AUTH_FAILED) {
            appContext.getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REVOKED, code == ErrorCodeV2.DEVICE_REVOKED)
                .putBoolean(KEY_WAS_CONNECTED, false)
                .apply()
        }
        emitEvent(
            mapOf(
                "kind" to "error",
                "code" to code,
                "message" to message,
                "ref_id" to refId,
            ),
        )
    }

    /** agent2 装配成功后的 device.output.read 数据源钩子（反射探测缓存）。 */
    @Volatile
    private var agent2ReadOutputHook: ((String) -> Map<String, Any?>)? = null

    @Volatile
    private var agent2ReadOutputPageHook: ((String, Long, Int) -> Map<String, Any?>)? = null

    /** recording 装配成功后的 recording.control 帧处理钩子（反射探测缓存）。 */
    @Volatile
    private var recordingControlHook: ((Map<String, Any?>) -> Unit)? = null

    // ------------------------------------------------------------- 测试钩子

    @androidx.annotation.VisibleForTesting
    internal fun clientForTest(): WssClientV2 = client

    companion object {
        private const val TAG = "WhaleNodeRuntimeV2"
        private const val MCP_RESULT_SOURCE = "screenmemo.local"
        private const val SOURCE_VOIP = "voip"
        private const val SOURCE_NOTIFICATION_READ = "notification_read"

        /** v2 节点恢复上下文（独立于 v1 的 whale_node，互不影响）。 */
        private const val NODE_PREFS_NAME_V2 = "whale_node_v2"
        private const val KEY_WAS_CONNECTED = "was_connected"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_REVOKED = "revoked"

        /** 配对/重配对成功后调用：清除撤销标记。 */
        fun clearRevoked(context: Context) {
            context.applicationContext
                .getSharedPreferences(NODE_PREFS_NAME_V2, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REVOKED, false).apply()
        }

        /** v1 执行器错误码 → v2 §15 枚举收敛（未知/模块态错误并入 unavailable）。 */
        internal fun v2ErrorCodeOf(v1Code: String): String = when {
            v1Code in ErrorCodeV2.ALL -> v1Code
            v1Code == "module_disabled" || v1Code == "paused" -> ErrorCodeV2.UNAVAILABLE
            else -> ErrorCodeV2.INTERNAL
        }

        private val created = AtomicBoolean(false)

        @Volatile
        private var instance: NodeRuntimeV2? = null

        /**
         * 获取进程内唯一 v2 运行时（首次调用创建）。二次创建直接抛错——
         * 同一设备身份绝不允许两个 v2 心跳发送者。
         * 设备身份由本运行时自建（v1 栈已下线；密钥材料来源不变）。
         */
        fun get(context: Context): NodeRuntimeV2 {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return@synchronized it }
                check(created.compareAndSet(false, true)) {
                    "NodeRuntimeV2 已创建过，禁止第二实例"
                }
                val app = context.applicationContext
                val identity = DeviceIdentity(app)
                NodeRuntimeV2(
                    app,
                    Handler(Looper.getMainLooper()),
                    identity,
                    queueCipher = IdentityQueueCipher(identity),
                ).also { instance = it }
            }
        }

        /** 已创建则返回，否则 null（Service/桥层避免隐式创建）。 */
        fun peek(): NodeRuntimeV2? = instance

        /**
         * 测试装配入口：以种子身份（无 Keystore 依赖）与可选队列加密器
         * 安装进程内唯一实例。仍受单例纪律约束（已创建则抛错）。
         */
        @androidx.annotation.VisibleForTesting
        fun installForTesting(
            context: Context,
            identity: DeviceIdentity,
            queueCipher: QueueCipher? = null,
        ): NodeRuntimeV2 {
            check(instance == null && created.compareAndSet(false, true)) {
                "NodeRuntimeV2 已创建过，禁止第二实例"
            }
            return NodeRuntimeV2(
                context.applicationContext,
                Handler(Looper.getMainLooper()),
                identity,
                queueCipher,
            ).also { instance = it }
        }

        /** 仅供单元测试重置单例（不要在生产代码调用）。 */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance = null
            created.set(false)
        }
    }
}
