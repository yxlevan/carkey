package com.solo.carkeyscanner.bgscanner

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * 车控 App 常驻通知监听服务。
 * 只按包名 + 动作按钮标题匹配（如「解锁」「闭锁」），点击时直接 send 通知上的 PendingIntent。
 * 提供 clickAction / snapshot 两个静态方法供前台服务与诊断界面使用。
 */
class KeyNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: KeyNotificationListener? = null

        val UNLOCK_WORDS = listOf("解锁", "开锁", "解锁车门", "开车门")
        val LOCK_WORDS = listOf("闭锁", "关锁", "上锁", "闭锁车门", "锁车")

        /** 是否已授予通知使用权（设置里的开关） */
        fun isGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        /** 监听服务是否已被系统绑定 */
        fun isConnected(): Boolean = instance != null

        /** 尝试让系统重新绑定监听服务（授权后未生效时用） */
        fun requestBind(context: Context) {
            try {
                requestRebind(ComponentName(context, KeyNotificationListener::class.java))
            } catch (_: Exception) {}
        }

        private fun matchTitle(actionTitle: String, want: String): Boolean {
            val a = actionTitle.trim()
            val w = want.trim()
            if (a.isEmpty() || w.isEmpty()) return false
            return a.equals(w, true) || a.contains(w, true)
        }

        /** 该 App 当前是否有任一给定标题的动作按钮 */
        fun hasAction(context: Context, pkg: String, vararg titles: String): Boolean {
            val nls = instance ?: return false
            return try {
                for (sbn in nls.activeNotifications ?: return false) {
                    if (sbn.packageName != pkg) continue
                    for (a in sbn.notification.actions ?: continue) {
                        val t = (a.title ?: "").toString()
                        for (want in titles) if (matchTitle(t, want)) return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 点击目标 App 通知上标题匹配的按钮。
         * 返回状态码：ok / listener_not_connected / no_notification / no_actions /
         *            no_matching_action / canceled:<msg> / error:<msg>
         */
        fun clickAction(context: Context, pkg: String, want: String): String {
            val nls = instance
            if (nls == null) {
                requestBind(context)
                return "listener_not_connected"
            }
            return try {
                val targets = (nls.activeNotifications ?: emptyArray())
                    .filter { it.packageName == pkg }
                if (targets.isEmpty()) return "no_notification"
                // 优先常驻（isOngoing）通知
                val ordered = targets.sortedByDescending { it.isOngoing }
                var actionCount = 0
                for (sbn in ordered) {
                    val actions = sbn.notification.actions ?: continue
                    for (a in actions) {
                        val t = (a.title ?: "").toString()
                        if (t.isBlank()) continue
                        actionCount++
                        if (matchTitle(t, want)) {
                            val pi = a.actionIntent
                                ?: return "no_pending_intent"
                            pi.send()
                            return "ok"
                        }
                    }
                }
                if (actionCount == 0) "no_actions" else "no_matching_action"
            } catch (e: Exception) {
                "canceled:" + (e.message ?: e.javaClass.simpleName)
            }
        }

        /** 诊断快照：目标包当前通知的标题文本与动作按钮标题（不返回正文） */
        fun snapshot(packages: List<String>): JSONArray {
            val out = JSONArray()
            val nls = instance ?: return out
            try {
                val actives = nls.activeNotifications ?: return out
                for (sbn in actives) {
                    if (packages.isNotEmpty() && !packages.contains(sbn.packageName)) continue
                    if (packages.isEmpty() && sbn.packageName == "android") continue
                    val o = JSONObject()
                    o.put("package", sbn.packageName)
                    o.put("ongoing", sbn.isOngoing)
                    val ex = sbn.notification.extras
                    o.put("text", ex?.getCharSequence("android.title")?.toString() ?: "")
                    // 自定义 RemoteViews 布局（此布局里的"按钮"不是标准 Action，需要无障碍兜底点击）
                    o.put(
                        "customView",
                        sbn.notification.contentView != null || sbn.notification.bigContentView != null
                    )
                    val acts = JSONArray()
                    sbn.notification.actions?.forEach { a ->
                        val t = (a.title ?: "").toString()
                        if (t.isNotBlank()) acts.put(t)
                    }
                    o.put("actions", acts)
                    out.put(o)
                }
            } catch (_: Exception) {}
            return out
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        requestBind(this)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
