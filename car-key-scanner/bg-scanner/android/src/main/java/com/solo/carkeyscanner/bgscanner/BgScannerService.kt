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

/**
 * 后台常驻 BLE 感知服务（前台服务，connectedDevice 类型）。
 * 扫描策略：SCAN_MODE_LOW_LATENCY + 收藏 MAC/名称硬件过滤，广播到达即判定。
 *
 * 触发逻辑（互斥武装）：
 *  - 距离 ≤ 解锁距离 持续满判定时长 → 解锁；距离 ≥ 闭锁距离（或信号丢失）持续满时长 → 闭锁；
 *  - 触发过某一动作后，必须先触发相反动作才会再次武装（无中间区重复触发）；
 *  - 息屏时经唤醒锁 + 透明触发页（showWhenLocked/turnScreenOn）+ 全屏意图兜底完成点击。
 */
class BgScannerService : Service() {

    companion object {
        @Volatile var running = false
        const val CH_FG = "bg_scanner"
        const val ID_FG = 900
        const val STALE_MS = 6000L // 信号 6 秒未刷新即视为离开（加快"走远锁车"响应）
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
    private var belowSince: Long? = null // 距离 ≤ 解锁距离的起始时刻
    private var aboveSince: Long? = null // 距离 ≥ 闭锁距离（或信号丢失）的起始时刻
    // 互斥武装：触发过"解锁"后，必须先触发过一次"闭锁"才能再次解锁，反之亦然
    @Volatile private var lastAction: String? = null
    private val handler = Handler(Looper.getMainLooper())
    // 点击/无障碍等待耗时较长，放后台线程执行，避免阻塞主线程导致 ANR（"应用无响应"）
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 500)
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
        handler.postDelayed(tick, 500)
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

    /** 评估：取所有在期目标中最近距离驱动状态机；全部丢失视为离开。扫描回调与定时器都会调用，需线程安全。 */
    @Synchronized
    private fun evaluate() {
        val now = System.currentTimeMillis()
        val durMs = (prefs.getFloat("durationSec", 0.5f) * 1000).toLong().coerceAtLeast(300)
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

        if (anyFresh && best != null && best <= unlockDist) {
            // —— 解锁条件：距离 ≤ 设定值，持续满判定时长即触发 ——
            // 武装规则：上一次动作是"解锁"则不再重复，必须先经历一次"闭锁"
            if (belowSince == null) belowSince = now
            aboveSince = null
            if (lastAction != "unlock" && now - belowSince!! >= durMs) {
                lastAction = "unlock"
                belowSince = null
                val distText = "%.1f m ≤ %.1f m".format(best, unlockDist)
                worker.execute { handleTrigger(true, distText, durMs / 1000, notifyEnabled) }
            }
        } else if (!anyFresh || (best != null && best >= lockDist)) {
            // —— 闭锁条件：距离 ≥ 设定值（或信号丢失），持续满判定时长即触发 ——
            if (aboveSince == null) aboveSince = now
            belowSince = null
            if (lastAction != "lock" && now - aboveSince!! >= durMs) {
                lastAction = "lock"
                aboveSince = null
                val why = if (anyFresh) "%.1f m ≥ %.1f m".format(best, lockDist) else "信号丢失"
                worker.execute { handleTrigger(false, why, durMs / 1000, notifyEnabled) }
            }
        } else {
            // 两距离之间：仅重置计时，不改变武装状态
            belowSince = null
            aboveSince = null
        }
    }

    /**
     * 到达触发条件后的执行入口（worker 线程）：
     * 亮屏 → 直接走点击链路；息屏/锁屏 → 唤醒屏幕并经透明触发页执行（点完自动回黑屏）。
     */
    private fun handleTrigger(unlock: Boolean, distText: String, durSec: Long, notifyEnabled: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val screenOff = !pm.isInteractive
        if (screenOff) {
            // 先用唤醒锁点亮屏幕（ACQUIRE_CAUSES_WAKEUP 会立即亮屏）
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "carkey:trigger"
            )
            try { wl.acquire(15_000) } catch (_: Exception) {}
            // 主线程：直接拉起透明触发页（授予悬浮窗权限后后台可启动）
            handler.post { TriggerRunner.launchViaActivity(this, unlock, distText, durSec, true) }
            // 兜底：900ms 后再发全屏意图通知（若直接拉起被系统拦截，FSI 会全屏唤起）
            handler.postDelayed(
                { TriggerRunner.postFullScreenIntent(this, unlock, distText, durSec, true) },
                900
            )
            return
        }

        val body = TriggerRunner.run(this, unlock, distText, durSec, notifyEnabled)
        TriggerRunner.notifyResult(this, unlock, body)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "后台感知", NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(TriggerRunner.CH_PROX, "范围提醒", NotificationManager.IMPORTANCE_HIGH).apply {
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
}
