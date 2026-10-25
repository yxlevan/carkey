package com.solo.carkeyscanner.bgscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray

/**
 * 后台常驻 BLE 感知服务（前台服务，connectedDevice 类型）。
 * 省电策略：SCAN_MODE_LOW_POWER + 硬件过滤（按已收藏 MAC/名称）+ 3 秒批量上报。
 *
 * 触发逻辑：
 *  - 进入解锁距离 / 离开闭锁距离(含信号丢失) 持续超过判定时长；
 *  - 且通知监听器确认目标车控 App 常驻通知上存在对应按钮（即图一"已连接"状态，
 *    图二"未连接"状态通知无按钮，不会触发）；
 *  - 则直接以系统权限点击通知上的「解锁」/「闭锁」按钮，并发结果通知。
 */
class BgScannerService : Service() {

    companion object {
        @Volatile var running = false
        const val CH_FG = "bg_scanner"
        const val CH_PROX = "proximity"
        const val ID_FG = 900
        const val ID_ENTER = 1001
        const val ID_LEAVE = 1002
        const val STALE_MS = 12000L
        val CAR_NAME_RE = Regex(
            "geely|galaxy|starship|星舰|银河|car[ _-]?key|digital[ _-]?key|ble[ _-]?key|ccc|iccoa",
            RegexOption.IGNORE_CASE
        )
    }

    private lateinit var prefs: SharedPreferences
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val samples = HashMap<String, ArrayDeque<Int>>()
    private val lastSeen = HashMap<String, Long>()
    private val lastDist = HashMap<String, Double>()
    private var belowSince: Long? = null
    private var aboveSince: Long? = null
    private var triggeredEnter = false // 本次进入是否已触发（回到中间区/离开区后重新武装）
    private var triggeredLeave = false // 本次离开是否已触发（回到中间区/进入区后重新武装）
    private var lastTriggerTime = 0L // 防重复触发抑制窗口
    private val handler = Handler(Looper.getMainLooper())
    // 点击/无障碍等待耗时较长，放后台线程执行，避免阻塞主线程导致 ANR（"应用无响应"）
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("bgscanner", Context.MODE_PRIVATE)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_FG, fgNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID_FG, fgNotification())
        }
        running = true
        prefs.edit().putBoolean("bgEnabled", true).apply()
        restartScan()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 3000)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
        try { worker.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun restartScan() {
        try {
            scanner?.stopScan(cb)
        } catch (_: Exception) {}
        startScanning()
    }

    private fun startScanning() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return

        // 硬件过滤：已收藏 MAC + 已收藏名称（精确）。无收藏时全量低功耗扫描。
        val filters = ArrayList<ScanFilter>()
        for (mac in prefs.getStringSet("starred", emptySet())!!) {
            try { filters.add(ScanFilter.Builder().setDeviceAddress(mac).build()) } catch (_: Exception) {}
        }
        for (n in prefs.getStringSet("customNames", emptySet())!!) {
            if (n.isNotBlank()) filters.add(ScanFilter.Builder().setDeviceName(n.trim()).build())
        }

        val settings = ScanSettings.Builder()
            // 低延迟：发现即上报（配合收藏 MAC/名称的硬件过滤，锁屏也能被及时唤醒）
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        try {
            scanner?.startScan(if (filters.isEmpty()) null else filters, settings, cb)
        } catch (_: SecurityException) {
        }
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handle(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handle(r)
        }
        override fun onScanFailed(errorCode: Int) {
            handler.postDelayed({ restartScan() }, 10000)
        }
    }

    private fun handle(r: ScanResult) {
        val id = r.device?.address ?: return
        val name = try {
            r.device.name ?: r.scanRecord?.deviceName
        } catch (_: SecurityException) { null }
        if (!isTarget(id, name)) return

        val q = samples.getOrPut(id) { ArrayDeque() }
        q.addLast(r.rssi)
        while (q.size > 8) q.removeFirst()
        val med = q.sorted()[q.size / 2]
        lastSeen[id] = System.currentTimeMillis()
        lastDist[id] = rssiToDistance(med)
        // 广播到达即判定，不等定时器，最大化及时性
        evaluate()
    }

    /** 收藏 MAC 精确匹配；名称匹配采用"包含用户配置的关键字"即可（不要求正则完全匹配）。 */
    private fun isTarget(id: String, name: String?): Boolean {
        val starred = prefs.getStringSet("starred", emptySet())!!
        for (m in starred) if (m.equals(id, true)) return true
        val n = name?.trim()?.lowercase() ?: return false
        if (CAR_NAME_RE.containsMatchIn(n)) return true
        for (cn in prefs.getStringSet("customNames", emptySet())!!) {
            val key = cn.trim().lowercase()
            if (key.isNotEmpty() && n.contains(key)) return true
        }
        return false
    }

    private fun rssiToDistance(rssi: Int): Double {
        val tx = prefs.getInt("txPower", -59)
        val d = Math.pow(10.0, (tx - rssi) / (10.0 * 2.2))
        return Math.max(0.1, Math.round(d * 10) / 10.0)
    }

    data class WatchApp(
        val pkg: String,
        val name: String,
        val unlockTitle: String,
        val lockTitle: String
    )

    private fun watchApps(): List<WatchApp> {
        val raw = prefs.getString("watchApps", "[]") ?: "[]"
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

    /** 评估：取所有在期目标中最近距离驱动状态机；全部丢失视为离开。扫描回调与定时器都会调用，需线程安全。 */
    @Synchronized
    private fun evaluate() {
        val now = System.currentTimeMillis()
        val dur = prefs.getInt("durationSec", 2) * 1000L
        val unlockDist = prefs.getFloat("unlockDist", 2f).toDouble()
        val lockDist = prefs.getFloat("lockDist", 3f).toDouble()
        val notifyEnabled = prefs.getBoolean("notifyEnabled", true)

        var best: Double? = null
        var anyFresh = false
        for ((id, t) in lastSeen) {
            if (now - t <= STALE_MS) {
                anyFresh = true
                val d = lastDist[id]
                if (d != null && (best == null || d < best)) best = d
            }
        }

        // 防重复触发抑制窗口：30 秒内只允许一次触发（方向不限）
        val suppressed = now - lastTriggerTime < 30_000L

        if (anyFresh && best != null && best <= unlockDist) {
            // —— 进入解锁范围：距离 ≤ 设定值且持续满判定时长 → 立即触发 ——
            if (belowSince == null) belowSince = now
            aboveSince = null
            triggeredLeave = false // 离开逻辑重新武装
            if (!triggeredEnter && !suppressed && now - belowSince!! >= dur) {
                triggeredEnter = true
                lastTriggerTime = now
                worker.execute {
                    try {
                        handleTrigger(
                            unlock = true,
                            distText = "%.1f m ≤ %.1f m".format(best, unlockDist),
                            durSec = dur / 1000,
                            notifyEnabled = notifyEnabled
                        )
                    } catch (_: Exception) {}
                }
            }
        } else if (!anyFresh || (best != null && best >= lockDist)) {
            // —— 离开闭锁范围（含信号丢失）：距离 ≥ 设定值且持续满判定时长 → 立即触发 ——
            if (aboveSince == null) aboveSince = now
            belowSince = null
            triggeredEnter = false // 进入逻辑重新武装
            if (!triggeredLeave && !suppressed && now - aboveSince!! >= dur) {
                triggeredLeave = true
                lastTriggerTime = now
                val why = if (anyFresh) "%.1f m ≥ %.1f m".format(best, lockDist) else "信号丢失"
                worker.execute {
                    try {
                        handleTrigger(
                            unlock = false,
                            distText = why,
                            durSec = dur / 1000,
                            notifyEnabled = notifyEnabled
                        )
                    } catch (_: Exception) {}
                }
            }
        } else {
            // 中间区：不计时，双向重新武装（30 秒抑制窗口负责防抖）
            belowSince = null
            aboveSince = null
            triggeredEnter = false
            triggeredLeave = false
        }
    }

    /**
     * 到达触发条件：
     * 1. 若配置了监听 App → 检查其常驻通知是否有对应按钮（图一"已连接"态），
     *    有则直接触发（图二"未连接"态无按钮，自然跳过）；
     * 2. 发结果/提示通知。
     */
    private fun handleTrigger(unlock: Boolean, distText: String, durSec: Long, notifyEnabled: Boolean) {
        val apps = watchApps()
        val actionName = if (unlock) "解锁" else "闭锁"
        val condText = (if (unlock) "已进入解锁范围" else "已离开闭锁范围") +
            "（$distText，持续 ${durSec} s）"

        var statuses = ArrayList<String>()
        var codes = ArrayList<String>()
        if (apps.isNotEmpty()) {
            for (app in apps) {
                val r = tryClick(app, unlock)
                codes.add(r)
                statuses.add("${app.name}: ${statusText(r)}")
                if (r == "ok" || r == "a11y_ok") break // 成功一个即可
            }
        }

        val ok = codes.any { it == "ok" || it == "a11y_ok" }
        val body = when {
            ok -> "$condText，已自动点击「$actionName」"
            apps.isNotEmpty() -> "$condText，未点击（${statuses.joinToString("；")}）"
            notifyEnabled -> condText
            else -> return
        }
        // 记录最近一次执行结果，供 App 内诊断界面查看
        prefs.edit()
            .putString("lastResult", "${System.currentTimeMillis() / 1000}|$body")
            .apply()
        notifyProx(if (unlock) ID_ENTER else ID_LEAVE, body)
    }

    /** 先按用户配置的按钮标题点，失败后用同义词兜底；仍失败且是无按钮/自定义布局时，走无障碍在通知栏点击 */
    private fun tryClick(app: WatchApp, unlock: Boolean): String {
        val primary = if (unlock) app.unlockTitle else app.lockTitle
        var r = KeyNotificationListener.clickAction(this, app.pkg, primary)
        if (r == "ok") return r
        val alts = if (unlock) KeyNotificationListener.UNLOCK_WORDS else KeyNotificationListener.LOCK_WORDS
        for (w in alts) {
            if (w == primary) continue
            r = KeyNotificationListener.clickAction(this, app.pkg, w)
            if (r == "ok") return r
        }
        // 自定义 RemoteViews 布局的通知拿不到标准按钮，走无障碍兜底点击
        if (KeyClickService.isEnabled(this)) {
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

    private fun statusText(code: String): String = when {
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

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "后台感知", NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_PROX, "范围提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
    }

    private fun fgNotification(): Notification {
        return NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("星舰蓝牙助手")
            .setContentText("正在后台低功耗感知车辆蓝牙")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun notifyProx(id: Int, body: String) {
        val n = NotificationCompat.Builder(this, CH_PROX)
            .setContentTitle("星舰蓝牙助手")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }
}
