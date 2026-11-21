package com.wangfanlab.whale.modules.agent2

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.wangfanlab.whale.modules.notificationread.WhaleNotificationListenerService

/**
 * 媒体会话直控（#96 播放控制收敛）：经系统 MediaSessionManager 读取/控制
 * 其他 App 的活跃媒体会话，为 GUI 播放控制提供确定性语义通道。
 *
 * 动机（nova GSI 高负载实证，Core #96）：
 * - 播放器控制条唤出后 3-5s 自隐，而一次观察往返 ~30s——暂停钮在观察里
 *   永远过期（§8 重校验必 stale），进度条盲拖既不可执行也不可验证；
 * - 播放/暂停状态对 UI 树不可见，「确认正在播放/已暂停」无判定信号。
 * MediaSession 通道绕开瞬态控件：play/pause/seek 是媒体会话的一等操作，
 * 状态/进度可直接读，天然带确定性验证。
 *
 * 授权面：读取/控制他方会话要求本 App 的通知监听已授权且系统已绑定
 * [WhaleNotificationListenerService]（复用既有授权，不新增权限申请面）。
 * 未授权/无会话/目标歧义一律诚实 unavailable/failed，绝不盲控。
 *
 * 与既有通道的关系：root 版 `device.key` 媒体键仍走 `input keyevent`
 * （零行为变化）；本模块服务无 root 变体（daily / GSI 无 su 场景）。
 */
interface MediaSessionControl {

    /** 活跃媒体会话快照（state 为 PlaybackState.STATE_* 原值，Int 透传）。 */
    data class SessionInfo(
        val packageName: String,
        val state: Int,
        /** 播放位置 ms；<0 = 会话未报告。 */
        val positionMs: Long,
        /** 媒体总长 ms；<0 = 会话未报告。 */
        val durationMs: Long,
        /** 标题（截断 40；与观察元素 text 同类的屏幕内容，不落更长正文）。 */
        val title: String,
    )

    /** 通知监听授权+绑定可用（false = 不可读，调用方回 unavailable）。 */
    fun available(): Boolean

    /** 活跃会话快照；null=读取失败（未授权/异常），空表=无活跃会话。 */
    fun sessions(): List<SessionInfo>?

    /** 对指定包名会话切播放/暂停；false=下发失败。 */
    fun togglePlayPause(packageName: String): Boolean

    /** 对指定包名会话下一曲；false=下发失败。 */
    fun skipToNext(packageName: String): Boolean

    /** 对指定包名会话上一曲；false=下发失败。 */
    fun skipToPrevious(packageName: String): Boolean

    /**
     * 对指定包名会话 seek 到绝对位置（device.media.seek_to 契约增项，
     * transportControls.seekTo 直控）；false=下发失败。
     */
    fun seekTo(packageName: String, positionMs: Long): Boolean
}

/** 目标选择与摘要（纯逻辑，无 Android 依赖，可单测）。 */
object MediaSessionLogic {

    // android.media.session.PlaybackState 常量镜像（纯逻辑不引 Android 依赖）。
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6

    const val TITLE_MAX_CHARS = 40

    /** device.media.seek_to 的 verify 容差（§5.1 增项：落点 ±2s 内 passed）。 */
    const val SEEK_VERIFY_TOLERANCE_MS = 2_000L

    fun isPlaying(state: Int): Boolean = state == STATE_PLAYING

    fun stateName(state: Int): String = when (state) {
        STATE_PLAYING -> "播放中"
        STATE_PAUSED -> "已暂停"
        STATE_BUFFERING -> "缓冲中"
        else -> "其他($state)"
    }

    /**
     * 目标会话选择（保守，歧义即 null 交由调用方诚实失败）：
     * 1. 前台包 + 播放中；2. 前台包任意态（agent 作用于所见应用）；
     * 3. 唯一播放中；4. 全局唯一会话；5. 其余（无会话/多会话歧义）→ null。
     */
    fun selectTarget(
        sessions: List<MediaSessionControl.SessionInfo>,
        foreground: String?,
    ): MediaSessionControl.SessionInfo? {
        if (sessions.isEmpty()) return null
        foreground?.let { fg ->
            sessions.firstOrNull { it.packageName == fg && isPlaying(it.state) }
                ?.let { return it }
            sessions.firstOrNull { it.packageName == fg }?.let { return it }
        }
        sessions.singleOrNull { isPlaying(it.state) }?.let { return it }
        return sessions.singleOrNull()
    }

    /**
     * seek 目标解算（device.media.seek_to 契约增项，纯逻辑可单测）：
     * position_ms 优先（审注①：ratio 依赖 duration 可得性）；ratio 换算
     * ratio×duration 并夹取到 [0,duration]；duration 未知（≤0）返回 null，
     * 交由调用方诚实 failed——绝不估算（审注③）。
     */
    fun resolveSeekTargetMs(positionMs: Long?, ratio: Double?, durationMs: Long): Long? {
        positionMs?.let { return it }
        val r = ratio ?: return null
        if (durationMs <= 0) return null
        return (r * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /** 紧凑摘要（入观察负载/回执摘要）：「播放中 83/613s tv.danmaku.bili 标题」。 */
    fun summaryOf(s: MediaSessionControl.SessionInfo): String {
        val sb = StringBuilder(stateName(s.state))
        if (s.positionMs >= 0) {
            sb.append(' ').append(s.positionMs / 1000)
            if (s.durationMs > 0) sb.append('/').append(s.durationMs / 1000)
            sb.append('s')
        }
        sb.append(' ').append(s.packageName)
        if (s.title.isNotEmpty()) sb.append(' ').append(s.title)
        return sb.toString()
    }
}

/** MediaSessionManager 生产实现（通知监听 ComponentName 授权路径）。 */
class AndroidMediaSessionControl(context: Context) : MediaSessionControl {

    private val appContext = context.applicationContext
    private val listenerComponent =
        ComponentName(appContext, WhaleNotificationListenerService::class.java)

    private val manager: MediaSessionManager?
        get() = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    override fun available(): Boolean = sessions() != null

    override fun sessions(): List<MediaSessionControl.SessionInfo>? {
        val mgr = manager ?: return null
        return try {
            mgr.getActiveSessions(listenerComponent).map { it.toInfo() }
        } catch (t: SecurityException) {
            // 通知监听未授权/未绑定：诚实不可读
            Log.w(TAG, "媒体会话读取被拒（通知监听未授权）")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "媒体会话读取失败: ${t.message}")
            null
        }
    }

    override fun togglePlayPause(packageName: String): Boolean {
        val ctrl = controllerFor(packageName) ?: return false
        // 平台 TransportControls 无 playPause()（playPause 属 androidx compat 层）：
        // 按当前态选 play/pause；播放中/缓冲中视为活跃 → pause，否则 play。
        val active = runCatching {
            val st = ctrl.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        }.getOrDefault(false)
        return try {
            if (active) ctrl.transportControls.pause() else ctrl.transportControls.play()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "play/pause 下发失败: ${t.message}")
            false
        }
    }

    override fun skipToNext(packageName: String): Boolean =
        controllerFor(packageName)?.let { ctrl ->
            runCatching { ctrl.transportControls.skipToNext() }.isSuccess
        } == true

    override fun skipToPrevious(packageName: String): Boolean =
        controllerFor(packageName)?.let { ctrl ->
            runCatching { ctrl.transportControls.skipToPrevious() }.isSuccess
        } == true

    override fun seekTo(packageName: String, positionMs: Long): Boolean =
        controllerFor(packageName)?.let { ctrl ->
            runCatching { ctrl.transportControls.seekTo(positionMs) }.isSuccess
        } == true

    /** 按包名现取控制器（两次快照间会话可能生灭，现取现用）。 */
    private fun controllerFor(packageName: String): MediaController? {
        val mgr = manager ?: return null
        return try {
            mgr.getActiveSessions(listenerComponent)
                .firstOrNull { it.packageName == packageName }
        } catch (t: Throwable) {
            null
        }
    }

    private fun MediaController.toInfo(): MediaSessionControl.SessionInfo {
        val pb = playbackState
        val meta = metadata
        val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?.take(MediaSessionLogic.TITLE_MAX_CHARS) ?: ""
        return MediaSessionControl.SessionInfo(
            packageName = packageName,
            state = pb?.state ?: PlaybackState.STATE_NONE,
            positionMs = pb?.position ?: -1L,
            durationMs = meta?.getLong(
                android.media.MediaMetadata.METADATA_KEY_DURATION,
            ) ?: -1L,
            title = title,
        )
    }

    private companion object {
        const val TAG = "WhaleMediaSession"
    }
}
