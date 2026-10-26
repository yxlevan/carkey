package com.solo.carkeyscanner.bgscanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍兜底服务：当车控 App 的通知是自定义布局（拿不到标准 Notification.Action）时，
 * 拉下通知栏，按文字找到「解锁 / 闭锁」控件并点击，再收起通知栏。
 *
 * 说明：
 * - 系统通知栏属于 systemui 窗口，必须在该窗口内查找，避免误点其他界面；
 * - 部分自定义布局的控件 isClickable=false，此时按控件坐标做手势点击兜底；
 * - 所有操作都在主线程 Handler 中执行，调用方（后台服务）不得阻塞主线程。
 */
class KeyClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyClickService? = null

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.contains(context.packageName)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var openedShade = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 点击由轮询完成，这里无需处理事件
    }

    /** 拉下通知栏并点击含任一关键字的控件；无论成功失败都会回调一次。lockAfter=true 时点完自动锁屏 */
    fun clickKeyword(words: List<String>, appName: String?, lockAfter: Boolean = false, done: (Boolean) -> Unit) {
        if (running) {
            done(false)
            return
        }
        running = true
        handler.post { startClick(words, lockAfter, done) }
    }

    private fun startClick(words: List<String>, lockAfter: Boolean, done: (Boolean) -> Unit) {
        try {
            val rootNow = shadeRoot()
            openedShade = rootNow == null
            if (openedShade) {
                try { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) } catch (_: Exception) {}
            }
            var attempts = 0
            val maxAttempts = 20 // 20 * 250ms ≈ 5 秒

            fun attempt() {
                attempts++
                var ok = false
                try {
                    val root = shadeRoot()
                    if (root != null) ok = clickInRoot(root, words)
                    if (!ok) ok = clickInAnyWindow(words)
                } catch (_: Exception) {}
                if (ok) {
                    // 等点击生效后再收起通知栏
                    handler.postDelayed({ finish(true, lockAfter, done) }, 350)
                } else if (attempts >= maxAttempts) {
                    finish(false, lockAfter, done)
                } else {
                    handler.postDelayed({ attempt() }, 250)
                }
            }

            handler.postDelayed({ attempt() }, 450)
        } catch (_: Exception) {
            finish(false, lockAfter, done)
        }
    }

    private fun finish(ok: Boolean, lockAfter: Boolean, done: (Boolean) -> Unit) {
        if (openedShade) {
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
        }
        openedShade = false
        running = false
        try { done(ok) } catch (_: Exception) {}
        if (lockAfter && ok) {
            handler.postDelayed({ lockScreen() }, 700)
        }
    }

    /** 熄屏回到锁屏页（触发由本服务点亮过屏幕时使用，API 28+） */
    fun lockScreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            handler.post {
                try { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Exception) {}
            }
        }
    }

    private fun shadeRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        val pkg = r.packageName?.toString() ?: return null
        return if (pkg.contains("systemui")) r else null
    }

    /** 在全部 systemui 窗口中查找 */
    private fun clickInAnyWindow(words: List<String>): Boolean {
        val ws = try { windows } catch (_: Exception) { null } ?: return false
        for (w in ws) {
            val r = try { w.root } catch (_: Exception) { null } ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (!pkg.contains("systemui")) continue
            if (clickInRoot(r, words)) return true
        }
        return false
    }

    private fun clickInRoot(root: AccessibilityNodeInfo, words: List<String>): Boolean {
        for (w in words) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            findAllText(root, w, true, nodes)
            if (nodes.isEmpty()) findAllText(root, w, false, nodes)
            for (node in nodes) {
                // 跳过本应用自己发的提示通知（正文含"解锁范围/星舰蓝牙助手"），避免误点
                if (belongsToOwnApp(node)) continue
                // 1) 优先找可点击祖先，走标准点击
                val clickable = clickableAncestor(node)
                if (clickable != null) {
                    if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                // 2) 自定义布局控件可能不可点击，按坐标手势点击
                if (tapNode(node)) return true
            }
        }
        return false
    }

    /** 判断节点是否属于本 App 发出的通知（向上查找层级里的文字特征） */
    private fun belongsToOwnApp(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var depth = 0
        while (n != null && depth < 12) {
            val t = n.text?.toString()
            if (t != null && (t.contains("星舰蓝牙助手") || t.contains("已进入") || t.contains("已离开"))) {
                return true
            }
            n = try { n.parent } catch (_: Exception) { null }
            depth++
        }
        return false
    }

    /** 收集所有匹配节点（精确/包含两种模式） */
    private fun findAllText(
        node: AccessibilityNodeInfo,
        key: String,
        exact: Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val txt = node.text?.toString()
        if (txt != null) {
            val hit = if (exact) txt.trim() == key else txt.contains(key, true)
            if (hit) out.add(node)
        }
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            findAllText(c, key, exact, out)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var depth = 0
        while (n != null && depth < 10) {
            if (n.isClickable) return n
            n = try { n.parent } catch (_: Exception) { null }
            depth++
        }
        return null
    }

    /** 按控件中心坐标手势点击（RemoteViews 里的伪按钮只能用这种方式） */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 24) return false
        return try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return false
            val x = rect.exactCenterX()
            val y = rect.exactCenterY()
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }
}
