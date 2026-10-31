package com.wangfanlab.whale.runtime

/**
 * 后台恢复决策（BG-02，纯逻辑可单测）。
 *
 * 自动恢复连接只在全部满足时发生：
 * - 掉线前用户处于已连接状态（was_connected）；
 * - 未处于一键暂停（A1 全局暂停优先于一切后台任务）；
 * - 设备未被撤销（revoked 后必须重新配对，绝不擅自重连）。
 *
 * 本策略只回答「该不该恢复」；能不能拉起前台服务由系统决定
 * （Android 12+ 后台 FGS 启动限制、Android 15 dataSync 时长限制），
 * 失败时降级为进程内连接，不做任何绕过。
 */
object RecoveryPolicy {

    fun shouldRecover(
        wasConnected: Boolean,
        allPaused: Boolean,
        revoked: Boolean,
    ): Boolean = wasConnected && !allPaused && !revoked
}
