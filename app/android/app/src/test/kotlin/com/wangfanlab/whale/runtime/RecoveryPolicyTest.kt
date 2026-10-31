package com.wangfanlab.whale.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BG-02 恢复策略单测：曾连接 && 未暂停 && 未撤销 三者齐备才自动重连。 */
class RecoveryPolicyTest {

    @Test
    fun `曾连接且未暂停未撤销时恢复`() {
        assertTrue(RecoveryPolicy.shouldRecover(wasConnected = true, allPaused = false, revoked = false))
    }

    @Test
    fun `用户主动断开不恢复`() {
        assertFalse(RecoveryPolicy.shouldRecover(wasConnected = false, allPaused = false, revoked = false))
    }

    @Test
    fun `一键暂停优先于恢复`() {
        assertFalse(RecoveryPolicy.shouldRecover(wasConnected = true, allPaused = true, revoked = false))
    }

    @Test
    fun `设备撤销后不擅自重连`() {
        assertFalse(RecoveryPolicy.shouldRecover(wasConnected = true, allPaused = false, revoked = true))
    }

    @Test
    fun `全部否定条件叠加不恢复`() {
        assertFalse(RecoveryPolicy.shouldRecover(wasConnected = false, allPaused = true, revoked = true))
    }
}
