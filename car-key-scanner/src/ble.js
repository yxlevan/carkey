import { reactive } from 'vue'
import { BleClient } from '@capacitor-community/bluetooth-le'
import { LocalNotifications } from '@capacitor/local-notifications'
import { BgScanner } from 'bg-scanner'

// 车钥匙类 BLE 广播名称特征（吉利/银河/星舰 + 通用数字钥匙关键字）
const CAR_NAME_RE = /geely|galaxy|starship|星舰|银河|car[ _-]?key|digital[ _-]?key|ble[ _-]?key|ccc|iccoa/i

export const STALE_MS = 12000 // 超过 12 秒没收到广播视为信号丢失
export const PURGE_MS = 60000 // 超过 60 秒没收到广播从列表移除（星标设备保留）
const MAX_SAMPLES = 8 // RSSI 滑动窗口长度

const NOTIFY_ID_ENTER = 1001 // 进入解锁范围通知 id（固定 id 会覆盖旧通知）
const NOTIFY_ID_LEAVE = 1002 // 离开闭锁范围通知 id

export const state = reactive({
  initialized: false,
  status: 'idle', // idle | scanning | stopped | error
  btEnabled: true,
  error: '',
  totalAds: 0, // 累计收到的广播条数（诊断用）
  scanStartedAt: 0,
  // 范围与通知参数（界面可调，持久化）
  unlockDist: Number(localStorage.getItem('unlockDist') || 2), // 解锁感知距离（米）
  lockDist: Number(localStorage.getItem('lockDist') || 3), // 闭锁感知距离（米）
  durationSec: Number(localStorage.getItem('durationSec') || 0.5), // 判定时长（秒，默认 0.5）
  txPower: Number(localStorage.getItem('txPower') || -59),
  // id -> { id, name, rssi, distance, lastSeen, isCar }
  devices: {},
  // 用户手动标星为"我的车钥匙"的设备 MAC
  starred: JSON.parse(localStorage.getItem('starred') || '[]'),
  // 用户自定义的车钥匙蓝牙名称关键字（匹配到的设备自动视为车钥匙）
  customNames: JSON.parse(localStorage.getItem('customNames') || '[]'),
  // 后台常驻感知开关（默认开启）
  bgEnabled: localStorage.getItem('bgEnabled') !== 'false',
  bgRunning: false,
  // 是否接收普通蓝牙广播（默认开启）
  scanNormal: localStorage.getItem('scanNormal') !== 'false',
  // 要监听的车控 App 列表：[{package, name, unlockTitle, lockTitle}]
  watchApps: JSON.parse(localStorage.getItem('watchApps') || '[]'),
  // 未配置监听 App 时是否仅发通知（默认开启）
  notifyEnabled: localStorage.getItem('notifyEnabled') !== 'false'
})

// 把当前配置同步给原生后台服务
export async function pushBgConfig() {
  try {
    await BgScanner.configure({
      starred: [...state.starred],
      customNames: [...state.customNames],
      watchApps: state.watchApps,
      unlockDist: state.unlockDist,
      lockDist: state.lockDist,
      durationSec: state.durationSec,
      txPower: state.txPower,
      notifyEnabled: state.notifyEnabled
    })
  } catch (_) {
    /* Web 预览环境无原生实现 */
  }
}

export async function startBgService() {
  try {
    await pushBgConfig()
    await BgScanner.start()
    state.bgRunning = true
  } catch (_) {}
}

export async function stopBgService() {
  try {
    await BgScanner.stop()
  } catch (_) {}
  state.bgRunning = false
}

export async function setBgEnabled(on) {
  state.bgEnabled = !!on
  localStorage.setItem('bgEnabled', String(state.bgEnabled))
  if (state.bgEnabled) await startBgService()
  else await stopBgService()
}

// 关闭普通蓝牙接收时，立即清掉已收录的非车钥匙设备
export function setScanNormal(on) {
  state.scanNormal = !!on
  localStorage.setItem('scanNormal', String(state.scanNormal))
  if (!state.scanNormal) {
    for (const id of Object.keys(state.devices)) {
      const d = state.devices[id]
      if (!d.isCar) {
        delete state.devices[id]
        samples.delete(id)
      }
    }
  }
}

// —— 车控 App 通知监听 ——

export function addWatchApp(app) {
  if (!app || !app.package) return
  if (state.watchApps.some((a) => a.package === app.package)) return
  state.watchApps.push({
    package: app.package,
    name: app.name || app.package,
    unlockTitle: '解锁',
    lockTitle: '闭锁'
  })
  localStorage.setItem('watchApps', JSON.stringify(state.watchApps))
  pushBgConfig()
}

export function removeWatchApp(pkg) {
  const i = state.watchApps.findIndex((a) => a.package === pkg)
  if (i >= 0) state.watchApps.splice(i, 1)
  localStorage.setItem('watchApps', JSON.stringify(state.watchApps))
  pushBgConfig()
}

export function setNotifyEnabled(on) {
  state.notifyEnabled = !!on
  localStorage.setItem('notifyEnabled', String(state.notifyEnabled))
  pushBgConfig()
}

export async function isNotifAccessGranted() {
  try {
    return (await BgScanner.isNotifAccessGranted()).granted
  } catch (_) {
    return false
  }
}

export async function openNotifAccess() {
  try {
    await BgScanner.openNotifAccess()
  } catch (_) {}
}

export async function getInstalledApps() {
  try {
    const r = await BgScanner.getInstalledApps()
    return r.apps || []
  } catch (_) {
    return []
  }
}

// —— 诊断 ——

export async function diagnoseNotifications() {
  try {
    return await BgScanner.diagnose()
  } catch (_) {
    return null
  }
}

export async function testClickAction(pkg, title) {
  try {
    return (await BgScanner.testAction({ package: pkg, title })).result
  } catch (e) {
    return 'error:' + (e && e.message)
  }
}

export async function isA11yEnabled() {
  try {
    return (await BgScanner.isA11yEnabled()).enabled
  } catch (_) {
    return false
  }
}

export async function openA11ySettings() {
  try {
    await BgScanner.openA11ySettings()
  } catch (_) {}
}

export async function isOverlayGranted() {
  try {
    return (await BgScanner.isOverlayGranted()).granted
  } catch (_) {
    return false
  }
}

export async function openOverlaySettings() {
  try {
    await BgScanner.openOverlaySettings()
  } catch (_) {}
}

const samples = new Map() // id -> 最近 N 个 RSSI 样本（非响应式）

// 范围判定状态机（非响应式）：与原生服务一致的互斥武装语义
let belowSince = null // 连续 ≤ 解锁距离的起始时间
let aboveSince = null // 连续 ≥ 闭锁距离/丢失 的起始时间
let lastAction = null // 'enter' | 'leave' | null：触发过某方向后，须先触发相反方向才能再触发

export function rssiToDistance(rssi, txPower = state.txPower, n = 2.2) {
  if (typeof rssi !== 'number') return null
  const d = Math.pow(10, (txPower - rssi) / (10 * n))
  return Math.max(0.1, Math.round(d * 10) / 10)
}

function median(arr) {
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.floor(s.length / 2)]
}

function classify(id, name) {
  if (state.starred.includes(id)) return true
  const n = (name || '').trim().toLowerCase()
  if (!n) return false
  if (CAR_NAME_RE.test(name)) return true
  // 命中用户自定义名称关键字（不区分大小写，包含即匹配）
  for (const cn of state.customNames) {
    const key = cn.trim().toLowerCase()
    if (key && n.includes(key)) return true
  }
  return false
}

function reclassifyAll() {
  for (const id of Object.keys(state.devices)) {
    const d = state.devices[id]
    d.isCar = classify(d.id, d.name)
  }
}

export function addCustomName(name) {
  const v = String(name || '').trim()
  if (!v || state.customNames.includes(v)) return
  state.customNames.push(v)
  localStorage.setItem('customNames', JSON.stringify(state.customNames))
  reclassifyAll()
  pushBgConfig()
}

export function removeCustomName(name) {
  const i = state.customNames.indexOf(name)
  if (i >= 0) state.customNames.splice(i, 1)
  localStorage.setItem('customNames', JSON.stringify(state.customNames))
  reclassifyAll()
  pushBgConfig()
}

function handleScan(result) {
  const id = result.device.deviceId
  const name = result.device.name || result.localName || ''
  const isCar = classify(id, name)

  // 未开启普通蓝牙接收时，直接丢弃非车钥匙设备（减少内存与渲染开销）
  if (!isCar && !state.scanNormal) return

  state.totalAds++
  let arr = samples.get(id)
  if (!arr) {
    arr = []
    samples.set(id, arr)
  }
  if (typeof result.rssi === 'number') {
    arr.push(result.rssi)
    if (arr.length > MAX_SAMPLES) arr.shift()
  }
  const rssi = arr.length ? Math.round(median(arr)) : result.rssi

  const entry = state.devices[id] || { id, name: '' }
  entry.name = name || entry.name
  entry.rssi = rssi
  entry.distance = rssiToDistance(rssi)
  entry.lastSeen = Date.now()
  entry.isCar = isCar
  state.devices[id] = entry
}

function errMsg(e) {
  return (e && (e.message || e.toString())) || '未知错误'
}

export async function initBle() {
  try {
    // 先显式申请运行时权限（Android 12+ 为「附近的设备」），再初始化
    if (typeof BleClient.requestPermissions === 'function') {
      try {
        await BleClient.requestPermissions()
      } catch (_) {
        /* 用户拒绝时继续，由错误提示引导去设置开启 */
      }
    }
    await BleClient.initialize()
    state.btEnabled = await BleClient.isEnabled()
    state.initialized = true
    // 默认开启后台常驻感知
    if (state.bgEnabled) await startBgService()
  } catch (e) {
    state.error = '蓝牙初始化失败：' + errMsg(e)
  }
}

// 申请通知权限并创建通知渠道（Android 13+ 需要运行时权限）
export async function initNotifications() {
  try {
    await LocalNotifications.requestPermissions()
    await LocalNotifications.createChannel({
      id: 'proximity',
      name: '范围提醒',
      importance: 4, // IMPORTANCE_HIGH：横幅 + 响铃 + 震动
      vibration: true
    })
  } catch (_) {
    /* 权限被拒绝时静默，通知功能降级 */
  }
}

async function fireNotify(id, body) {
  try {
    await LocalNotifications.schedule({
      notifications: [{ id, title: '星舰蓝牙助手', body, channelId: 'proximity' }]
    })
  } catch (_) {
    /* 无通知权限时忽略 */
  }
}

// 每个 tick 调用一次。car 为主车钥匙对象（可能为 null）。
// 后台服务运行期间由原生侧负责通知，此处跳过避免重复。
export function evaluateProximity(car, now) {
  if (state.bgRunning) return
  const durMs = state.durationSec * 1000
  const fresh = !!car && now - car.lastSeen <= STALE_MS

  if (fresh && car.distance != null && car.distance <= state.unlockDist) {
    belowSince = belowSince ?? now
    aboveSince = null
    if (lastAction !== 'enter' && now - belowSince >= durMs) {
      lastAction = 'enter'
      belowSince = null
      fireNotify(
        NOTIFY_ID_ENTER,
        `已进入解锁范围（${car.distance.toFixed(1)} m ≤ ${state.unlockDist} m，持续 ${state.durationSec} s）`
      )
    }
  } else if (!fresh || (car.distance != null && car.distance >= state.lockDist)) {
    aboveSince = aboveSince ?? now
    belowSince = null
    if (lastAction !== 'leave' && now - aboveSince >= durMs) {
      lastAction = 'leave'
      aboveSince = null
      const why = fresh ? `${car.distance.toFixed(1)} m ≥ ${state.lockDist} m` : '信号丢失'
      fireNotify(NOTIFY_ID_LEAVE, `已离开闭锁范围（${why}，持续 ${state.durationSec} s）`)
    }
  } else {
    // 解锁距离与闭锁距离之间：重置计时，不改变武装状态
    belowSince = null
    aboveSince = null
  }
}

export async function enableBt() {
  try {
    await BleClient.requestEnable()
    state.btEnabled = true
  } catch (e) {
    state.error = '无法开启蓝牙：' + errMsg(e)
  }
}

export async function startScan() {
  state.error = ''
  try {
    if (!state.btEnabled) await enableBt()
    // 先停掉可能存在的旧扫描，避免重复回调
    try {
      await BleClient.stopLEScan()
    } catch (_) {
      /* 忽略 */
    }
    await BleClient.requestLEScan(
      {
        allowDuplicates: true,
        scanMode: 2 // 2 = SCAN_MODE_LOW_LATENCY，最高频率扫描
      },
      handleScan
    )
    state.totalAds = 0
    state.scanStartedAt = Date.now()
    state.status = 'scanning'
  } catch (e) {
    state.status = 'error'
    state.error =
      '扫描启动失败（请到 设置→应用→星舰蓝牙助手→权限 中允许「附近的设备」）：' + errMsg(e)
  }
}

export async function stopScan() {
  try {
    await BleClient.stopLEScan()
  } catch (_) {
    /* 忽略重复停止 */
  }
  if (state.status === 'scanning') state.status = 'stopped'
}

export function toggleStar(id) {
  const i = state.starred.indexOf(id)
  if (i >= 0) state.starred.splice(i, 1)
  else state.starred.push(id)
  localStorage.setItem('starred', JSON.stringify(state.starred))
  const d = state.devices[id]
  if (d) d.isCar = classify(d.id, d.name)
  pushBgConfig()
}

function persistNum(key, v) {
  state[key] = Number(v)
  localStorage.setItem(key, String(v))
  pushBgConfig()
}

export function setUnlockDist(v) {
  persistNum('unlockDist', v)
}

export function setLockDist(v) {
  persistNum('lockDist', v)
}

export function setDurationSec(v) {
  persistNum('durationSec', v)
}

export function setTxPower(v) {
  persistNum('txPower', v)
}

export function isStale(d, now) {
  return now - d.lastSeen > STALE_MS
}
