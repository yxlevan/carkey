<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  state,
  STALE_MS,
  PURGE_MS,
  initBle,
  initNotifications,
  evaluateProximity,
  enableBt,
  startScan,
  stopScan,
  toggleStar,
  setTxPower,
  setUnlockDist,
  setLockDist,
  setDurationSec,
  addCustomName,
  removeCustomName,
  setBgEnabled,
  setScanNormal,
  addWatchApp,
  removeWatchApp,
  setNotifyEnabled,
  isNotifAccessGranted,
  openNotifAccess,
  getInstalledApps,
  diagnoseNotifications,
  testClickAction,
  isA11yEnabled,
  openA11ySettings,
  isOverlayGranted,
  openOverlaySettings
} from './ble'

const now = ref(Date.now())
const newName = ref('')
const notifGranted = ref(true)
const a11yEnabled = ref(true)
const overlayGranted = ref(true)
const showPicker = ref(false)
const appSearch = ref('')
const installedApps = ref([])
const diag = ref(null)
const testResult = ref('')

const TEST_TEXT = {
  ok: '✅ 已成功点击通知上的标准按钮',
  a11y_ok: '✅ 已通过无障碍点击通知栏按钮（通知栏会展开再收起，属正常）',
  a11y_fail: '❌ 无障碍点击失败：通知栏里没找到「解锁/闭锁」文字，或控件无法点击',
  a11y_unbound: '❌ 无障碍服务未连接：去系统无障碍里把「星舰蓝牙助手」关掉再打开',
  a11y_disabled: '❌ 无障碍服务未开启：点上方「去开启」',
  listener_not_connected: '❌ 监听服务未连接：重开 App，或去系统设置重新开关「通知使用权」',
  no_notification: '❌ 没找到该 App 的通知（通知被清掉或未开启常驻）',
  no_actions: '❌ 通知上没有任何标准按钮（自定义布局，需无障碍兜底）',
  no_matching_action: '❌ 有按钮但标题不匹配（见上方诊断里的按钮文本）',
  no_pending_intent: '❌ 按钮没有可执行的点击动作'
}

async function runDiagnose() {
  testResult.value = ''
  diag.value = await diagnoseNotifications()
}

function mapTestResult(r) {
  if (!r) return '❌ 无返回结果'
  if (TEST_TEXT[r]) return TEST_TEXT[r]
  if (r.startsWith('a11y_fail')) return TEST_TEXT.a11y_fail
  if (r.startsWith('a11y_unbound')) return TEST_TEXT.a11y_unbound
  if (r.startsWith('a11y_disabled')) return TEST_TEXT.a11y_disabled
  if (r.startsWith('canceled')) return '❌ 按钮已失效：' + r
  return r
}

// 步进器调参（不依赖手机键盘提交，扫描中也能改，实时生效到后台服务）
function stepSetting(key, delta, decimals) {
  const cur = Number(state[key]) || 0
  let v = Math.round((cur + delta) * 10 ** decimals) / 10 ** decimals
  if (key === 'durationSec') v = Math.max(0.5, v) // 判定时长下限 0.5s
  if (key === 'unlockDist') setUnlockDist(v)
  else if (key === 'lockDist') setLockDist(v)
  else if (key === 'durationSec') setDurationSec(v)
  else if (key === 'txPower') setTxPower(v)
}

async function runTest(title) {
  const app = state.watchApps[0]
  if (!app) return
  testResult.value = '正在尝试…'
  const r = await testClickAction(app.package, title)
  testResult.value = mapTestResult(r)
}

async function refreshNotifGranted() {
  notifGranted.value = await isNotifAccessGranted()
  a11yEnabled.value = await isA11yEnabled()
  overlayGranted.value = await isOverlayGranted()
}

async function openPicker() {
  showPicker.value = true
  if (!installedApps.value.length) {
    const apps = await getInstalledApps()
    apps.sort((a, b) => a.label.localeCompare(b.label, 'zh'))
    installedApps.value = apps
  }
}

const filteredApps = computed(() => {
  const k = appSearch.value.trim().toLowerCase()
  if (!k) return installedApps.value
  return installedApps.value.filter(
    (a) => a.label.toLowerCase().includes(k) || a.package.toLowerCase().includes(k)
  )
})

function pickApp(a) {
  addWatchApp({ package: a.package, name: a.label })
  showPicker.value = false
  appSearch.value = ''
}

// 手动输入包名兜底（应用列表查不到时使用）
const manualName = ref('')
const manualPkg = ref('')

function addManualApp() {
  const pkg = manualPkg.value.trim()
  if (!pkg) return
  addWatchApp({ package: pkg, name: manualName.value.trim() || pkg })
  manualName.value = ''
  manualPkg.value = ''
  showPicker.value = false
}

function addName() {
  addCustomName(newName.value)
  newName.value = ''
}
let timer = null
let resumeScan = false // 切后台前是否在扫描，回前台自动恢复

onMounted(async () => {
  await initBle()
  await initNotifications() // 申请通知权限（Android 13+ 会弹窗）
  await refreshNotifGranted()
  // 自动化：打开 App 即自动开始扫描
  if (state.initialized && state.btEnabled) startScan()
  timer = setInterval(() => {
    now.value = Date.now()
    // 清理 60 秒未出现且未标星的设备
    for (const id of Object.keys(state.devices)) {
      const d = state.devices[id]
      if (now.value - d.lastSeen > PURGE_MS && !state.starred.includes(id)) {
        delete state.devices[id]
      }
    }
    // 范围判定：进入/离开超时长发通知
    evaluateProximity(primaryCar.value, now.value)
  }, 500)
  document.addEventListener('visibilitychange', onVisibility)
})

onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})

// 切后台暂停扫描，回前台自动恢复（澎湃 OS 后台会停广播回调）
function onVisibility() {
  if (document.hidden) {
    if (state.status === 'scanning') {
      resumeScan = true
      stopScan()
    }
  } else if (resumeScan) {
    resumeScan = false
    startScan()
  }
  refreshNotifGranted() // 从系统设置页返回时刷新授权状态
}

const deviceList = computed(() =>
  Object.values(state.devices)
    .map((d) => ({ ...d, stale: now.value - d.lastSeen > STALE_MS }))
    .sort((a, b) => (b.rssi ?? -999) - (a.rssi ?? -999))
)

const carList = computed(() => deviceList.value.filter((d) => d.isCar))
const normalList = computed(() => deviceList.value.filter((d) => !d.isCar))

// 主车钥匙：优先取被标星且在线的设备
const primaryCar = computed(() => {
  const starred = carList.value.filter((d) => state.starred.includes(d.id) && !d.stale)
  return starred[0] || carList.value.find((d) => !d.stale) || null
})

const proxState = computed(() => {
  const c = primaryCar.value
  if (!c) return 'none'
  if (now.value - c.lastSeen > STALE_MS) return 'lost'
  if (c.distance <= state.unlockDist) return 'near'
  if (c.distance >= state.lockDist) return 'far'
  return 'mid'
})

const proxText = computed(() => ({
  none: '未识别到车钥匙',
  near: `在解锁范围内（≤ ${state.unlockDist} 米）`,
  far: `已达闭锁距离（≥ ${state.lockDist} 米）`,
  mid: '缓冲区（保持现状）',
  lost: '信号丢失'
}))

// 进入/离开状态切换时震动提醒（缓冲区不震动）
watch(proxState, (v, old) => {
  if ((v === 'near' || v === 'far') && v !== old && navigator.vibrate) {
    navigator.vibrate(v === 'near' ? 120 : [60, 60, 60])
  }
})

const scanning = computed(() => state.status === 'scanning')

// 扫描 15 秒仍 0 条广播 → 显示自检清单
const showNoResultHint = computed(
  () =>
    scanning.value &&
    state.totalAds === 0 &&
    now.value - state.scanStartedAt > 15000
)

async function toggleScan() {
  if (scanning.value) await stopScan()
  else await startScan()
}

function fmtDist(d) {
  return d == null ? '--' : d.toFixed(1) + ' m'
}
</script>

<template>
  <div class="header">
    <h1>星舰蓝牙助手</h1>
    <button class="scan-btn" :class="{ scanning }" @click="toggleScan">
      {{ scanning ? '停止扫描' : '开始扫描' }}
    </button>
  </div>

  <div v-if="!state.btEnabled" class="banner warn">
    蓝牙未开启
    <button @click="enableBt">去开启</button>
  </div>

  <div v-if="state.error" class="banner error">{{ state.error }}</div>

  <!-- 无结果自检清单 -->
  <div v-if="showNoResultHint" class="banner warn">
    扫描 15 秒仍无任何广播，请依次检查：<br />
    ① 设置 → 位置信息：已开启（小米扫描 BLE 需要定位总开关打开）<br />
    ② 设置 → 应用 → 星舰蓝牙助手 → 权限：「附近的设备」已允许<br />
    ③ 手机蓝牙已开启，且人就在车旁 2 米内<br />
    ④ 车辆未进入深度休眠（可开关一次车门/按钥匙唤醒后再试）
  </div>

  <!-- 我的车钥匙：距离与范围状态 -->
  <div class="car-card" v-if="primaryCar">
    <div class="name">{{ primaryCar.name || '我的车钥匙' }} · {{ primaryCar.id }}</div>
    <div class="distance">
      {{ primaryCar.distance != null ? primaryCar.distance.toFixed(1) : '--'
      }}<small> m</small>
    </div>
    <span class="pill" :class="proxState">{{ proxText[proxState] }}</span>
  </div>

  <!-- 按名称收藏钥匙 -->
  <div class="key-card">
    <div class="key-card-head">
      <div>
        <div class="key-card-title">按名称收藏车钥匙</div>
        <div class="key-card-sub">输入蓝牙名称关键字，匹配后自动归入车载蓝牙</div>
      </div>
      <span class="key-badge">可选</span>
    </div>

    <div class="field">
      <svg class="field-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="11" cy="11" r="6.5" stroke="currentColor" stroke-width="1.8" />
        <path d="M16 16l4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
      </svg>
      <input
        v-model="newName"
        placeholder="如 geely、starship"
        enterkeyhint="done"
        @keyup.enter="addName"
      />
      <button v-if="newName" class="field-clear" @click="newName = ''">×</button>
      <button class="field-add" :disabled="!newName.trim()" @click="addName">
        收藏
      </button>
    </div>

    <div v-if="state.customNames.length" class="chips">
      <span class="chips-label">已收藏</span>
      <span v-for="n in state.customNames" :key="n" class="chip">
        {{ n }}
        <button @click="removeCustomName(n)">×</button>
      </span>
    </div>

    <p class="key-hint">
      不区分大小写，包含即匹配。例如广播名为
      <code>Geely_BLE_1234</code>，输入 geely 即可；无名称设备请在列表中点 ☆
      按 MAC 收藏。
    </p>
  </div>

  <!-- 车控 App 通知监听 -->
  <div class="key-card">
    <div class="key-card-head">
      <div>
        <div class="key-card-title">车控 App 通知联动</div>
        <div class="key-card-sub">
          满足开/闭锁距离条件时，自动点击该 App 常驻通知上的「解锁 / 闭锁」按钮
        </div>
      </div>
      <span class="key-badge">自动开闭锁</span>
    </div>

    <div v-if="!notifGranted" class="access-warn">
      需要授予「通知使用权」才能监听并点击车控 App 的通知按钮
      <button class="access-btn" @click="openNotifAccess">去授权</button>
    </div>

    <div v-if="!a11yEnabled && state.watchApps.length" class="access-warn">
      若诊断显示通知是「自定义布局」或「无按钮」，需开启无障碍服务兜底点击
      <button class="access-btn" @click="openA11ySettings">去开启</button>
    </div>

    <div v-if="!overlayGranted" class="access-warn">
      建议授予「显示在应用上层」（悬浮窗）权限：手机息屏/揣兜里时触发更可靠
      <button class="access-btn" @click="openOverlaySettings">去开启</button>
    </div>

    <button class="picker-open" @click="openPicker">＋ 添加要监听的 App</button>

    <div v-for="a in state.watchApps" :key="a.package" class="watch-item">
      <div class="watch-info">
        <div class="watch-name">{{ a.name }}</div>
        <div class="watch-pkg">{{ a.package }} · 监听按钮「{{ a.unlockTitle }} / {{ a.lockTitle }}」</div>
      </div>
      <button class="watch-del" @click="removeWatchApp(a.package)">移除</button>
    </div>

    <div v-if="state.watchApps.length" class="diag-box">
      <button class="diag-run" @click="runDiagnose">运行诊断（查看通知与按钮）</button>
      <div v-if="diag" class="diag-body">
        <div class="diag-row">
          <span>通知使用权</span>
          <b :class="diag.granted ? 'ok' : 'bad'">{{ diag.granted ? '已授予' : '未授予' }}</b>
        </div>
        <div class="diag-row">
          <span>监听服务</span>
          <b :class="diag.connected ? 'ok' : 'bad'">{{ diag.connected ? '已连接' : '未连接' }}</b>
        </div>
        <div
          v-for="(n, i) in diag.apps"
          :key="n.package + i"
          class="diag-notif"
        >
          <div class="diag-notif-title">
            {{ n.text || '(无标题)' }}
            <small>{{ n.package }}{{ n.ongoing ? ' · 常驻' : '' }}{{ n.customView ? ' · 自定义布局' : '' }}</small>
          </div>
          <div class="diag-actions">
            按钮：{{ n.actions && n.actions.length ? n.actions.join(' / ') : '无' }}
          </div>
        </div>
        <div v-if="!diag.apps || !diag.apps.length" class="diag-row">
          <span>目标通知</span><b class="bad">未发现</b>
        </div>
        <div class="diag-test">
          <button @click="runTest('解锁')">测试点击解锁</button>
          <button @click="runTest('闭锁')">测试点击闭锁</button>
        </div>
        <div v-if="testResult" class="diag-result">{{ testResult }}</div>
      </div>
    </div>

    <p class="key-hint">
      工作原理：进入解锁范围 → 若该 App 常驻通知显示「解锁」按钮（钥匙已连接）则自动点击解锁；
      离开闭锁范围 → 同理自动点击「闭锁」。若通知显示「数字钥匙未连接」（无按钮），
      则不执行任何操作，仅发提示。通知被小米折叠在「更多通知」里不影响监听，但若诊断不到通知，
      请长按该通知设为「重要」，并给吉利银河和本 App 都开启「后台弹出界面」权限。
    </p>
  </div>

  <!-- App 选择弹层 -->
  <div v-if="showPicker" class="picker-mask" @click.self="showPicker = false">
    <div class="picker-panel">
      <div class="picker-head">
        <input v-model="appSearch" placeholder="搜索应用名称" />
        <button class="picker-close" @click="showPicker = false">×</button>
      </div>
      <div class="picker-list">
        <div
          v-for="a in filteredApps"
          :key="a.package"
          class="picker-item"
          @click="pickApp(a)"
        >
          <div class="picker-label">{{ a.label }}</div>
          <div class="picker-pkg">{{ a.package }}</div>
        </div>
        <div v-if="!filteredApps.length" class="empty">无匹配应用，可下方手动输入包名</div>
      </div>
      <div class="picker-manual">
        <input v-model="manualName" placeholder="应用名称（如 吉利银河）" />
        <input v-model="manualPkg" placeholder="包名（如 com.xxx.xxx）" />
        <button :disabled="!manualPkg.trim()" @click="addManualApp">添加</button>
      </div>
      <div class="picker-tip">
        列表里找不到车控 App 时手动输入包名。查看包名：设置 → 应用 → 吉利银河 → 右上角详情
        （或长按桌面图标 → 应用信息）。
      </div>
    </div>
  </div>
  <div class="section-title">
    🚗 车载蓝牙 <span class="count">{{ carList.length }} 台设备</span>
  </div>
  <div v-if="!carList.length" class="empty">
    暂未识别到车钥匙广播。扫描到后，点设备右侧 ☆ 可手动标记为「我的车钥匙」。
  </div>
  <div
    v-for="d in carList"
    :key="d.id"
    class="device"
    :class="{ stale: d.stale }"
  >
    <div class="info">
      <div class="dname">
        <span v-if="d.name">{{ d.name }}</span>
        <span v-else class="unnamed">未命名设备</span>
      </div>
      <div class="mac">{{ d.id }}</div>
    </div>
    <div class="metrics">
      <div class="rssi">{{ d.rssi }} dBm</div>
      <div class="dist">{{ fmtDist(d.distance) }}</div>
    </div>
    <button
      class="star"
      :class="{ active: state.starred.includes(d.id) }"
      @click="toggleStar(d.id)"
    >
      {{ state.starred.includes(d.id) ? '★' : '☆' }}
    </button>
  </div>

  <!-- 普通蓝牙列表（固定 5 行高度，超出滚动） -->
  <template v-if="state.scanNormal">
    <div class="section-title">
      📱 普通蓝牙 <span class="count">{{ normalList.length }} 台设备</span>
    </div>
    <div v-if="!normalList.length" class="empty">
      {{ scanning ? '正在扫描周围 BLE 设备…' : '点击「开始扫描」' }}
    </div>
    <div class="list-fixed">
      <div
        v-for="d in normalList"
        :key="d.id"
        class="device"
        :class="{ stale: d.stale }"
      >
        <div class="info">
          <div class="dname">
            <span v-if="d.name">{{ d.name }}</span>
            <span v-else class="unnamed">未命名设备</span>
          </div>
          <div class="mac">{{ d.id }}</div>
        </div>
        <div class="metrics">
          <div class="rssi">{{ d.rssi }} dBm</div>
          <div class="dist">{{ fmtDist(d.distance) }}</div>
        </div>
        <button
          class="star"
          :class="{ active: state.starred.includes(d.id) }"
          @click="toggleStar(d.id)"
        >
          {{ state.starred.includes(d.id) ? '★' : '☆' }}
        </button>
      </div>
    </div>
  </template>

  <!-- 范围与通知参数 -->
  <div class="settings">
    <h2>范围与通知设置</h2>
    <div class="setting-row">
      <span>后台常驻感知（低功耗）</span>
      <input
        type="checkbox"
        style="width: 22px; height: 22px"
        :checked="state.bgEnabled"
        @change="(e) => setBgEnabled(e.target.checked)"
      />
    </div>
    <div class="setting-row">
      <span>接收普通蓝牙（关闭更省电）</span>
      <input
        type="checkbox"
        style="width: 22px; height: 22px"
        :checked="state.scanNormal"
        @change="(e) => setScanNormal(e.target.checked)"
      />
    </div>
    <div class="setting-row">
      <span>范围提醒通知（未配置联动时）</span>
      <input
        type="checkbox"
        style="width: 22px; height: 22px"
        :checked="state.notifyEnabled"
        @change="(e) => setNotifyEnabled(e.target.checked)"
      />
    </div>
    <div class="setting-row">
      <span>解锁感知距离（米）</span>
      <div class="stepper">
        <button @click="stepSetting('unlockDist', -0.5, 1)">−</button>
        <span class="stepper-val">{{ state.unlockDist }}</span>
        <button @click="stepSetting('unlockDist', 0.5, 1)">＋</button>
      </div>
    </div>
    <div class="setting-row">
      <span>闭锁感知距离（米）</span>
      <div class="stepper">
        <button @click="stepSetting('lockDist', -0.5, 1)">−</button>
        <span class="stepper-val">{{ state.lockDist }}</span>
        <button @click="stepSetting('lockDist', 0.5, 1)">＋</button>
      </div>
    </div>
    <div class="setting-row">
      <span>判定时长（秒）</span>
      <div class="stepper">
        <button @click="stepSetting('durationSec', -0.5, 1)">−</button>
        <span class="stepper-val">{{ state.durationSec }}</span>
        <button @click="stepSetting('durationSec', 0.5, 1)">＋</button>
      </div>
    </div>
    <div class="setting-row">
      <span>发射功率校准 txPower (dBm)</span>
      <div class="stepper">
        <button @click="stepSetting('txPower', -1, 0)">−</button>
        <span class="stepper-val">{{ state.txPower }}</span>
        <button @click="stepSetting('txPower', 1, 0)">＋</button>
      </div>
    </div>
  </div>

  <div class="note">
    说明：关闭「接收普通蓝牙」后不再收录非车钥匙设备，列表更干净也更省电（车钥匙识别照常）。开启「后台常驻感知」后，原生前台服务在
    App 退出后仍低功耗扫描车钥匙（状态栏有常驻提示）。配置「车控 App
    通知联动」并授予通知使用权后，满足距离条件会直接点击该 App
    常驻通知上的解锁/闭锁按钮，无需 Tasker。澎湃 OS 必做：①
    设置→应用→星舰蓝牙助手→省电策略「无限制」；② 打开「自启动」；③
    最近任务卡片下拉加锁。车辆认证始终由吉利银河 App 的官方数字钥匙完成。
  </div>
</template>
