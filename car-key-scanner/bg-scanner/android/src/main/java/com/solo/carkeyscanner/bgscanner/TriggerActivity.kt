package com.solo.carkeyscanner.bgscanner

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager

/**
 * 息屏触发的透明执行页：
 * showWhenLocked + turnScreenOn → 锁屏/灭屏时被拉起会自动点亮屏幕，
 * 然后执行通知按钮点击链路；若触发前屏幕是灭的，点击完成后自动锁屏回到黑屏。
 */
class TriggerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val i = intent ?: run { finish(); return }
        val unlock = i.getBooleanExtra("unlock", true)
        val dist = i.getStringExtra("dist") ?: ""
        val dur = i.getLongExtra("dur", 1)
        val wasOff = i.getBooleanExtra("wasOff", true)

        TriggerRunner.execute(this, unlock, dist, dur, true) { body ->
            TriggerRunner.notifyResult(this, unlock, body)
            if (wasOff) {
                // 点击完成，回到黑屏（无障碍 GLOBAL_ACTION_LOCK_SCREEN）
                handler.postDelayed({
                    KeyClickService.instance?.lockScreen()
                    finish()
                }, 1000)
            } else {
                finish()
            }
        }
    }
}
