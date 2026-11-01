package com.wangfanlab.whale.modules.authconfirm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wangfanlab.whale.runtime.NodeRuntime

/**
 * 可信确认界面（AUTH-01）：锁屏可显示的全屏确认页。
 *
 * 展示请求主体/来源会话/动作 scope/有效期，用户明确同意或拒绝；
 * 不存在默认批准路径。
 */
class ConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val module = NodeRuntime.peek()?.authConfirmModule
        val c = module?.currentPending()
        if (module == null || c == null) {
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        fun line(label: String, value: String) {
            layout.addView(TextView(this).apply {
                text = "$label：$value"
                textSize = 16f
                setPadding(0, 8, 0, 8)
            })
        }
        layout.addView(TextView(this).apply {
            text = "Whale 操作确认"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        })
        line("请求主体", c.principalId)
        line("来源会话", "${c.channel} / ${c.conversationId ?: "-"}")
        line("请求权限", c.scopes.joinToString(", "))
        c.actionFingerprint?.let { line("动作指纹", it) }
        val remainSec = ((c.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        line("有效期", "${remainSec}s（超时自动拒绝）")

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "拒绝"
            setOnClickListener {
                module.deny()
                finish()
            }
        })
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(Button(this).apply {
            text = "同意"
            setOnClickListener {
                module.approve()
                finish()
            }
        })
        layout.addView(buttons)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    companion object {
        fun buildIntent(context: Context, c: AuthConfirmModule.Challenge): Intent =
            Intent(context, ConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** 全屏弹出（服务/通知外入口）。 */
        fun show(context: Context, c: AuthConfirmModule.Challenge) {
            try {
                context.startActivity(buildIntent(context, c))
            } catch (_: Throwable) {
                // 后台启动限制下拉起失败时，通知栏入口仍在
            }
        }
    }
}
