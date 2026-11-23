package com.wangfanlab.whale.runtime.v2

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NodeServiceV2 前台化看门狗纪律测试（nova 回归 2026-11-25：
 * startForegroundService 后 ~5s 内未成功 startForeground →
 * RemoteServiceException 杀进程）。
 *
 * 三条防线逐项钉死：
 * 1. onCreate 即 promoteForeground（不等 onStartCommand 第二跳）；
 * 2. startForeground 失败 → stopSelf 自毁撤看门狗（不留普通服务残躯）；
 * 3. 进程非前台态不调 startForegroundService（不误触启动）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeServiceV2ForegroundTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NodeServiceV2.resetRunningForTest()
        // Robolectric 每个测试方法重置沙箱（含 instrumentation 启动记录），无需手清。
    }

    @After
    fun tearDown() {
        NodeServiceV2.resetRunningForTest()
    }

    private fun setOwnImportance(importance: Int) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val proc = ActivityManager.RunningAppProcessInfo().apply {
            processName = context.packageName
            this.importance = importance
        }
        shadowOf(am).setProcesses(listOf(proc))
    }

    // ---------------------------------------------------------------- 防线 3

    @Test
    fun `门卫判定：前台与前台服务态允许，其余一律不允许`() {
        assertTrue(
            ForegroundStartGuard.mayStartForegroundService(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ),
        )
        assertTrue(
            ForegroundStartGuard.mayStartForegroundService(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            ),
        )
        assertFalse(
            ForegroundStartGuard.mayStartForegroundService(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND,
            ),
        )
        assertFalse(
            ForegroundStartGuard.mayStartForegroundService(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            ),
        )
        assertFalse(
            ForegroundStartGuard.mayStartForegroundService(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING,
            ),
        )
        // 取不到进程态（null）：宁缺毋滥，不允许
        assertFalse(ForegroundStartGuard.mayStartForegroundService(null))
    }

    @Test
    fun `后台进程态：start() 不调 startForegroundService（不误触启动）`() {
        setOwnImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND)
        val dispatched = NodeServiceV2.start(context)
        assertFalse("后台态不得发起前台服务启动", dispatched)
        assertFalse(NodeServiceV2.isRunning)
    }

    @Test
    fun `前台进程态：start() 正常发起前台服务启动`() {
        setOwnImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val dispatched = NodeServiceV2.start(context)
        assertTrue("前台态必须发起前台服务启动", dispatched)
    }

    // ---------------------------------------------------------------- 防线 1

    @Test
    fun `onCreate 即前台化（不等 onStartCommand 第二跳）`() {
        val service = Robolectric.buildService(NodeServiceV2::class.java).create().get()
        assertEquals(
            "onCreate 后必须已 startForeground",
            1002,
            shadowOf(service).lastForegroundNotificationId,
        )
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(NodeServiceV2.isRunning)
        service.stopSelf()
    }

    @Test
    fun `onStartCommand 重复 promote 幂等（不重复抛错）`() {
        val service = Robolectric.buildService(NodeServiceV2::class.java)
            .create()
            .startCommand(0, 0)
            .get()
        assertEquals(1002, shadowOf(service).lastForegroundNotificationId)
        assertTrue(NodeServiceV2.isRunning)
        service.stopSelf()
    }

    // ---------------------------------------------------------------- 防线 2

    @Test
    fun `前台化失败路径：onPromoteForegroundFailed 立即 stopSelf 自毁`() {
        val service = Robolectric.buildService(NodeServiceV2::class.java).create().get()
        // 直接调用失败处理（模拟 startForeground 抛 ForegroundServiceStartNotAllowedException）
        service.onPromoteForegroundFailed(
            android.app.ForegroundServiceStartNotAllowedException("simulated"),
            "test",
        )
        assertTrue("前台化失败必须 stopSelf 撤看门狗", shadowOf(service).isStoppedBySelf)
        assertFalse(NodeServiceV2.isRunning)
    }

    @Test
    fun `ACTION_STOP 正常停止且退出前台`() {
        val service = Robolectric.buildService(NodeServiceV2::class.java).create().get()
        service.onStartCommand(
            Intent().setAction("com.wangfanlab.whale.runtime.v2.action.STOP"),
            0,
            0,
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(NodeServiceV2.isRunning)
    }

    @Test
    fun `onDestroy 清 running 标记`() {
        val service = Robolectric.buildService(NodeServiceV2::class.java).create().get()
        assertTrue(NodeServiceV2.isRunning)
        service.onDestroy()
        assertFalse(NodeServiceV2.isRunning)
    }
}
