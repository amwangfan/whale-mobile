package com.wangfanlab.whale.runtime

/**
 * sensor.query 处理器链决策（BG-01，纯逻辑可单测）：
 * 1. 全局暂停中 → PAUSED（立即回 status="paused"，不分发 Dart）；
 * 2. 有 Dart 委托 → DELEGATE（转发，Dart 经 sendSensorResult 回复）；
 * 3. 无处理器 → UNAVAILABLE（WssClient fail-closed 立即回 unavailable，
 *    不会把请求丢到不存在的 UI/EventChannel）。
 */
object SensorQueryDispatch {

    enum class Decision { PAUSED, DELEGATE, UNAVAILABLE }

    fun decide(allPaused: Boolean, hasDelegate: Boolean): Decision = when {
        allPaused -> Decision.PAUSED
        hasDelegate -> Decision.DELEGATE
        else -> Decision.UNAVAILABLE
    }
}
