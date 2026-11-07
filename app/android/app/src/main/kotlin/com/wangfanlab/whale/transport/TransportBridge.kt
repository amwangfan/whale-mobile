package com.wangfanlab.whale.transport

import android.app.Activity
import android.content.Context
import android.util.Log
import com.wangfanlab.whale.modules.notificationread.NotificationReadModule
import com.wangfanlab.whale.modules.notifications.NotificationModule
import com.wangfanlab.whale.modules.safeaction.SafeActionModule
import com.wangfanlab.whale.modules.safeaction.SafeActionValidator
import com.wangfanlab.whale.modules.voip.VoipModule
import com.wangfanlab.whale.runtime.PauseFlags
import com.wangfanlab.whale.runtime.v2.NodeRuntimeV2
import com.wangfanlab.whale.runtime.v2.NodeServiceV2
import com.wangfanlab.whale.voip.BridgePrefs
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 桥（v1 栈下线后的瘦身版）：MethodChannel `whale/transport`。
 *
 * v1 协议控制面（connect/disconnect/sendSensorEvent/sendActionReceipt/setStatus/
 * sendSensorResult/setSensorQueryDelegate/getState/getIdentity 与
 * EventChannel `whale/transport/events`）已随 v1 协议栈整体下线；
 * 控制面唯一通道为 `whale/transport_v2`（见 [com.wangfanlab.whale.transport.v2.TransportBridgeV2]）。
 *
 * 本桥保留两类**与协议版本无关**的方法面（Flutter UI 在用在跑）：
 * - 本地数据查询面（实现见 [BridgeDataQueries]）：
 *   notificationInbox.history/markRead/markAllRead/count、
 *   capture.listCaptures/listSegments/search/getEvidencePath/storageStats/
 *   purgeBefore/compressImagesBefore、favorites.list/add/remove、
 *   backup.exportZip/importZip、getLaunchRoute（启动深链读取并消费）；
 * - 模块配置面：模块开关/全局暂停/后台服务开关（whale_modules 存储）、
 *   通知与 VoIP/通知监听权限与桥配置、采集开关与数据源、ScreenMemo MCP token
 *   （Keystore 加密存取）与数据导入、设备自主体开关与紧急停止。
 *
 * 运行时装配归 [NodeRuntimeV2]（Application 单例）；本桥不再持有任何连接面状态。
 * 所有回调在主线程执行（Flutter channel 要求）。
 */
class TransportBridge(
    context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private val appContext = context.applicationContext

    /** 仅用于运行时权限申请（requestPermissions 需 Activity）；bridge 与 Activity 同生命周期。 */
    private val activity: Activity? = context as? Activity
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)

    /** v2 运行时（Application 单例；懒解析，避免桥构造期触碰 Keystore）。 */
    private val runtime: NodeRuntimeV2 by lazy { NodeRuntimeV2.get(context) }

    /**
     * 本地数据查询面（Flutter UI 按本通道 `whale/transport`
     * 消费 notificationInbox 系/capture 系/favorites 系/backup 系/getLaunchRoute，
     * 约定见 app/lib/features/notifications/inbox_page.dart 与
     * app/lib/features/screenmemo/data/）。与 v2 桥共享同一实现。
     */
    private val dataQueries = BridgeDataQueries(
        context = appContext,
        activityProvider = { activity },
        storeProvider = { runtime.captureStore },
        inboxProvider = { NodeRuntimeV2.peek()?.inbox },
    )

    init {
        methodChannel.setMethodCallHandler(this)
    }

    /** UI 解绑：注销方法处理器（运行时与连接生命周期归 NodeRuntimeV2，不受影响）。 */
    fun dispose() {
        methodChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            // 本地数据查询面（notificationInbox.*/capture.*/favorites.*/backup.*/getLaunchRoute）
            if (dataQueries.supports(call.method)) {
                dataQueries.handle(call, result)
                return
            }
            when (call.method) {
                "setMcpToken" -> {
                    // 空白 token 视为清除；密文落盘，token 不记日志。
                    val token = call.argument<String>("token")?.trim().orEmpty()
                    val prefs = appContext.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
                    if (token.isEmpty()) {
                        prefs.edit().remove(KEY_MCP_TOKEN_BLOB).apply()
                    } else {
                        prefs.edit()
                            .putString(KEY_MCP_TOKEN_BLOB, runtime.identity.encryptString(token))
                            .apply()
                    }
                    result.success(true)
                }

                "getMcpToken" -> {
                    val blob = appContext
                        .getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_MCP_TOKEN_BLOB, null)
                    val token = blob?.let {
                        try {
                            runtime.identity.decryptString(it)
                        } catch (e: Exception) {
                            Log.w(TAG, "MCP token 解密失败（主密钥可能已失效），按未配置处理", e)
                            null
                        }
                    }
                    result.success(token)
                }

                // ---- 模块开关（通用；whale_modules/<module>_enabled，默认 true）----

                "setModuleEnabled" -> {
                    val module = call.argument<String>("module")
                    val enabled = call.argument<Boolean>("enabled")
                    if (module == null || enabled == null || !MODULE_NAME_REGEX.matches(module)) {
                        return result.error("bad_args", "非法 module/enabled 参数", null)
                    }
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("${module}_enabled", enabled).apply()
                    result.success(true)
                }

                "getModuleStates" -> {
                    // 模块开关登记（默认：notification_post true；其余 false，需显式开启）。
                    val prefs = appContext.getSharedPreferences(
                        NotificationModule.PREFS_NAME, Context.MODE_PRIVATE,
                    )
                    result.success(
                        mapOf(
                            "notification_post" to
                                prefs.getBoolean(NotificationModule.KEY_ENABLED, true),
                            // voip 默认 false（B4：首次需显式开启）
                            "voip" to
                                prefs.getBoolean(VoipModule.KEY_ENABLED, false),
                            // 通知监听默认 false（B3：独立可选，一个 App 都不读）
                            "notification_read" to
                                prefs.getBoolean(NotificationReadModule.KEY_ENABLED, false),
                            // 受控设备动作默认 false（B3）
                            "safe_action" to
                                prefs.getBoolean(SafeActionModule.KEY_ENABLED, false),
                        )
                    )
                }

                // ---- 全局暂停（A1；whale_modules/all_paused）----

                "setAllPaused" -> {
                    val paused = call.argument<Boolean>("paused")
                        ?: return result.error("bad_args", "missing paused", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PauseFlags.KEY_ALL_PAUSED, paused).apply()
                    Log.i(TAG, "全局暂停: $paused")
                    result.success(true)
                }

                "isAllPaused" -> result.success(PauseFlags.isAllPaused(appContext))

                // ---- 后台前台服务开关（whale_modules/bg_service_enabled，默认 true）----

                "setBgServiceEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    appContext
                        .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_BG_SERVICE_ENABLED, enabled).apply()
                    if (!enabled) NodeServiceV2.stop(appContext)
                    result.success(true)
                }

                "isBgServiceEnabled" -> result.success(isBgServiceEnabled(appContext))

                "getNotificationPermissionStatus" ->
                    result.success(NotificationModule.permissionStatus(appContext))

                "requestNotificationPermission" ->
                    result.success(NotificationModule.requestPermission(activity, appContext))

                // ---- VoIP 模块（B4：phone.call.ring + call.* 信令）----

                "getVoipPermissionStatus" ->
                    result.success(VoipModule.permissionStatus(appContext))

                "requestVoipPermissions" ->
                    result.success(VoipModule.requestPermissions(activity, appContext))

                "getVoipBridgeConfig" -> result.success(
                    mapOf(
                        "host" to BridgePrefs.getHost(appContext),
                        "port" to BridgePrefs.getPort(appContext),
                        "tokenConfigured" to BridgePrefs.getToken(appContext).isNotEmpty(),
                    )
                )

                "setVoipBridgeConfig" -> {
                    val host = call.argument<String>("host") ?: BridgePrefs.DEFAULT_HOST
                    val port = call.argument<Number>("port")?.toInt()
                        ?: BridgePrefs.DEFAULT_PORT
                    if (host.isBlank() || host.length > 255 || port !in 1..65535) {
                        return result.error("bad_args", "非法 bridge host/port", null)
                    }
                    // Missing token preserves it; explicit empty clears it. Never return/log it.
                    if (call.hasArgument("token")) {
                        val token = call.argument<String>("token")
                            ?: return result.error("bad_args", "非法 media token", null)
                        try {
                            BridgePrefs.setToken(appContext, token)
                        } catch (_: IllegalArgumentException) {
                            return result.error("bad_args", "非法 media token", null)
                        }
                    }
                    BridgePrefs.set(appContext, host.trim(), port)
                    result.success(true)
                }

                // ---- 通知监听模块（B3 前半：phone.notification.read）----

                "getNotificationReadPermissionStatus" ->
                    result.success(NotificationReadModule.permissionStatus(appContext))

                /**
                 * SEC-02：授权（granted/denied）与系统实际绑定（bound）分开观测，
                 * 附带缓冲/溢出计数与最近绑定时间。
                 */
                "getNotificationReadStatus" -> result.success(
                    com.wangfanlab.whale.modules.notificationread
                        .WhaleNotificationListenerService.bindingStatus(appContext)
                )

                /** 「去授权」：跳系统通知监听设置页（特殊访问权限无运行时弹窗）。 */
                "openNotificationListenerSettings" -> {
                    NotificationReadModule.openListenerSettings(appContext)
                    result.success(true)
                }

                "getNotificationReadAllowedApps" ->
                    result.success(NotificationReadModule.allowedApps(appContext).toList())

                "setNotificationReadAllowedApps" -> {
                    @Suppress("UNCHECKED_CAST")
                    val apps = call.argument<List<String>>("apps")
                        ?: return result.error("bad_args", "missing apps", null)
                    // 包名格式白名单（复用 safe_action 的包名正则；防任意字符串写入）
                    val valid = apps.filter { SafeActionValidator.PACKAGE_REGEX.matches(it) }
                    NotificationReadModule.setAllowedApps(appContext, valid.toSet())
                    result.success(true)
                }

                /** 白名单多选页数据源：带 LAUNCHER 入口的已安装 App（label+package）。 */
                "getListenableApps" ->
                    result.success(NotificationReadModule.listListenableApps(appContext))

                // ---- 影子采集模块（SM-01；默认关；影子统计不含内容数据） ----

                "getCaptureStatus" -> result.success(
                    com.wangfanlab.whale.modules.capture.CaptureModule.status(appContext)
                )

                "setCaptureEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    com.wangfanlab.whale.modules.capture.CaptureModule.setEnabled(appContext, enabled)
                    Log.i(TAG, "影子采集模块开关: $enabled")
                    result.success(true)
                }

                "openAccessibilitySettings" -> {
                    com.wangfanlab.whale.modules.capture.CaptureModule
                        .openAccessibilitySettings(appContext)
                    result.success(true)
                }

                /** SM-02：ScreenMemo 导出目录增量导入（只读旧库；幂等 checkpoint）。 */
                "importScreenMemoData" -> {
                    val base = call.argument<String>("base_path")
                        ?: return result.error("bad_args", "missing base_path", null)
                    // MethodChannel.Result 必须主线程回执；导入本身在后台线程跑
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        try {
                            val report = com.wangfanlab.whale.modules.capture.migration
                                .ScreenMemoImporter(appContext, runtime.captureStore)
                                .import(java.io.File(base))
                            main.post {
                                result.success(
                                    mapOf(
                                        "shards_scanned" to report.shardsScanned,
                                        "rows_imported" to report.rowsImported,
                                        "rows_duplicate" to report.rowsSkippedDuplicate,
                                        "rows_deleted" to report.rowsSkippedDeleted,
                                        "errors" to report.errors,
                                        "backup_dir" to report.backupDir,
                                    )
                                )
                            }
                        } catch (t: Throwable) {
                            main.post { result.error("internal", t.message, null) }
                        }
                    }.start()
                }

                // ---- 设备自主体（core#58；默认全关） ----

                "getAgentStatus" -> result.success(
                    mapOf(
                        "enabled" to com.wangfanlab.whale.modules.agent.AgentModule
                            .isEnabled(appContext),
                        "root_authorized" to com.wangfanlab.whale.modules.agent.AgentModule
                            .isRootAuthorized(appContext),
                        "estop" to com.wangfanlab.whale.modules.agent.AgentModule
                            .isEstop(appContext),
                    )
                )

                "setAgentEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    appContext.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                        .edit().putBoolean(
                            com.wangfanlab.whale.modules.agent.AgentModule.KEY_ENABLED, enabled,
                        ).apply()
                    result.success(true)
                }

                "setAgentRootAuthorized" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    appContext.getSharedPreferences("whale_modules", Context.MODE_PRIVATE)
                        .edit().putBoolean(
                            com.wangfanlab.whale.modules.agent.AgentModule.KEY_ROOT_AUTH, enabled,
                        ).apply()
                    result.success(true)
                }

                /** 紧急停止：销毁会话 + 置位（之后所有 agent 请求立即拒绝）。 */
                "agentEmergencyStop" -> {
                    com.wangfanlab.whale.modules.agent.AgentModule
                        .emergencyStop(appContext, runtime.agentModule.shell)
                    result.success(true)
                }

                "agentClearEstop" -> {
                    com.wangfanlab.whale.modules.agent.AgentModule.clearEstop(appContext)
                    result.success(true)
                }

                /** SM-03：数据源切换（mcp|builtin；builtin 前置校验不过返回 false）。 */
                "setCaptureDataSource" -> {
                    val source = call.argument<String>("source")
                        ?: return result.error("bad_args", "missing source", null)
                    val ok = com.wangfanlab.whale.modules.capture.CaptureModule
                        .setDataSource(appContext, source)
                    result.success(ok)
                }

                "setCapturePersistEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return result.error("bad_args", "missing enabled", null)
                    com.wangfanlab.whale.modules.capture.CaptureModule
                        .setPersistEnabled(appContext, enabled)
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("internal", e.message, null)
        }
    }

    companion object {
        private const val TAG = "TransportBridge"
        const val METHOD_CHANNEL = "whale/transport"

        /** ScreenMemo MCP token 加密存储（与身份 Keystore 主密钥同款 AES/GCM）。 */
        private const val MCP_PREFS_NAME = "whale_mcp"
        private const val KEY_MCP_TOKEN_BLOB = "mcp_token_enc_v1"

        /** 模块开关名白名单格式（防任意键写入 whale_modules）。 */
        private val MODULE_NAME_REGEX = Regex("^[a-z0-9_]{1,32}$")

        /** 后台前台服务开关键（whale_modules/bg_service_enabled；默认 true）。 */
        const val KEY_BG_SERVICE_ENABLED = "bg_service_enabled"

        /** 后台前台服务开关：false 时连接不启动前台服务（v2 面为 NodeServiceV2）。 */
        fun isBgServiceEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(NotificationModule.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG_SERVICE_ENABLED, true)
    }
}
