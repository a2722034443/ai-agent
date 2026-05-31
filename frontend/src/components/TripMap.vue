<template>
  <section class="trip-map-panel">
    <div class="map-actions">
      <button type="button" @click="collapsed = !collapsed">{{ collapsed ? '展开地图' : '折叠地图' }}</button>
      <button type="button" @click="openInAmap">在高德APP打开</button>
    </div>

    <div v-if="!collapsed" class="map-body">
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
let drawTimer = null

const activePlan = computed(() => props.plans.find(plan => plan.rank === props.activeRank) || props.plans[0] || null)

watch(() => [props.activeRank, props.plans], () => {
  renderPlan()
}, { deep: true })

watch(collapsed, async value => {
  if (!value) {
    await nextTick()
    map?.resize()
    renderPlan()
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
    renderPlan()
  } catch (err) {
    loading.value = false
    error.value = err?.message || 'load-failed'
  }
}

function renderPlan() {
  if (!mapReady.value || !map || !amap || collapsed.value || !activePlan.value) return
  clearOverlays()
  const points = mapPoints(activePlan.value)
  unavailableStops.value = points.unavailable
  const usable = points.usable
  if (!usable.length) return

  const nextOverlays = []
  usable.forEach((point, index) => {
    const marker = new amap.Marker({
      position: [point.lng, point.lat],
      zIndex: point.kind === 'origin' ? 120 : 100 - index,
      content: markerContent(point),
      anchor: 'bottom-center'
    })
    marker.on('click', () => openInfo(point, marker))
    nextOverlays.push(marker)
  })

  const routeOverlays = routeLines(usable, activePlan.value)
  nextOverlays.push(...routeOverlays)
  overlays.value = nextOverlays
  map.add(nextOverlays)
  window.setTimeout(() => nextOverlays.forEach(overlay => overlay.show?.()), 20)
  map.setFitView(nextOverlays, false, [48, 48, 48, 48])
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

function routeLines(points, plan) {
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
    const color = colorFor(to)
    const line = new amap.Polyline({
      path: [[from.lng, from.lat]],
      strokeColor: color,
      strokeOpacity: 0.9,
      strokeWeight: 6,
      strokeStyle: 'solid',
      showDir: true,
      lineJoin: 'round',
      extData: { animatedPath: [[from.lng, from.lat], [to.lng, to.lat]] }
    })
    const label = new amap.Text({
      position: [(from.lng + to.lng) / 2, (from.lat + to.lat) / 2],
      text: `${formatDistance(totalDistance, segments)}，${formatMinutes(segmentMinutes[i], totalMinutes, segments)}分钟车程`,
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
    result.push(line, label)
    animateLine(line, [[from.lng, from.lat], [to.lng, to.lat]], i)
  }
  return result
}

function animateLine(line, path, index) {
  window.clearTimeout(drawTimer)
  drawTimer = window.setTimeout(() => {
    line.setPath(path)
  }, 80 + index * 110)
}

function clearOverlays() {
  window.clearTimeout(drawTimer)
  if (!map || !overlays.value.length) return
  overlays.value.forEach(overlay => overlay.hide?.())
  const previous = [...overlays.value]
  overlays.value = []
  window.setTimeout(() => map?.remove(previous), 300)
}

function markerContent(point) {
  const icon = point.kind === 'origin' ? '⌂' : point.kind === 'dining' ? '🍽' : '✦'
  return `<div class="amap-trip-marker marker-${point.kind}">
    <b>${icon}</b><span>${escapeHtml(point.name || '地点')}</span>
  </div>`
}

function openInfo(point, marker) {
  const content = `<div class="amap-info-card">
    <strong>${escapeHtml(point.name || '地点')}</strong>
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
  margin-top: 12px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 10px 24px rgba(91, 106, 150, .10);
  overflow: hidden;
}

.map-actions {
  position: absolute;
  z-index: 5;
  top: 10px;
  right: 10px;
  display: flex;
  gap: 6px;
}

.map-actions button,
.map-error button {
  min-height: 28px;
  border: 0;
  border-radius: 8px;
  padding: 0 9px;
  background: rgba(255,255,255,.92);
  color: #334155;
  box-shadow: 0 8px 18px rgba(91,106,150,.12);
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.map-body {
  position: relative;
  height: 400px;
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
    linear-gradient(90deg, rgba(248,250,252,.75), rgba(226,232,240,.88), rgba(248,250,252,.75)),
    repeating-linear-gradient(0deg, transparent 0 38px, rgba(148,163,184,.18) 39px 40px),
    repeating-linear-gradient(90deg, transparent 0 38px, rgba(148,163,184,.18) 39px 40px);
  background-size: 220px 100%, auto, auto;
  animation: map-shimmer 1.2s ease-in-out infinite;
}

.map-skeleton span {
  width: 70px;
  height: 70px;
  border-radius: 50%;
  border: 10px solid rgba(22,93,255,.12);
}

.map-skeleton i {
  position: absolute;
  width: 56%;
  height: 6px;
  border-radius: 999px;
  background: linear-gradient(90deg, #13b8a6, #ff7d00);
  transform: rotate(-12deg);
}

.map-error {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  padding: 28px;
  text-align: center;
  background: #f8fafc;
}

.map-error strong {
  color: #1d2436;
}

.map-error p {
  margin: 0;
  color: #64748b;
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
  border-radius: 8px;
  padding: 7px 10px;
  background: rgba(255,255,255,.9);
  color: #64748b;
  box-shadow: 0 8px 18px rgba(91,106,150,.12);
  font-size: 12px;
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
  box-shadow: 0 10px 22px rgba(15,23,42,.18), 0 0 0 4px rgba(255,255,255,.72);
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

:global(.marker-origin) { background: #165dff; }
:global(.marker-activity) { background: #13b8a6; }
:global(.marker-dining) { background: #ff7d00; }

:global(.amap-info-card) {
  width: 220px;
  border-radius: 10px;
  padding: 10px;
  background: rgba(255,255,255,.96);
  box-shadow: 0 12px 26px rgba(15,23,42,.18);
  color: #1d2436;
}

:global(.amap-info-card strong) {
  display: block;
  font-size: 14px;
}

:global(.amap-info-card p) {
  margin: 6px 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

:global(.amap-info-card span) {
  color: #334155;
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
</style>
