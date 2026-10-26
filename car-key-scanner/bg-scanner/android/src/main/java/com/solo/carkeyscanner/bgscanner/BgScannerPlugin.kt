package com.solo.carkeyscanner.bgscanner

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "BgScanner")
class BgScannerPlugin : Plugin() {

    @PluginMethod
    fun start(call: PluginCall) {
        try {
            val i = Intent(context, BgScannerService::class.java)
            ContextCompat.startForegroundService(context, i)
            call.resolve()
        } catch (e: Exception) {
            call.reject("start failed: ${e.message}")
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        context.stopService(Intent(context, BgScannerService::class.java))
        context.getSharedPreferences("bgscanner", 0).edit().putBoolean("bgEnabled", false).apply()
        call.resolve()
    }

    @PluginMethod
    fun configure(call: PluginCall) {
        val p = context.getSharedPreferences("bgscanner", 0).edit()
        p.putFloat("unlockDist", call.getDouble("unlockDist", 2.0)!!.toFloat())
        p.putFloat("lockDist", call.getDouble("lockDist", 3.0)!!.toFloat())
        p.putFloat("durationSec", call.getDouble("durationSec", 0.5)!!.toFloat())
        p.putInt("txPower", call.getInt("txPower", -59)!!)
        p.putBoolean("notifyEnabled", call.getBoolean("notifyEnabled", true)!!)
        p.putStringSet("starred", toSet(call.getArray("starred")))
        p.putStringSet("customNames", toSet(call.getArray("customNames")))
        // watchApps: [{package, name, unlockTitle, lockTitle}]
        val arr = JSONArray()
        call.getArray("watchApps")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val t = JSONObject()
                t.put("package", o.optString("package"))
                t.put("name", o.optString("name"))
                t.put("unlockTitle", o.optString("unlockTitle", "解锁"))
                t.put("lockTitle", o.optString("lockTitle", "闭锁"))
                arr.put(t)
            }
        }
        p.putString("watchApps", arr.toString())
        p.putBoolean("bgEnabled", true)
        p.apply()
        // 若服务在跑，通知其重载配置
        val i = Intent(context, BgScannerService::class.java).setAction("reconfigure")
        try { ContextCompat.startForegroundService(context, i) } catch (_: Exception) {}
        call.resolve()
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        call.resolve(JSObject().put("running", BgScannerService.running))
    }

    /** 打开系统「通知使用权」设置页 */
    @PluginMethod
    fun openNotifAccess(call: PluginCall) {
        try {
            val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
        call.resolve()
    }

    /** 通知使用权是否已授予本应用 */
    @PluginMethod
    fun isNotifAccessGranted(call: PluginCall) {
        val granted = KeyNotificationListener.isGranted(context)
        call.resolve(JSObject().put("granted", granted))
    }

    /** 诊断：监听服务绑定状态 + 目标 App 通知及按钮标题 */
    @PluginMethod
    fun diagnose(call: PluginCall) {
        val pkgs = watchedPackages()
        val o = JSObject()
        o.put("granted", KeyNotificationListener.isGranted(context))
        o.put("connected", KeyNotificationListener.isConnected())
        o.put("apps", KeyNotificationListener.snapshot(pkgs))
        call.resolve(o)
    }

    /** 手动测试：标准按钮点击失败时自动走无障碍兜底。放子线程等待，避免阻塞主线程。 */
    @PluginMethod
    fun testAction(call: PluginCall) {
        val pkg = call.getString("package") ?: ""
        val title = call.getString("title") ?: ""
        Thread {
            var r = KeyNotificationListener.clickAction(context, pkg, title)
            if (r != "ok") {
                if (!KeyClickService.isEnabled(context)) {
                    r = "a11y_disabled:$r"
                } else {
                    val svc = KeyClickService.instance
                    if (svc == null) {
                        r = "a11y_unbound:$r"
                    } else {
                        val words = if (title.contains("解") || title.contains("开")) {
                            listOf(title) + KeyNotificationListener.UNLOCK_WORDS
                        } else {
                            listOf(title) + KeyNotificationListener.LOCK_WORDS
                        }
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var ok = false
                        try {
                            svc.clickKeyword(words.distinct(), null) { ok = it; latch.countDown() }
                            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (_: Exception) {}
                        r = if (ok) "a11y_ok" else "a11y_fail:$r"
                    }
                }
            }
            call.resolve(JSObject().put("result", r))
        }.start()
    }

    /** 重新请求系统绑定监听服务 */
    @PluginMethod
    fun rebindListener(call: PluginCall) {
        KeyNotificationListener.requestBind(context)
        call.resolve()
    }

    /** 无障碍兜底服务是否已开启 */
    @PluginMethod
    fun isA11yEnabled(call: PluginCall) {
        call.resolve(JSObject().put("enabled", KeyClickService.isEnabled(context)))
    }

    /** 悬浮窗（显示在应用上层）权限：授予后息屏/后台可直接拉起触发页 */
    @PluginMethod
    fun isOverlayGranted(call: PluginCall) {
        call.resolve(JSObject().put("granted", android.provider.Settings.canDrawOverlays(context)))
    }

    /** 打开系统悬浮窗权限设置页 */
    @PluginMethod
    fun openOverlaySettings(call: PluginCall) {
        try {
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
        call.resolve()
    }

    /** 打开系统无障碍设置页 */
    @PluginMethod
    fun openA11ySettings(call: PluginCall) {
        try {
            val i = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
        call.resolve()
    }

    private fun watchedPackages(): List<String> {
        val raw = prefs(context).getString("watchApps", "[]") ?: "[]"
        val out = ArrayList<String>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i).optString("package")
                if (p.isNotBlank()) out.add(p)
            }
        } catch (_: Exception) {}
        return out
    }

    /** 已安装的第三方应用列表（用于选择要监听的车控 App） */
    @PluginMethod
    fun getInstalledApps(call: PluginCall) {
        val pm = context.packageManager
        val apps = JSONArray()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        for (ri in list) {
            val o = JSONObject()
            o.put("package", ri.activityInfo.packageName)
            o.put("label", ri.loadLabel(pm).toString())
            apps.put(o)
        }
        call.resolve(JSObject().put("apps", apps))
    }

    private fun toSet(arr: JSArray?): Set<String> {
        val s = HashSet<String>()
        if (arr != null) {
            val list = arr.toList<String>()
            for (v in list) s.add(v)
        }
        return s
    }

    companion object {
        fun prefs(c: Context) = c.getSharedPreferences("bgscanner", Context.MODE_PRIVATE)
    }
}
