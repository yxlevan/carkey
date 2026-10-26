package com.solo.carkeyscanner.bgscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray

/**
 * 触发执行器：供 BgScannerService（亮屏路径）与 TriggerActivity（息屏路径）共用。
 * 执行链：配置的按钮点击 → 同义词兜底 → 无障碍拉通知栏点击（含手势坐标兜底）。
 */
object TriggerRunner {

    const val CH_PROX = "proximity"
    const val ID_ENTER = 1001
    const val ID_LEAVE = 1002
    const val ID_FSI = 1003

    data class WatchApp(
        val pkg: String,
        val name: String,
        val unlockTitle: String,
        val lockTitle: String
    )

    // 防并发重入：直接拉起 + FSI 兜底两条路可能同时到达
    @Volatile private var busyUntil = 0L
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun watchApps(ctx: Context): List<WatchApp> {
        val raw = prefs(ctx).getString("watchApps", "[]") ?: "[]"
        val out = ArrayList<WatchApp>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.optString("package").trim()
                if (pkg.isEmpty()) continue
                out.add(
                    WatchApp(
                        pkg = pkg,
                        name = o.optString("name").ifBlank { pkg },
                        unlockTitle = o.optString("unlockTitle", "解锁").ifBlank { "解锁" },
                        lockTitle = o.optString("lockTitle", "闭锁").ifBlank { "闭锁" }
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    private fun prefs(c: Context) = c.getSharedPreferences("bgscanner", Context.MODE_PRIVATE)

    private fun isBusy() = System.currentTimeMillis() < busyUntil

    /** 直接拉起透明触发页（授予悬浮窗权限后，后台/息屏均可成功） */
    fun launchViaActivity(ctx: Context, unlock: Boolean, distText: String, durSec: Long, wasOff: Boolean) {
        val i = Intent(ctx, TriggerActivity::class.java)
            .putExtra("unlock", unlock)
            .putExtra("dist", distText)
            .putExtra("dur", durSec)
            .putExtra("wasOff", wasOff)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    /** 全屏意图通知兜底（息屏时系统会直接全屏拉起触发页） */
    fun postFullScreenIntent(ctx: Context, unlock: Boolean, distText: String, durSec: Long, wasOff: Boolean) {
        if (isBusy()) return
        val i = Intent(ctx, TriggerActivity::class.java)
            .putExtra("unlock", unlock)
            .putExtra("dist", distText)
            .putExtra("dur", durSec)
            .putExtra("wasOff", wasOff)
        val pi = PendingIntent.getActivity(
            ctx, if (unlock) 31 else 32, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(ctx)
        val action = if (unlock) "解锁" else "闭锁"
        val n = NotificationCompat.Builder(ctx, CH_PROX)
            .setContentTitle("星舰蓝牙助手")
            .setContentText("已满足条件，正在尝试「$action」…")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID_FSI, n)
        } catch (_: SecurityException) {}
    }

    /** 执行一次触发（阻塞，内部已切子线程则由调用方保证）；返回结果文案 */
    fun run(ctx: Context, unlock: Boolean, distText: String, durSec: Long, notifyEnabled: Boolean): String {
        if (isBusy()) return "busy"
        busyUntil = System.currentTimeMillis() + 12_000
        try {
            return runInner(ctx, unlock, distText, durSec, notifyEnabled)
        } finally {
            busyUntil = 0
        }
    }

    /** 异步执行（TriggerActivity 用），回调在触发线程 */
    fun execute(ctx: Context, unlock: Boolean, distText: String, durSec: Long, notifyEnabled: Boolean, done: (String) -> Unit) {
        exec.execute { done(run(ctx, unlock, distText, durSec, notifyEnabled)) }
    }

    private fun runInner(ctx: Context, unlock: Boolean, distText: String, durSec: Long, notifyEnabled: Boolean): String {
        val apps = watchApps(ctx)
        val actionName = if (unlock) "解锁" else "闭锁"
        val condText = (if (unlock) "已进入解锁范围" else "已离开闭锁范围") +
            "（$distText，持续 ${durSec} s）"

        val statuses = ArrayList<String>()
        val codes = ArrayList<String>()
        if (apps.isNotEmpty()) {
            for (app in apps) {
                val r = tryClick(ctx, app, unlock)
                codes.add(r)
                statuses.add("${app.name}: ${statusText(r)}")
                if (r == "ok" || r == "a11y_ok") break // 成功一个即可
            }
        }

        val ok = codes.any { it == "ok" || it == "a11y_ok" }
        return when {
            ok -> "$condText，已自动点击「$actionName」"
            apps.isNotEmpty() -> "$condText，未点击（${statuses.joinToString("；")}）"
            notifyEnabled -> condText
            else -> ""
        }
    }

    /** 写入最近结果并发通知 */
    fun notifyResult(ctx: Context, unlock: Boolean, body: String) {
        if (body.isEmpty()) return
        prefs(ctx).edit()
            .putString("lastResult", "${System.currentTimeMillis() / 1000}|$body")
            .apply()
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CH_PROX)
            .setContentTitle("星舰蓝牙助手")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(if (unlock) ID_ENTER else ID_LEAVE, n)
        } catch (_: SecurityException) {}
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_PROX, "范围提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
    }

    /** 先按配置标题点，失败后用同义词兜底；再不行走无障碍拉通知栏点击 */
    fun tryClick(ctx: Context, app: WatchApp, unlock: Boolean): String {
        val primary = if (unlock) app.unlockTitle else app.lockTitle
        var r = KeyNotificationListener.clickAction(ctx, app.pkg, primary)
        if (r == "ok") return r
        val alts = if (unlock) KeyNotificationListener.UNLOCK_WORDS else KeyNotificationListener.LOCK_WORDS
        for (w in alts) {
            if (w == primary) continue
            r = KeyNotificationListener.clickAction(ctx, app.pkg, w)
            if (r == "ok") return r
        }
        // 自定义 RemoteViews 布局的通知拿不到标准按钮，走无障碍兜底点击
        if (KeyClickService.isEnabled(ctx)) {
            val svc = KeyClickService.instance ?: return "a11y_unbound:$r"
            val words = distinctWords(primary, alts)
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            try {
                svc.clickKeyword(words, app.name) { ok = it; latch.countDown() }
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {}
            return if (ok) "a11y_ok" else "a11y_fail:$r"
        }
        return r
    }

    /** 关键字去重并去掉过短（单字）的词，避免误点其他文字 */
    private fun distinctWords(primary: String, alts: List<String>): List<String> {
        val out = ArrayList<String>()
        for (w in listOf(primary) + alts) {
            val t = w.trim()
            if (t.length < 2) continue
            if (!out.contains(t)) out.add(t)
        }
        return out
    }

    fun statusText(code: String): String = when {
        code == "ok" -> "已点击"
        code == "a11y_ok" -> "已通过无障碍点击"
        code.startsWith("a11y_fail") -> "无障碍点击失败（${code.substringAfter(":")}）"
        code.startsWith("a11y_unbound") -> "无障碍服务未连接（请重开一次无障碍开关）"
        code == "listener_not_connected" -> "监听服务未连接（请在系统设置授予通知使用权后重开本 App）"
        code == "no_notification" -> "未找到该 App 通知"
        code == "no_actions" -> "通知上没有按钮"
        code == "no_matching_action" -> "未找到匹配的按钮"
        code.startsWith("canceled") -> "按钮已失效（${code.removePrefix("canceled:")}）"
        else -> code
    }
}
