<template>
  <section class="trip-map-panel">
    <div class="map-head">
      <div class="map-title">
        <span class="map-sticker">路线地图</span>
      </div>
      <div class="map-actions">
        <button type="button" @click="collapsed = !collapsed">{{ collapsed ? '展开地图' : '折叠地图' }}</button>
        <button type="button" @click="openInAmap">在高德APP打开</button>
      </div>
    </div>

    <div v-show="!collapsed" class="map-body">
      <div v-if="loading" class="map-skeleton" aria-label="地图加载中">
        <span></span>
        <i></i>
      </div>
      <div v-if="error" class="map-error">
        <strong>抱歉，地图暂时加载不出来</strong>
        <p>你可以点这里跳转到高德地图查看当前行程。</p>
        <button type="button" @click="openInAmap">跳转高德地图</button>
      </div>
      <div ref="mapRef" class="map-canvas" :class="{ hidden: loading || error }"></div>
      <div v-if="unavailableStops.length" class="unavailable-list">
        <span v-for="stop in unavailableStops" :key="stop.name">地点暂不可用：{{ stop.name }}</span>
      </div>
      <div v-if="guardMode" class="guard-notice">守护模式已开启，出行当天会同步位置、路况和突发调整。</div>
    </div>
  </section>
</template>

<script setup>
import AMapLoader from '@amap/amap-jsapi-loader'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  plans: { type: Array, default: () => [] },
  activeRank: { type: Number, default: 1 },
  origin: { type: Object, default: () => ({}) },
  guardMode: { type: Boolean, default: false }
})

const mapRef = ref(null)
const collapsed = ref(false)
const loading = ref(true)
const error = ref('')
const unavailableStops = ref([])
const overlays = ref([])
const mapReady = ref(false)

let amap = null
let map = null
let infoWindow = null
let renderTimer = null
let lastSignature = ''
let markerPool = []
let linePool = []
let labelPool = []
let drawTimers = []

const activePlan = computed(() => props.plans.find(plan => plan.rank === props.activeRank) || props.plans[0] || null)

watch(() => [props.activeRank, props.plans], () => {
  scheduleRender()
}, { deep: true })

watch(collapsed, async value => {
  if (!value) {
    await nextTick()
    if (!map && amap && mapRef.value) {
      recreateMap()
      return
    }
    lastSignature = ''
    map?.resize()
    scheduleRender(0)
  }
})

initMap()

async function initMap() {
  const key = import.meta.env.VITE_AMAP_JS_KEY
  const securityCode = import.meta.env.VITE_AMAP_JS_SECURITY_CODE
  if (!key) {
    loading.value = false
    error.value = 'missing-key'
    return
  }
  if (securityCode) {
    window._AMapSecurityConfig = { securityJsCode: securityCode }
  }
  try {
    amap = await AMapLoader.load({
      key,
      version: import.meta.env.VITE_AMAP_JS_VERSION || '2.0'
    })
    await nextTick()
    map = new amap.Map(mapRef.value, {
      zoom: 13,
      center: defaultCenter(),
      viewMode: '2D',
      mapStyle: 'amap://styles/normal',
      features: ['bg', 'road', 'building'],
      zoomEnable: true,
      dragEnable: true,
      showLabel: true
    })
    infoWindow = new amap.InfoWindow({ offset: new amap.Pixel(0, -32), isCustom: true })
    mapReady.value = true
    loading.value = false
    scheduleRender(0)
  } catch (err) {
    loading.value = false
    error.value = err?.message || 'load-failed'
  }
}

function recreateMap() {
  if (!amap || !mapRef.value) return
  map?.destroy?.()
  map = new amap.Map(mapRef.value, {
    zoom: 13,
    center: defaultCenter(),
    viewMode: '2D',
    mapStyle: 'amap://styles/normal',
    features: ['bg', 'road', 'building'],
    zoomEnable: true,
    dragEnable: true,
    showLabel: true
  })
  infoWindow = new amap.InfoWindow({ offset: new amap.Pixel(0, -32), isCustom: true })
  mapReady.value = true
  lastSignature = ''
  scheduleRender(0)
}

function scheduleRender(delay = 120) {
  window.clearTimeout(renderTimer)
  renderTimer = window.setTimeout(() => renderPlan(), delay)
}

function renderPlan() {
  if (!mapReady.value || !map || !amap || collapsed.value || !activePlan.value) return
  const points = mapPoints(activePlan.value)
  unavailableStops.value = points.unavailable
  const usable = points.usable
  const signature = overlaySignature(usable, activePlan.value)
  if (signature === lastSignature) return
  lastSignature = signature
  if (!usable.length) {
    hideUnused(markerPool, 0)
    hideUnused(linePool, 0)
    hideUnused(labelPool, 0)
    overlays.value = []
    return
  }

  usable.forEach((point, index) => {
    const marker = markerFor(index)
    marker.setPosition([point.lng, point.lat])
    marker.setzIndex?.(point.kind === 'origin' ? 120 : 100 - index)
    marker.setContent(markerContent(point))
    marker.__point = point
    marker.show()
  })
  hideUnused(markerPool, usable.length)

  const routeOverlays = updateRouteLines(usable, activePlan.value)
  overlays.value = [...markerPool.slice(0, usable.length), ...routeOverlays]
  map.setFitView(overlays.value, false, [24, 24, 24, 24])
}

function mapPoints(plan) {
  const timeline = Array.isArray(plan.timeline) ? plan.timeline : []
  const stops = timeline.map((stop, index) => ({
    ...stop,
    kind: stop.type === '餐饮' ? 'dining' : index === 0 ? 'activity' : 'activity',
    lng: Number(stop.lng),
    lat: Number(stop.lat)
  }))
  const usableStops = stops.filter(hasPoint)
  const unavailable = stops.filter(stop => !hasPoint(stop))
  const first = usableStops[0]
  const origin = hasPoint(props.origin)
    ? { name: '我的位置', address: props.origin.district || props.origin.city || '', kind: 'origin', lng: Number(props.origin.lng), lat: Number(props.origin.lat) }
    : first
      ? { name: '我的位置', address: '按当前方案起点估算', kind: 'origin', lng: first.lng, lat: first.lat }
      : null
  const usable = origin ? [origin, ...usableStops] : usableStops
  return { usable, unavailable }
}

function updateRouteLines(points, plan) {
  if (points.length < 2) return []
  const result = []
  const route = plan.route || {}
  const totalDistance = Number(route.distanceKm || 0)
  const totalMinutes = Number(route.travelMinutes || 0)
  const segmentMinutes = Array.isArray(route.segmentMinutes) ? route.segmentMinutes : []
  const segments = points.length - 1
  for (let i = 0; i < segments; i++) {
    const from = points[i]
    const to = points[i + 1]
    if (!hasPoint(from) || !hasPoint(to)) continue
    const color = colorFor(to)
    const line = lineFor(i)
    const label = labelFor(i)
    line.setOptions?.({ strokeColor: color, strokeOpacity: 0.9, strokeWeight: 6 })
    label.setPosition([(from.lng + to.lng) / 2, (from.lat + to.lat) / 2])
    label.setText(`${formatDistance(totalDistance, segments)}，${formatMinutes(segmentMinutes[i], totalMinutes, segments)}分钟车程`)
    line.show()
    label.show()
    result.push(line, label)
    animateLine(line, [[from.lng, from.lat], [to.lng, to.lat]], i)
  }
  hideUnused(linePool, segments)
  hideUnused(labelPool, segments)
  return result
}

function animateLine(line, path, index) {
  if (drawTimers[index]) window.clearTimeout(drawTimers[index])
  if (!isValidPolylinePath(path)) {
    line.hide?.()
    return
  }
  // AMap Polyline requires at least two points; a single-point path throws.
  line.setPath([path[0], path[0]])
  drawTimers[index] = window.setTimeout(() => {
    line.setPath(path)
  }, 80 + index * 110)
}

function isValidPolylinePath(path) {
  return Array.isArray(path)
    && path.length >= 2
    && path.every(point => Array.isArray(point)
      && point.length >= 2
      && Number.isFinite(Number(point[0]))
      && Number.isFinite(Number(point[1])))
}

function clearOverlays() {
  drawTimers.forEach(timer => window.clearTimeout(timer))
  drawTimers = []
  window.clearTimeout(renderTimer)
  if (!map || !overlays.value.length) return
  const previous = [...overlays.value]
  overlays.value = []
  map?.remove(previous)
  markerPool = []
  linePool = []
  labelPool = []
  lastSignature = ''
}

function markerFor(index) {
  if (markerPool[index]) return markerPool[index]
  const marker = new amap.Marker({
    position: defaultCenter(),
    zIndex: 100 - index,
    content: '',
    anchor: 'bottom-center'
  })
  marker.on('click', () => openInfo(marker.__point || {}, marker))
  map.add(marker)
  markerPool[index] = marker
  return marker
}

function lineFor(index) {
  if (linePool[index]) return linePool[index]
  const line = new amap.Polyline({
    path: [],
    strokeColor: '#13b8a6',
    strokeOpacity: 0.9,
    strokeWeight: 6,
    strokeStyle: 'solid',
    showDir: true,
    lineJoin: 'round'
  })
  map.add(line)
  linePool[index] = line
  return line
}

function labelFor(index) {
  if (labelPool[index]) return labelPool[index]
  const label = new amap.Text({
    position: defaultCenter(),
    text: '',
    anchor: 'center',
    style: {
      padding: '5px 8px',
      border: '0',
      borderRadius: '8px',
      backgroundColor: 'rgba(255,255,255,.88)',
      color: '#334155',
      boxShadow: '0 8px 18px rgba(91,106,150,.16)',
      fontSize: '12px'
    }
  })
  map.add(label)
  labelPool[index] = label
  return label
}

function hideUnused(pool, usedCount) {
  pool.slice(usedCount).forEach(overlay => overlay.hide?.())
}

function overlaySignature(points, plan) {
  return [
    plan?.rank || '',
    ...points.map(point => `${point.name}:${point.lng.toFixed(6)},${point.lat.toFixed(6)}`),
    JSON.stringify(plan?.route || {})
  ].join('|')
}

function markerContent(point) {
  const icon = point.kind === 'origin' ? '⌂' : point.kind === 'dining' ? '🍽' : '✦'
  return `<div class="amap-trip-marker marker-${point.kind}">
    <b>${icon}</b><span>${escapeHtml(simplifyPoiName(point.name || '地点'))}</span>
  </div>`
}

function openInfo(point, marker) {
  const content = `<div class="amap-info-card">
    <strong>${escapeHtml(simplifyPoiName(point.name || '地点'))}</strong>
    <p>${escapeHtml(point.address || '地址以高德地图为准')}</p>
    <span>评分 ${point.rating || '暂无'} · 可预约状态以商家实时状态为准 · 营业时间以门店为准</span>
  </div>`
  infoWindow.setContent(content)
  infoWindow.open(map, marker.getPosition())
}

function openInAmap() {
  const points = activePlan.value ? mapPoints(activePlan.value).usable : []
  if (points.length < 2) {
    window.open('https://uri.amap.com/', '_blank')
    return
  }
  const origin = points[0]
  const destination = points[points.length - 1]
  const vias = points.slice(1, -1).map(point => `${point.lng},${point.lat},${encodeURIComponent(point.name || '途经点')}`).join(';')
  const params = new URLSearchParams({
    from: `${origin.lng},${origin.lat},${origin.name || '我的位置'}`,
    to: `${destination.lng},${destination.lat},${destination.name || '目的地'}`,
    mode: 'car',
    policy: '1',
    src: 'local-life-agent',
    callnative: '1'
  })
  if (vias) params.set('via', vias)
  window.open(`https://uri.amap.com/navigation?${params.toString()}`, '_blank')
}

function hasPoint(point) {
  return Number.isFinite(Number(point?.lng)) && Number.isFinite(Number(point?.lat)) && Number(point.lng) !== 0 && Number(point.lat) !== 0
}

function defaultCenter() {
  if (hasPoint(props.origin)) return [Number(props.origin.lng), Number(props.origin.lat)]
  const point = activePlan.value?.timeline?.find(hasPoint)
  return point ? [Number(point.lng), Number(point.lat)] : [121.4737, 31.2304]
}

function colorFor(point) {
  if (point.kind === 'origin') return '#165dff'
  if (point.kind === 'dining') return '#ff7d00'
  return '#13b8a6'
}

function formatDistance(totalDistance, segments) {
  if (!totalDistance || !segments) return '约1.2km'
  return `${Math.max(0.1, Math.round((totalDistance / segments) * 10) / 10)}km`
}

function formatMinutes(segmentValue, totalMinutes, segments) {
  if (Number.isFinite(Number(segmentValue)) && Number(segmentValue) > 0) return Math.round(Number(segmentValue))
  if (totalMinutes && segments) return Math.max(1, Math.round(totalMinutes / segments))
  return 5
}

function simplifyPoiName(name) {
  const raw = String(name || '地点').trim()
  const suffix = raw.match(/（[^）]+）|\([^)]+\)$/)?.[0] || ''
  let base = suffix ? raw.slice(0, -suffix.length) : raw
  base = base
    .replace(/羊肉(?=手抓饭|泡馍|汤|面|粉)/g, '')
    .replace(/(手抓饭)羊肉串/g, '$1')
    .replace(/(.{2,6})\1+/g, '$1')
    .replace(/(旗舰店|体验店|官方店|专门店|主题店){2,}/g, '$1')
  if (base.length > 14) {
    base = base.replace(/(餐厅|饭店|美食|小吃|料理|烤肉|烧烤|火锅|咖啡|影院|影城|公园|广场).*$/, '$1')
  }
  return `${base}${suffix}`
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[char])
}

onBeforeUnmount(() => {
  clearOverlays()
  map?.destroy()
  map = null
})
</script>

<style scoped>
.trip-map-panel {
  position: relative;
  width: 100%;
  max-width: 100%;
  margin-top: 12px;
  border: 3px solid var(--ink-strong);
  border-radius: 1.8rem;
  background: linear-gradient(180deg, #fffef8 0%, #fff4df 100%);
  box-shadow: var(--panel-shadow);
  overflow: hidden;
}

.map-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: .8rem;
  padding: 1rem 1rem .75rem;
}

.map-title {
  display: flex;
  align-items: center;
}

.map-sticker {
  width: fit-content;
  border: 1px solid rgba(145, 120, 95, .22);
  border-radius: 999px;
  padding: .2rem .6rem;
  background: rgba(255, 221, 149, .52);
  color: #7a5b47;
  font-size: .76rem;
  font-weight: 900;
}

.map-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.map-actions button,
.map-error button {
  min-height: 2.5rem;
  border: 1px solid rgba(145, 120, 95, .16);
  border-radius: .95rem;
  padding: 0 .8rem;
  background: rgba(255,255,255,.62);
  color: #6c4e3d;
  box-shadow: none;
  font-size: .8rem;
  font-weight: 900;
  cursor: pointer;
}

.map-body {
  position: relative;
  height: clamp(30rem, 62vh, 44rem);
  margin: 0 .9rem .9rem;
  border: 1px dashed rgba(145, 120, 95, .14);
  border-radius: 1.4rem;
  overflow: hidden;
  background: rgba(255,255,255,.62);
}

.map-canvas,
.map-skeleton,
.map-error {
  position: absolute;
  inset: 0;
}

.map-canvas.hidden {
  opacity: 0;
  pointer-events: none;
}

.map-skeleton {
  display: grid;
  place-items: center;
  background:
    linear-gradient(90deg, rgba(255,250,241,.76), rgba(255, 221, 189, .92), rgba(255,250,241,.76)),
    repeating-linear-gradient(0deg, transparent 0 38px, rgba(111,71,50,.11) 39px 40px),
    repeating-linear-gradient(90deg, transparent 0 38px, rgba(111,71,50,.11) 39px 40px);
  background-size: 220px 100%, auto, auto;
  animation: map-shimmer 1.2s ease-in-out infinite;
}

.map-skeleton span {
  width: 70px;
  height: 70px;
  border-radius: 50%;
  border: 10px solid rgba(140, 178, 255, .18);
}

.map-skeleton i {
  position: absolute;
  width: 56%;
  height: 6px;
  border-radius: 999px;
  background: linear-gradient(90deg, #8cb2ff, #ff9368);
  transform: rotate(-12deg);
}

.map-error {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  padding: 28px;
  text-align: center;
  background: #fff7ee;
}

.map-error strong {
  color: var(--ink-strong);
}

.map-error p {
  margin: 0;
  color: #8c6752;
  font-size: 14px;
}

.unavailable-list,
.guard-notice {
  position: absolute;
  left: 12px;
  bottom: 12px;
  z-index: 4;
  display: grid;
  gap: 6px;
}

.unavailable-list span,
.guard-notice {
  border: 1px solid rgba(145, 120, 95, .16);
  border-radius: 1rem;
  padding: 7px 10px;
  background: rgba(255,250,241,.94);
  color: #8c6752;
  box-shadow: none;
  font-size: 12px;
  font-weight: 800;
}

:global(.amap-trip-marker) {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 72px;
  max-width: 160px;
  padding: 6px 8px;
  border-radius: 999px;
  color: #fff;
  box-shadow: 0 10px 22px rgba(111,71,50,.18), 0 0 0 4px rgba(255,255,255,.72);
  animation: marker-pop .3s cubic-bezier(.2, .9, .25, 1.2);
  white-space: nowrap;
}

:global(.amap-trip-marker b) {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(255,255,255,.22);
  font-size: 12px;
}

:global(.amap-trip-marker span) {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  font-weight: 900;
}

:global(.marker-origin) { background: #5d87ef; }
:global(.marker-activity) { background: #55b485; }
:global(.marker-dining) { background: #ff9368; }

:global(.amap-info-card) {
  width: 220px;
  border: 2px solid #563321;
  border-radius: 14px;
  padding: 10px;
  background: rgba(255,250,241,.96);
  box-shadow: 0 12px 26px rgba(111,71,50,.18);
  color: #563321;
}

:global(.amap-info-card strong) {
  display: block;
  font-size: 14px;
}

:global(.amap-info-card p) {
  margin: 6px 0;
  color: #8c6752;
  font-size: 12px;
  line-height: 1.45;
}

:global(.amap-info-card span) {
  color: #725542;
  font-size: 12px;
  line-height: 1.45;
}

@keyframes marker-pop {
  from { opacity: 0; transform: scale(.72) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

@keyframes map-shimmer {
  0% { background-position: -220px 0, 0 0, 0 0; }
  100% { background-position: 220px 0, 0 0, 0 0; }
}

@media (max-width: 760px) {
  .map-head {
    flex-direction: column;
  }

  .map-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .map-body {
    height: 300px;
  }
}
</style>
