package com.solo.carkeyscanner.bgscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 开机自启：上次开启了后台感知则自动拉起服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val p = context.getSharedPreferences("bgscanner", Context.MODE_PRIVATE)
            if (p.getBoolean("bgEnabled", false)) {
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, BgScannerService::class.java)
                    )
                } catch (_: Exception) {
                    /* 系统限制后台拉起时静默失败，用户下次打开 App 会恢复 */
                }
            }
        }
    }
}
