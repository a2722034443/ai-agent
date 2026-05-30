<template>
  <main class="instant-app">
    <div class="sky" aria-hidden="true">
      <span v-for="star in stars" :key="star.id" class="star" :style="star.style"></span>
    </div>

    <header class="topbar">
      <button class="brand" type="button" @click="setDock('home')">
        <span class="brand-orbit"></span>
        <span>
          <strong>立刻游</strong>
          <small>你的智能出行星图 · 记录每一次出发</small>
        </span>
      </button>

      <label class="search-pill">
        <span class="search-icon"></span>
        <input v-model="searchText" placeholder="搜索城市 / 机票 / 酒店 / 行程" @keydown.enter="applySearch" />
      </label>

      <div class="meta">
        <span>{{ todayText }}</span>
        <button class="avatar" type="button" @click="setDock('mine')">
          <span></span>
          <i></i>
        </button>
      </div>
    </header>

    <section class="glass status-card">
      <div class="card-title">
        <h2>当前出行状态</h2>
        <span>05.30 更新</span>
      </div>
      <h1>即刻出发期 <b></b></h1>
      <p>你正处于高效规划阶段，距离下一次完美出发更近一步。</p>
      <ul class="progress-list">
        <li v-for="item in progressItems" :key="item.name">
          <span :style="{ color: item.color }">{{ item.icon }}</span>
          <em>{{ item.name }}</em>
          <i><b :style="{ width: `${item.value}%`, background: item.color }"></b></i>
          <strong>{{ item.value }}%</strong>
        </li>
      </ul>
      <button class="soft-action" type="button" @click="setDock('trips')">查看详细状态</button>
    </section>

    <aside class="toolbar glass" aria-label="地图工具">
      <button type="button" title="定位" @click="focusPlanet('我的位置')">⌾</button>
      <button type="button" title="放大" @click="zoomLevel = Math.min(zoomLevel + 1, 3)">+</button>
      <button type="button" title="缩小" @click="zoomLevel = Math.max(zoomLevel - 1, 1)">−</button>
      <button type="button" title="全屏" @click="compactMap = !compactMap">□</button>
      <button type="button" title="图层" @click="legendOpen = !legendOpen">≡</button>
    </aside>

    <aside v-if="legendOpen" class="glass legend-card">
      <h2>图例</h2>
      <ol>
        <li v-for="item in legendItems" :key="item.name">
          <span :style="{ background: item.color }"></span>{{ item.name }}
        </li>
      </ol>
      <h3>连接强度</h3>
      <div class="link-sample strong"><i></i><span>强</span></div>
      <div class="link-sample medium"><i></i><span>中</span></div>
      <div class="link-sample weak"><i></i><span>弱</span></div>
    </aside>

    <section :class="['star-map', { compact: compactMap }]" :style="{ '--zoom': zoomLevel }">
      <svg class="orbit-lines" viewBox="0 0 1000 760" aria-hidden="true" preserveAspectRatio="none">
        <line
          v-for="line in connectionLines"
          :key="line.key"
          :x1="line.x1"
          :y1="line.y1"
          :x2="line.x2"
          :y2="line.y2"
          :class="line.strength"
        />
      </svg>

      <button
        class="planet planet-center"
        type="button"
        :class="{ active: activePlanet?.name === '我的位置' }"
        @click="focusPlanet('我的位置')"
      >
        <span>我</span>
      </button>

      <article
        v-for="planet in planets"
        :key="planet.name"
        :class="['planet-node', planet.className, { active: activePlanet?.name === planet.name }]"
        :style="{ left: `${planet.x}%`, top: `${planet.y}%`, '--planet-color': planet.color, '--planet-soft': planet.soft }"
      >
        <button class="planet" type="button" @click="focusPlanet(planet.name)">
          <span>{{ planet.short }}</span>
        </button>
        <button
          v-for="tag in planet.tags"
          :key="`${planet.name}-${tag.text}`"
          class="tag-dot"
          type="button"
          :style="{ left: `${tag.x}px`, top: `${tag.y}px` }"
          @click="quickFill(planet, tag.text)"
        >
          {{ tag.text }}
        </button>
        <button class="more-dot" type="button" @click="quickFill(planet, planet.name)">...</button>
      </article>
    </section>

    <section class="glass insight-card">
      <div class="card-title">
        <h2>旅行洞察</h2>
        <button type="button" @click="setDock('saved')">2 条新洞察</button>
      </div>
      <p><b>✦</b> 你最近更偏好“周末短途”的出行，放松身心是主要目的。</p>
      <p><b>✦</b> 你倾向在周五出发，选择自然风光类目的地，避开人流高峰。</p>
      <button class="link-button" type="button" @click="setDock('saved')">查看全部洞察 ›</button>
    </section>

    <aside class="glass time-filter">
      <button
        v-for="item in timeFilters"
        :key="item"
        type="button"
        :class="{ active: activeTimeFilter === item }"
        @click="activeTimeFilter = item"
      >
        {{ item }}
      </button>
    </aside>

    <aside class="glass mini-map" aria-label="缩略星图">
      <span class="mini-center"></span>
      <span v-for="planet in planets" :key="`mini-${planet.name}`" :style="{ left: `${planet.x}%`, top: `${planet.y}%`, background: planet.color }"></span>
    </aside>

    <section v-if="drawerVisible" class="glass planner-drawer">
      <div class="drawer-head">
        <div>
          <small>{{ drawerKicker }}</small>
          <h2>{{ drawerTitle }}</h2>
        </div>
        <button type="button" @click="drawerVisible = false">×</button>
      </div>

      <div v-if="activeDock === 'home'" class="drawer-grid">
        <article v-for="item in recommendationCards" :key="item.title" class="mini-card">
          <strong>{{ item.title }}</strong>
          <p>{{ item.text }}</p>
        </article>
      </div>

      <div v-if="activeDock === 'trips'" class="drawer-grid">
        <article v-for="trip in tripCards" :key="trip.title" class="mini-card">
          <strong>{{ trip.title }}</strong>
          <p>{{ trip.text }}</p>
          <button type="button" @click="message = trip.prompt">继续规划</button>
        </article>
      </div>

      <div v-if="activeDock === 'saved'" class="drawer-grid">
        <article v-for="spot in savedCards" :key="spot.title" class="mini-card">
          <strong>{{ spot.title }}</strong>
          <p>{{ spot.text }}</p>
          <button type="button" @click="message = spot.prompt">加入本次出行</button>
        </article>
      </div>

      <div v-if="activeDock === 'mine'" class="profile-grid">
        <div>
          <strong>偏好画像</strong>
          <span>短途、少排队、亲子友好、预算可控</span>
        </div>
        <div>
          <strong>数据来源</strong>
          <span>高德周边 POI、路线估算、天气与网页校验</span>
        </div>
      </div>

      <!-- loading 进度提示 -->
      <div v-if="loading" class="loading-steps">
        <span class="loading-dot"></span>
        <span :class="['step', { active: loadingStep >= 0 }]">解析意图</span>
        <span class="step-arrow">›</span>
        <span :class="['step', { active: loadingStep >= 1 }]">搜索地点</span>
        <span class="step-arrow">›</span>
        <span :class="['step', { active: loadingStep >= 2 }]">计算路线</span>
        <span class="step-arrow">›</span>
        <span :class="['step', { active: loadingStep >= 3 }]">生成方案</span>
      </div>

      <section v-if="clarificationFields.length" class="clarify-box">
        <div class="clarify-header">
          <span class="clarify-icon">✦</span>
          <h3>{{ clarification.message || '还需要补齐关键信息' }}</h3>
        </div>
        <article v-for="field in clarificationFields" :key="field.key" class="clarify-field">
          <div class="field-label">
            <strong>{{ field.label }}</strong>
            <p>{{ field.question || field.reason }}</p>
          </div>
          <div class="choice-row">
            <button
              v-for="choice in fieldChoices(field)"
              :key="choice"
              type="button"
              :class="{ selected: clarificationAnswers[field.key] === choice }"
              @click="clarificationAnswers[field.key] = choice"
            >
              {{ choice }}
            </button>
          </div>
          <input v-model="clarificationAnswers[field.key]" :placeholder="field.expectedAnswerHint || '也可以直接输入你的答案'" />
        </article>
        <button class="primary-button clarify-submit" type="button" :disabled="loading" @click="submitClarification">
          {{ loading ? '生成中…' : '补齐后生成方案' }}
        </button>
      </section>

      <section v-if="options.length" class="plan-results">
        <article
          v-for="option in options"
          :key="option.rank"
          :class="['plan-card', { selected: selectedRank === option.rank }]"
          @click="selectedRank = option.rank"
        >
          <div class="plan-card-head">
            <div class="plan-rank">{{ option.rank }}</div>
            <div class="plan-meta">
              <strong>{{ option.name || `方案 ${option.rank}` }}</strong>
              <p>{{ option.tagline || '基于真实周边地点生成' }}</p>
            </div>
            <button class="confirm-btn" type="button" :disabled="loading" @click.stop="confirm(option.rank)">
              {{ loading && selectedRank === option.rank ? '…' : '出发' }}
            </button>
          </div>
          <div class="plan-stats">
            <span>⏱ {{ formatHours(option.totalMinutes) }}</span>
            <span>💰 {{ formatMoney(option.budgetEstimate) }}</span>
            <span>📍 {{ option.route?.distanceKm || '-' }}km</span>
          </div>
          <ol v-if="option.timeline?.length" class="timeline">
            <li v-for="(stop, idx) in option.timeline" :key="idx" :class="['tl-item', stop.type === '餐饮' ? 'dining' : 'activity']">
              <span class="tl-time">{{ stop.time }}</span>
              <span class="tl-dot"></span>
              <div class="tl-content">
                <strong>{{ stop.name }}</strong>
                <span>{{ stop.type }} · {{ stop.durationMinutes }}分钟</span>
              </div>
            </li>
          </ol>
        </article>
        <label class="feedback-line">
          <input v-model="feedback" placeholder="例如：预算太高、太远了、换一家餐厅" />
          <button type="button" :disabled="!feedback.trim() || loading" @click="adjustPlan">调整</button>
        </label>
      </section>

      <div v-if="execution" class="execution-card">
        <div class="execution-icon">✓</div>
        <div>
          <strong>方案已确认执行</strong>
          <p>{{ execution.shareMessage }}</p>
        </div>
      </div>
      <p v-if="error" class="error-line">{{ error }}</p>
    </section>

    <footer class="composer glass">
      <button class="brand-orbit footer-orbit" type="button" @click="setDock('home')" aria-label="立刻游"></button>
      <label class="command-input">
        <input
          v-model="message"
          :placeholder="placeholderText"
          @focus="inputFocused = true"
          @blur="inputFocused = false"
          @keydown.enter="plan"
        />
      </label>
      <button type="button" :class="{ active: voiceRecording }" @click="toggleVoice">
        <span>语音</span>
      </button>
      <button type="button" @click="openImageTool">
        <span>图片</span>
      </button>
      <button type="button" :class="{ active: toolMenuOpen }" @click="toolMenuOpen = !toolMenuOpen">
        <span>更多</span>
      </button>
      <button class="primary-button" type="button" :disabled="!message.trim() || loading" @click="plan">
        {{ loading ? '规划中' : '出发' }}
      </button>
      <input ref="fileInput" class="hidden-file" type="file" accept="image/*" @change="handleImagePick" />
      <div v-if="toolMenuOpen" class="tool-popover">
        <button v-for="sample in samples" :key="sample.label" type="button" @click="useSample(sample.text)">{{ sample.label }}</button>
      </div>
    </footer>

    <nav class="dock glass">
      <button
        v-for="item in dockItems"
        :key="item.key"
        type="button"
        :class="{ active: activeDock === item.key }"
        @click="setDock(item.key)"
      >
        <span>{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>
  </main>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { confirmPlan, createPlan, createSession, sendFeedback } from './api.js'

const token = ref(localStorage.getItem('lla_token') || '')
const searchText = ref('')
const message = ref('今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。')
const feedback = ref('')
const loading = ref(false)
const loadingStep = ref(-1)
const error = ref('')
const currentPlanId = ref('')
const options = ref([])
const trace = ref([])
const intent = ref({})
const execution = ref(null)
const clarification = ref({})
const clarificationAnswers = ref({})
const selectedRank = ref(1)
const activeDock = ref('home')
const activePlanet = ref(null)
const activeTimeFilter = ref('全部')
const drawerVisible = ref(false)
const toolMenuOpen = ref(false)
const voiceRecording = ref(false)
const inputFocused = ref(false)
const compactMap = ref(false)
const legendOpen = ref(false)
const zoomLevel = ref(1)
const fileInput = ref(null)

const planets = [
  {
    name: '美食餐饮',
    short: '美食',
    className: 'food',
    color: '#ff9a3d',
    soft: 'rgba(255, 154, 61, .22)',
    x: 30,
    y: 66,
    strength: 'strong',
    tags: [
      { text: '火锅', x: -90, y: -44 },
      { text: '轻食', x: -110, y: 5 },
      { text: '约会餐厅', x: -55, y: 64 },
      { text: '亲子餐厅', x: 82, y: 22 }
    ]
  },
  {
    name: '亲子玩乐',
    short: '亲子',
    className: 'kids',
    color: '#54cdb5',
    soft: 'rgba(84, 205, 181, .23)',
    x: 70,
    y: 37,
    strength: 'medium',
    tags: [
      { text: '儿童乐园', x: -92, y: -66 },
      { text: '博物馆', x: 50, y: -94 },
      { text: '科技馆', x: 92, y: 36 },
      { text: '亲子活动', x: 60, y: 96 }
    ]
  },
  {
    name: '休闲娱乐',
    short: '娱乐',
    className: 'fun',
    color: '#ef9bd0',
    soft: 'rgba(239, 155, 208, .24)',
    x: 70,
    y: 63,
    strength: 'strong',
    tags: [
      { text: '展览', x: -112, y: -6 },
      { text: '密室逃脱', x: 84, y: -62 },
      { text: 'KTV', x: 96, y: 44 },
      { text: '桌游', x: 72, y: 96 }
    ]
  },
  {
    name: '商圈逛街',
    short: '商圈',
    className: 'mall',
    color: '#78a7ff',
    soft: 'rgba(120, 167, 255, .26)',
    x: 30,
    y: 38,
    strength: 'medium',
    tags: [
      { text: '潮流商圈', x: -130, y: -24 },
      { text: '市集', x: -118, y: 48 },
      { text: '打卡地', x: -44, y: 112 },
      { text: '网红店', x: 50, y: -104 }
    ]
  },
  {
    name: '好友聚会',
    short: '聚会',
    className: 'party',
    color: '#ffd37e',
    soft: 'rgba(255, 211, 126, .26)',
    x: 42,
    y: 83,
    strength: 'weak',
    tags: [
      { text: '烧烤', x: -116, y: -24 },
      { text: '聚餐', x: -118, y: 46 },
      { text: '夜场', x: -36, y: 106 },
      { text: '轰趴', x: 86, y: 58 }
    ]
  },
  {
    name: '运动休闲',
    short: '运动',
    className: 'sport',
    color: '#73bcff',
    soft: 'rgba(115, 188, 255, .27)',
    x: 50,
    y: 82,
    strength: 'strong',
    tags: [
      { text: '公园跑步', x: -126, y: -54 },
      { text: '健身', x: -116, y: 2 },
      { text: '骑行', x: -40, y: 104 },
      { text: '户外', x: 86, y: -62 }
    ]
  }
]

const stars = Array.from({ length: 96 }, (_, index) => ({
  id: index,
  style: {
    left: `${(index * 37) % 100}%`,
    top: `${(index * 61) % 100}%`,
    width: `${index % 5 === 0 ? 3 : 2}px`,
    height: `${index % 5 === 0 ? 3 : 2}px`,
    animationDelay: `${(index % 12) * .35}s`,
    opacity: .28 + (index % 7) * .08
  }
}))

const centerPoint = { x: 50, y: 52 }
const connectionLines = computed(() => planets.map(planet => ({
  key: planet.name,
  x1: centerPoint.x * 10,
  y1: centerPoint.y * 7.6,
  x2: planet.x * 10,
  y2: planet.y * 7.6,
  strength: planet.strength
})))

const progressItems = [
  { name: '行程推进度', value: 68, color: '#4b8dff', icon: '◇' },
  { name: '预算控制', value: 82, color: '#9274ee', icon: '◈' },
  { name: '舒适偏好', value: 76, color: '#56c7a9', icon: '◎' },
  { name: '效率优先度', value: 74, color: '#ff9d38', icon: '♡' }
]

const legendItems = [
  { name: '行程', color: '#9b7cf4' },
  { name: '预算', color: '#61ccb5' },
  { name: '交通', color: '#ffc06c' },
  { name: '住宿', color: '#f0a3c9' },
  { name: '人物', color: '#80aaff' },
  { name: '活动', color: '#e99bb9' }
]

const dockItems = [
  { key: 'home', label: '立刻游', icon: '◌' },
  { key: 'trips', label: '我的行程', icon: '⌁' },
  { key: 'saved', label: '足迹收藏', icon: '▢' },
  { key: 'mine', label: '我的', icon: '♙' }
]

const timeFilters = ['全部', '近7天', '近30天', '今年', '2024', '更早']

const samples = [
  { label: '周末短途', text: '本周六从杭州西湖附近出发，两个大人一个 6 岁孩子，预算 600 元，想安排 4 小时亲子活动和清淡晚餐。' },
  { label: '好友聚会', text: '今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。' },
  { label: '运动放松', text: '明天上午 10 点在成都太古里附近，一个人，预算 300 元，想运动出汗后找个安静地方吃轻食。' }
]

const recommendationCards = [
  { title: '高德周边 POI', text: '优先查真实营业地点，再做路线组合。' },
  { title: '短时出行节奏', text: '适合 2-6 小时城市漫游、亲子、聚会和运动。' },
  { title: '可解释推荐', text: '保留预算、距离、天气、风险和证据链。' }
]

const tripCards = [
  { title: '周末亲子 4 小时', text: '室内优先，减少步行，晚餐清淡。', prompt: samples[0].text },
  { title: '好友夜游', text: '先活动再用餐，避开排队高峰。', prompt: samples[1].text },
  { title: '运动恢复', text: '先运动，后轻食，控制交通折返。', prompt: samples[2].text }
]

const savedCards = [
  { title: '自然风光', text: '收藏 12 个轻徒步与公园目的地。', prompt: '这个周末想找自然风光，预算 500 元，路线轻松一点。' },
  { title: '城市漫游', text: '收藏 8 个展览、市集、咖啡路线。', prompt: '今晚想在附近做一次城市漫游，包含展览或市集，再吃一顿饭。' },
  { title: '亲子乐园', text: '收藏 6 个亲子室内地点。', prompt: '带孩子出门，想找安全、卫生间方便、少排队的亲子地点。' }
]

const clarificationFields = computed(() => clarification.value?.fields || [])
const activePlanetLabel = computed(() => activePlanet.value?.name || '我的位置')
const placeholderText = computed(() => inputFocused.value ? '输入地点、预算、同行人和想玩的内容...' : '记录此刻的想法、目的地、预算...')
const drawerKicker = computed(() => activeDock.value === 'home' ? activePlanetLabel.value : dockItems.find(item => item.key === activeDock.value)?.label)
const drawerTitle = computed(() => {
  if (loading.value) return '正在整理你的短时出行方案'
  if (clarificationFields.value.length) return '先补齐必要信息'
  if (options.value.length) return '可执行方案已生成'
  const titles = {
    home: '出行智能推荐',
    trips: '我的行程',
    saved: '足迹收藏',
    mine: '我的偏好与数据'
  }
  return titles[activeDock.value] || '立刻游'
})

const todayText = computed(() => {
  const now = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const hour = String(now.getHours()).padStart(2, '0')
  const minute = String(now.getMinutes()).padStart(2, '0')
  return `${month}.${day} ${week} ${hour}:${minute}`
})

function setDock(key) {
  activeDock.value = key
  drawerVisible.value = true
  toolMenuOpen.value = false
}

function focusPlanet(name) {
  activePlanet.value = name === '我的位置' ? { name } : planets.find(planet => planet.name === name)
  activeDock.value = 'home'
  drawerVisible.value = true
}

function quickFill(planet, tag) {
  focusPlanet(planet.name)
  message.value = `我在当前位置附近，想安排${planet.name}，重点考虑${tag}，预算和时间请先帮我澄清。`
}

function applySearch() {
  const text = searchText.value.trim()
  if (!text) return
  message.value = `我想搜索${text}相关的本地短时出行方案，请基于真实周边地点帮我规划。`
  plan()
}

function useSample(text) {
  message.value = text
  toolMenuOpen.value = false
}

function openImageTool() {
  fileInput.value?.click()
}

function handleImagePick(event) {
  const file = event.target.files?.[0]
  if (!file) return
  message.value = `我上传了一张图片“${file.name}”，想按图片里的风格找附近可玩的地点，请先结合地点、时间、预算继续澄清。`
  drawerVisible.value = true
  event.target.value = ''
}

function toggleVoice() {
  voiceRecording.value = !voiceRecording.value
  if (voiceRecording.value) {
    message.value = '语音记录中：想找一个附近轻松、不赶、预算可控的短时出行方案。'
  }
}

async function ensureSession() {
  if (token.value) return
  const data = await createSession('立刻游用户')
  token.value = data.token
}

async function plan() {
  if (!message.value.trim() || loading.value) return
  loading.value = true
  loadingStep.value = 0
  error.value = ''
  execution.value = null
  drawerVisible.value = true
  const stepTimer = setInterval(() => {
    if (loadingStep.value < 3) loadingStep.value++
  }, 2500)
  try {
    await ensureSession()
    loadingStep.value = 1
    const data = await createPlan({
      message: message.value,
      planCount: 3,
      stopCountPreference: '标准'
    })
    loadingStep.value = 3
    applyPlan(data)
  } catch (err) {
    handleRequestError(err)
  } finally {
    clearInterval(stepTimer)
    loading.value = false
    loadingStep.value = -1
  }
}

async function submitClarification() {
  loading.value = true
  error.value = ''
  try {
    await ensureSession()
    const data = await createPlan({
      message: message.value,
      planCount: 3,
      stopCountPreference: '标准',
      clarificationAnswers: clarificationAnswers.value,
      previousPlanId: currentPlanId.value || null
    })
    applyPlan(data)
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
  }
}

async function confirm(rank) {
  if (!currentPlanId.value) return
  loading.value = true
  error.value = ''
  selectedRank.value = rank
  try {
    const data = await confirmPlan(currentPlanId.value, rank)
    applyPlan(data)
    execution.value = data.execution
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
  }
}

async function adjustPlan() {
  if (!currentPlanId.value || !feedback.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    const data = await sendFeedback(currentPlanId.value, feedback.value)
    applyPlan(data)
    feedback.value = ''
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
  }
}

function applyPlan(data) {
  currentPlanId.value = data.planId || currentPlanId.value
  options.value = data.options || []
  trace.value = data.trace || []
  intent.value = data.intent || {}
  clarification.value = data.clarification || {}
  selectedRank.value = options.value[0]?.rank || 1
  if (!clarificationFields.value.length) clarificationAnswers.value = {}
  nextTick(() => {
    drawerVisible.value = true
  })
}

function handleRequestError(err) {
  error.value = err.message || '请求失败，请稍后重试。'
  const payload = err.payload || {}
  currentPlanId.value = payload.planId || currentPlanId.value
  trace.value = payload.trace || trace.value
  if (payload.clarification) clarification.value = payload.clarification
}

function fieldChoices(field) {
  if (Array.isArray(field.options) && field.options.length) {
    return field.options.map(option => typeof option === 'string' ? option : option?.text).filter(Boolean)
  }
  return field.suggestions || []
}

function formatHours(minutes) {
  if (!minutes) return '时间待确认'
  return `${Math.round((minutes / 60) * 10) / 10} 小时`
}

function formatMoney(value) {
  if (value === undefined || value === null || value === '') return '预算待确认'
  return `约 ${value} 元`
}
</script>

<style scoped>
.instant-app {
  position: relative;
  min-height: 100vh;
  min-height: 100svh;
  overflow: hidden;
  --page-edge: clamp(20px, 2.1vw, 44px);
  --topbar-height: 78px;
  --left-panel-width: clamp(310px, 18.6vw, 380px);
  --right-panel-width: clamp(320px, 19vw, 398px);
  --side-rail: clamp(350px, 25vw, 500px);
  --bottom-safe: 224px;
  padding: 14px var(--page-edge) var(--bottom-safe);
  color: #1d2436;
  background:
    radial-gradient(circle at 50% 48%, rgba(98, 126, 255, .28), transparent 20%),
    radial-gradient(circle at 30% 72%, rgba(255, 184, 105, .20), transparent 18%),
    radial-gradient(circle at 74% 36%, rgba(105, 210, 218, .22), transparent 20%),
    linear-gradient(135deg, #f7f7ff 0%, #eaf0ff 42%, #f8fbff 100%);
}

.sky {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 50% 54%, rgba(255,255,255,.82), transparent 2px),
    radial-gradient(circle at 20% 35%, rgba(255,255,255,.55), transparent 1px),
    radial-gradient(circle at 82% 28%, rgba(255,255,255,.65), transparent 2px);
}

.star {
  position: absolute;
  display: block;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 0 10px rgba(255,255,255,.88);
  animation: drift 8s ease-in-out infinite;
}

.glass {
  border: 1px solid rgba(255,255,255,.72);
  background: rgba(255,255,255,.34);
  box-shadow: 0 20px 46px rgba(91, 106, 150, .16), inset 0 1px 0 rgba(255,255,255,.72);
  backdrop-filter: blur(24px);
}

.instant-app > * {
  min-width: 0;
}

button,
input {
  font: inherit;
}

button {
  border: 0;
  cursor: pointer;
}

button:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.topbar {
  position: relative;
  z-index: 10;
  display: grid;
  grid-template-columns: minmax(260px, 360px) minmax(300px, 1fr) minmax(150px, 220px);
  align-items: center;
  gap: clamp(16px, 2vw, 28px);
  min-height: var(--topbar-height);
}

.brand {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  background: transparent;
  color: #090d20;
  text-align: left;
}

.brand strong {
  display: block;
  font-size: clamp(26px, 1.55vw, 32px);
  line-height: 1;
  font-weight: 900;
}

.brand small {
  display: block;
  margin-top: 8px;
  color: rgba(29,36,54,.68);
  font-size: 14px;
  line-height: 1.35;
}

.brand-orbit {
  width: clamp(58px, 3.7vw, 74px);
  height: clamp(58px, 3.7vw, 74px);
  flex: 0 0 auto;
  border-radius: 50%;
  background:
    radial-gradient(circle at 54% 55%, #fff 0 4%, #9d7dff 5% 15%, #326fff 16% 28%, transparent 30%),
    linear-gradient(145deg, #040715, #14102f 58%, #040715);
  box-shadow: 0 10px 28px rgba(25, 31, 74, .22), inset 0 0 0 2px rgba(255,255,255,.08);
  position: relative;
}

.brand-orbit::after {
  content: "";
  position: absolute;
  inset: 20px 8px;
  border-top: 2px solid rgba(255,255,255,.86);
  border-radius: 50%;
  transform: rotate(-28deg);
}

.search-pill {
  height: clamp(56px, 3.3vw, 66px);
  display: flex;
  align-items: center;
  gap: 18px;
  justify-self: center;
  width: min(560px, 100%);
  padding: 0 30px;
  border: 1px solid rgba(255,255,255,.82);
  border-radius: 32px;
  background: rgba(255,255,255,.48);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.8), 0 14px 32px rgba(92, 101, 138, .12);
  backdrop-filter: blur(20px);
}

.search-icon {
  width: 24px;
  height: 24px;
  border: 3px solid #5f687b;
  border-radius: 50%;
  position: relative;
}

.search-icon::after {
  content: "";
  position: absolute;
  right: -8px;
  bottom: -6px;
  width: 11px;
  height: 3px;
  border-radius: 2px;
  background: #5f687b;
  transform: rotate(45deg);
}

.search-pill input,
.command-input input,
.clarify-box input,
.feedback-line input {
  width: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: #1d2436;
}

.search-pill input {
  font-size: 20px;
}

.meta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 22px;
  color: #22293a;
  font-size: clamp(16px, 1vw, 20px);
}

.avatar {
  width: clamp(58px, 3.5vw, 70px);
  height: clamp(58px, 3.5vw, 70px);
  border-radius: 50%;
  border: 3px solid rgba(255,255,255,.92);
  background: linear-gradient(140deg, #1a1f2a, #e8e9ef);
  box-shadow: 0 12px 24px rgba(60, 72, 110, .18);
  position: relative;
}

.avatar span {
  position: absolute;
  width: 28px;
  height: 34px;
  left: 22px;
  top: 13px;
  border-radius: 50% 50% 44% 44%;
  background: #1e1f23;
}

.avatar i {
  position: absolute;
  inset: auto 15px 8px;
  height: 24px;
  border-radius: 50% 50% 0 0;
  background: #f3f4f7;
}

.status-card {
  position: absolute;
  z-index: 5;
  left: var(--page-edge);
  top: calc(14px + var(--topbar-height) + 18px);
  width: var(--left-panel-width);
  max-width: 100%;
  border-radius: 24px;
  padding: clamp(18px, 1.25vw, 24px);
}

.card-title,
.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.card-title h2,
.insight-card h2,
.legend-card h2,
.planner-drawer h2 {
  margin: 0;
  color: #111729;
  font-size: clamp(20px, 1.1vw, 22px);
}

.card-title span,
.drawer-head small {
  color: rgba(44, 54, 78, .62);
  font-size: 14px;
}

.status-card h1 {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 10px;
  font-size: clamp(27px, 1.55vw, 32px);
}

.status-card h1 b {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #56cbb6;
  box-shadow: 0 0 0 6px rgba(86,203,182,.18);
}

.status-card p,
.insight-card p,
.mini-card p,
.clarify-box p {
  margin: 0;
  color: rgba(29,36,54,.76);
  line-height: 1.65;
}

.progress-list {
  display: grid;
  gap: 12px;
  padding: 0;
  margin: 18px 0 18px;
  list-style: none;
}

.progress-list li {
  display: grid;
  grid-template-columns: 24px minmax(92px, .78fr) minmax(0, 1fr) 42px;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.progress-list em {
  font-style: normal;
}

.progress-list i {
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(42, 50, 74, .12);
}

.progress-list i b {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.soft-action,
.link-button,
.insight-card .card-title button {
  border-radius: 999px;
  background: rgba(255,255,255,.44);
  color: #2f6fde;
  transition: transform .18s ease, background .18s ease, opacity .18s ease;
}

.soft-action {
  width: 100%;
  min-height: 38px;
}

.toolbar {
  position: absolute;
  z-index: 6;
  left: calc(var(--page-edge) + var(--left-panel-width) + 16px);
  top: calc(14px + var(--topbar-height) + 18px);
  display: grid;
  gap: 12px;
  width: 58px;
  padding: 12px 0;
  border-radius: 20px;
}

.toolbar button {
  min-height: 34px;
  background: transparent;
  color: #111729;
  font-size: 30px;
  line-height: 1;
  transition: transform .18s ease, opacity .18s ease;
}

.legend-card {
  position: absolute;
  z-index: 6;
  left: calc(var(--page-edge) + var(--left-panel-width) + 16px);
  top: calc(14px + var(--topbar-height) + 282px);
  width: min(190px, var(--left-panel-width));
  border-radius: 24px;
  padding: 18px 22px;
}

.legend-card ol {
  display: grid;
  gap: 12px;
  padding: 0;
  margin: 16px 0 18px;
  list-style: none;
}

.legend-card li {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #40506b;
}

.legend-card li span {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.legend-card h3 {
  margin: 0 0 10px;
  font-size: 16px;
}

.link-sample {
  display: grid;
  grid-template-columns: 70px 1fr;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: #40506b;
}

.link-sample i {
  border-top: 3px solid rgba(57,65,92,.55);
}

.link-sample.medium i {
  border-top-width: 2px;
}

.link-sample.weak i {
  border-top-width: 1px;
  opacity: .5;
}

.star-map {
  position: relative;
  z-index: 2;
  width: clamp(680px, calc(100vw - var(--side-rail) - var(--side-rail)), 960px);
  height: clamp(500px, calc(100svh - 314px), 680px);
  min-height: 0;
  margin: clamp(40px, 5.2vh, 72px) auto 0;
  overflow: visible;
  transform: scale(var(--zoom));
  transform-origin: 50% 52%;
  transition: transform .24s ease;
}

.star-map.compact {
  transform: scale(.86);
}

.orbit-lines {
  position: absolute;
  inset: 0;
  overflow: visible;
}

.orbit-lines line {
  stroke: rgba(255,255,255,.78);
  stroke-linecap: round;
  filter: drop-shadow(0 0 8px rgba(255,255,255,.74));
}

.orbit-lines .strong {
  stroke-width: 3.2;
}

.orbit-lines .medium {
  stroke-width: 2;
}

.orbit-lines .weak {
  stroke-width: 1.1;
  opacity: .58;
}

.planet,
.planet-center {
  position: relative;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  transition: transform .2s ease, opacity .2s ease, box-shadow .2s ease;
}

.planet-center {
  position: absolute;
  left: 50%;
  top: 52%;
  z-index: 5;
  width: clamp(132px, 8.4vw, 170px);
  height: clamp(132px, 8.4vw, 170px);
  transform: translate(-50%, -50%);
  border: 2px solid rgba(255,255,255,.78);
  background:
    radial-gradient(circle at 45% 35%, rgba(255,255,255,.88), transparent 10%),
    radial-gradient(circle at 52% 60%, #2049f3, #4a69ff 44%, #dae6ff 78%);
  box-shadow: 0 0 0 28px rgba(255,255,255,.20), 0 0 0 54px rgba(143, 164, 255, .14), 0 0 60px rgba(68,97,255,.72);
}

.planet-center span {
  font-size: clamp(40px, 2.55vw, 52px);
  font-weight: 900;
}

.planet-node {
  position: absolute;
  z-index: 4;
  --node-size: clamp(112px, 6.9vw, 142px);
  width: var(--node-size);
  height: var(--node-size);
  transform: translate(-50%, -50%);
}

.planet-node .planet {
  width: var(--node-size);
  height: var(--node-size);
  border: 2px solid rgba(255,255,255,.72);
  background:
    radial-gradient(circle at 38% 28%, rgba(255,255,255,.92), transparent 13%),
    radial-gradient(circle at 50% 58%, var(--planet-color), var(--planet-soft) 76%, rgba(255,255,255,.56));
  box-shadow: 0 0 34px var(--planet-soft), inset 0 0 24px rgba(255,255,255,.48);
}

.planet-node .planet span {
  font-size: clamp(20px, 1.25vw, 25px);
  font-weight: 900;
  text-shadow: 0 2px 12px rgba(55,63,100,.24);
}

.planet-node.active .planet,
.planet:hover,
.planet-center:hover {
  transform: scale(1.06);
  opacity: .94;
}

.planet-center.active,
.planet-center:hover {
  transform: translate(-50%, -50%) scale(1.06);
  opacity: .94;
}

.tag-dot,
.more-dot {
  position: absolute;
  z-index: 6;
  min-width: clamp(72px, 4.2vw, 86px);
  min-height: 36px;
  padding: 0 13px;
  border-radius: 999px;
  border: 1px solid rgba(255,255,255,.86);
  background: rgba(255,255,255,.46);
  color: #30405a;
  font-weight: 700;
  white-space: nowrap;
  box-shadow: 0 10px 20px rgba(77, 88, 130, .10), inset 0 1px 0 rgba(255,255,255,.82);
  backdrop-filter: blur(18px);
  transition: transform .18s ease, opacity .18s ease;
}

.more-dot {
  right: -46px;
  bottom: -18px;
  min-width: 48px;
  width: 48px;
  padding: 0;
  font-size: 18px;
}

.insight-card {
  position: absolute;
  z-index: 5;
  right: var(--page-edge);
  top: calc(14px + var(--topbar-height) + 18px);
  width: var(--right-panel-width);
  max-width: 100%;
  border-radius: 28px;
  padding: clamp(22px, 1.5vw, 28px);
}

.insight-card .card-title button {
  padding: 0;
  background: transparent;
  color: #2c75e8;
}

.insight-card p {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 10px;
  margin-top: 28px;
  font-size: 17px;
}

.insight-card b {
  color: #8e77ee;
}

.link-button {
  margin-top: 28px;
  padding: 0;
  background: transparent;
  font-size: 16px;
}

.time-filter {
  position: absolute;
  z-index: 6;
  right: calc(var(--page-edge) + 20px);
  top: 49%;
  display: grid;
  gap: 18px;
  width: 96px;
  padding: 24px 0;
  border-radius: 24px;
}

.time-filter button {
  min-height: 24px;
  background: transparent;
  color: #40506b;
  font-size: 16px;
}

.time-filter button.active {
  color: #3178e9;
  font-weight: 900;
}

.mini-map {
  position: absolute;
  z-index: 6;
  right: calc(var(--page-edge) + 20px);
  bottom: calc(var(--bottom-safe) + 18px);
  width: clamp(142px, 8.8vw, 180px);
  height: clamp(104px, 6.5vw, 132px);
  border-radius: 22px;
}

.mini-map span {
  position: absolute;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  box-shadow: 0 0 12px currentColor;
}

.mini-map .mini-center {
  left: 50%;
  top: 52%;
  width: 16px;
  height: 16px;
  margin: -8px 0 0 -8px;
  background: #fff;
}

.planner-drawer {
  position: absolute;
  z-index: 12;
  right: clamp(220px, 13vw, 292px);
  bottom: calc(var(--bottom-safe) + 18px);
  width: clamp(380px, 28vw, 540px);
  max-height: min(42vh, 420px);
  overflow: auto;
  border-radius: 24px;
  padding: 22px;
}

.drawer-head button {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(255,255,255,.54);
  color: #40506b;
  font-size: 22px;
}

.drawer-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 18px;
}

.mini-card {
  min-height: 122px;
  border-radius: 18px;
  padding: 16px;
  background: rgba(255,255,255,.42);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.72);
}

.mini-card strong {
  display: block;
  margin-bottom: 8px;
}

.mini-card button,
.feedback-line button,
.choice-row button {
  min-height: 32px;
  margin-top: 12px;
  border-radius: 999px;
  padding: 0 12px;
  background: rgba(255,255,255,.58);
  color: #2f6fde;
}

.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 18px;
}

.profile-grid div {
  border-radius: 18px;
  padding: 16px;
  background: rgba(255,255,255,.42);
}

.profile-grid strong,
.profile-grid span {
  display: block;
}

.profile-grid span {
  margin-top: 8px;
  color: rgba(29,36,54,.72);
  line-height: 1.6;
}

.clarify-box,
.plan-results {
  display: grid;
  gap: 14px;
  margin-top: 18px;
}

.clarify-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.clarify-icon {
  color: #8e77ee;
  font-size: 18px;
}

.clarify-box h3 {
  margin: 0;
  font-size: 17px;
  color: #111729;
}

.clarify-field {
  border-radius: 18px;
  padding: 16px;
  background: rgba(255,255,255,.52);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.82);
  display: grid;
  gap: 10px;
}

.field-label strong {
  display: block;
  font-size: 15px;
  color: #111729;
  margin-bottom: 4px;
}

.field-label p {
  margin: 0;
  font-size: 13px;
  color: rgba(29,36,54,.62);
}

.clarify-submit {
  width: 100%;
  margin-top: 4px;
}

/* 方案卡片 */
.plan-card {
  border-radius: 20px;
  padding: 16px;
  background: rgba(255,255,255,.48);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.82);
  cursor: pointer;
  transition: box-shadow .18s ease, transform .18s ease;
}

.plan-card.selected {
  box-shadow: 0 0 0 2px rgba(74, 118, 255, .38), inset 0 1px 0 rgba(255,255,255,.82);
}

.plan-card-head {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 72px;
  align-items: center;
  gap: 12px;
}

.plan-rank {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4b83ff, #7b68ee);
  color: #fff;
  font-weight: 900;
  display: grid;
  place-items: center;
  font-size: 16px;
  flex-shrink: 0;
}

.plan-meta strong {
  display: block;
  font-size: 15px;
  color: #111729;
}

.plan-meta p {
  margin: 3px 0 0;
  font-size: 13px;
  color: rgba(29,36,54,.62);
}

.confirm-btn {
  min-height: 36px;
  border-radius: 999px;
  padding: 0 16px;
  background: linear-gradient(135deg, #4b83ff, #7b68ee);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  box-shadow: 0 6px 14px rgba(75, 105, 238, .22);
  transition: transform .18s ease, opacity .18s ease;
}

.confirm-btn:hover {
  transform: scale(1.04);
}

.confirm-btn:disabled {
  opacity: .5;
}

.plan-stats {
  display: flex;
  gap: 14px;
  margin: 10px 0 12px;
  font-size: 13px;
  color: rgba(29,36,54,.68);
}

/* 时间线 */
.timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 0;
}

.tl-item {
  display: grid;
  grid-template-columns: 48px 14px minmax(0, 1fr);
  gap: 0 10px;
  align-items: start;
  padding: 6px 0;
  position: relative;
}

.tl-item:not(:last-child)::after {
  content: '';
  position: absolute;
  left: 54px;
  top: 22px;
  bottom: -6px;
  width: 2px;
  background: rgba(74, 118, 255, .18);
}

.tl-time {
  font-size: 12px;
  color: rgba(29,36,54,.52);
  text-align: right;
  padding-top: 2px;
  white-space: nowrap;
}

.tl-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #4b83ff;
  margin-top: 3px;
  flex-shrink: 0;
}

.tl-item.dining .tl-dot {
  background: #ff9a3d;
}

.tl-content strong {
  display: block;
  font-size: 14px;
  color: #111729;
}

.tl-content span {
  font-size: 12px;
  color: rgba(29,36,54,.55);
}

/* 执行结果卡片 */
.execution-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-top: 14px;
  border-radius: 18px;
  padding: 16px;
  background: rgba(86,203,182,.14);
  border: 1px solid rgba(86,203,182,.28);
}

.execution-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #56cbb6;
  color: #fff;
  font-size: 18px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.execution-card strong {
  display: block;
  color: #1a6b5e;
  font-size: 15px;
}

.execution-card p {
  margin: 4px 0 0;
  color: rgba(22,80,70,.76);
  font-size: 13px;
  line-height: 1.6;
}

/* loading 步骤提示 */
.loading-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 12px 16px;
  border-radius: 14px;
  background: rgba(255,255,255,.42);
  font-size: 13px;
  color: rgba(29,36,54,.52);
}

.loading-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4b83ff;
  animation: pulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}

.loading-steps .step {
  color: rgba(29,36,54,.38);
  transition: color .3s ease, font-weight .3s ease;
}

.loading-steps .step.active {
  color: #2f6fde;
  font-weight: 700;
}

.step-arrow {
  color: rgba(29,36,54,.28);
}

.choice-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 4px 0;
}

.choice-row button.selected {
  background: rgba(74, 118, 255, .22);
  color: #245ed9;
  font-weight: 700;
}

.clarify-box input,
.feedback-line input {
  min-height: 42px;
  border-radius: 999px;
  padding: 0 14px;
  background: rgba(255,255,255,.48);
}

.feedback-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 82px;
  gap: 10px;
}

.primary-button {
  min-height: 46px;
  border-radius: 999px;
  padding: 0 22px;
  background: linear-gradient(135deg, #4b83ff, #7b68ee);
  color: #fff;
  font-weight: 900;
  box-shadow: 0 12px 24px rgba(75, 105, 238, .24);
}

.error-line {
  margin: 14px 0 0;
  border-radius: 16px;
  padding: 12px 14px;
  line-height: 1.6;
  background: rgba(255, 106, 106, .14);
  color: #a33145;
}

.composer {
  position: fixed;
  z-index: 20;
  left: var(--page-edge);
  right: var(--page-edge);
  bottom: 96px;
  min-height: 96px;
  display: grid;
  grid-template-columns: 88px minmax(240px, 1fr) 72px 72px 72px 92px;
  align-items: center;
  gap: 16px;
  border-radius: 28px;
  padding: 14px 28px;
  max-width: calc(100vw - var(--page-edge) * 2);
}

.footer-orbit {
  width: 58px;
  height: 58px;
  justify-self: center;
}

.command-input {
  height: 64px;
  display: flex;
  align-items: center;
  border-radius: 42px;
  padding: 0 38px;
  background: rgba(255,255,255,.58);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.86);
  transition: box-shadow .2s ease, transform .2s ease;
}

.command-input:focus-within {
  box-shadow: 0 0 0 5px rgba(77, 126, 255, .13), inset 0 1px 0 rgba(255,255,255,.86);
}

.command-input input {
  font-size: clamp(17px, 1.05vw, 21px);
}

.composer > button:not(.brand-orbit):not(.primary-button) {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: rgba(255,255,255,.46);
  color: #1e4a88;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.82);
}

.composer > button span {
  display: block;
  font-size: 14px;
}

.composer > button.active {
  background: rgba(74, 118, 255, .20);
}

.hidden-file {
  display: none;
}

.tool-popover {
  position: absolute;
  right: 130px;
  bottom: 82px;
  display: grid;
  gap: 8px;
  width: 160px;
  border-radius: 18px;
  padding: 12px;
  background: rgba(255,255,255,.86);
  box-shadow: 0 16px 34px rgba(70,83,122,.18);
}

.tool-popover button {
  min-height: 36px;
  border-radius: 999px;
  background: rgba(74, 118, 255, .12);
  color: #2c65d8;
}

.dock {
  position: fixed;
  z-index: 19;
  left: var(--page-edge);
  right: var(--page-edge);
  bottom: 26px;
  min-height: 64px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: center;
  border-radius: 24px;
  padding: 10px 80px;
  max-width: calc(100vw - var(--page-edge) * 2);
}

.dock button {
  justify-self: center;
  min-width: min(170px, 80%);
  min-height: 44px;
  border-radius: 999px;
  background: transparent;
  color: #3f485c;
  font-size: 18px;
  transition: transform .18s ease, background .18s ease, color .18s ease;
}

.dock button span {
  margin-right: 12px;
  color: #4f7ff3;
  font-size: 26px;
  vertical-align: middle;
}

.dock button.active {
  background: rgba(255,255,255,.46);
  color: #2f73ec;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.86);
}

.soft-action:hover,
.toolbar button:hover,
.tag-dot:hover,
.more-dot:hover,
.dock button:hover,
.composer > button:hover,
.mini-card button:hover,
.choice-row button:hover,
.feedback-line button:hover {
  transform: scale(1.04);
  opacity: .9;
}

.primary-button:active,
.soft-action:active,
.dock button:active,
.composer > button:active {
  transform: scale(.97);
}

@media (min-width: 981px) {
  .instant-app {
    --page-edge: clamp(24px, 2vw, 40px);
    --bottom-safe: 28px;
    display: grid;
    grid-template-columns: minmax(300px, 360px) minmax(560px, 1fr) minmax(300px, 360px);
    grid-template-areas:
      "topbar topbar topbar"
      "status map insight"
      "toolbar map filters"
      "legend map mini"
      "drawer drawer drawer"
      "composer composer composer"
      "dock dock dock";
    align-items: start;
    gap: 18px;
    min-height: 100vh;
    overflow: auto;
    overflow-x: hidden;
    padding: 14px var(--page-edge) var(--bottom-safe);
  }

  .topbar {
    grid-area: topbar;
  }

  .status-card,
  .insight-card,
  .toolbar,
  .legend-card,
  .time-filter,
  .mini-map,
  .planner-drawer,
  .composer,
  .dock {
    position: relative;
    inset: auto;
    width: 100%;
    max-width: 100%;
    margin: 0;
  }

  .status-card {
    grid-area: status;
  }

  .insight-card {
    grid-area: insight;
  }

  .toolbar {
    grid-area: toolbar;
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 8px;
    padding: 10px;
  }

  .toolbar button {
    min-height: 40px;
    font-size: 24px;
  }

  .legend-card {
    grid-area: legend;
    display: block;
  }

  .star-map {
    grid-area: map;
    width: 100%;
    max-width: 920px;
    height: clamp(520px, calc(100vh - 310px), 640px);
    min-height: 520px;
    margin: 0 auto;
    overflow: hidden;
    border-radius: 30px;
    transform: none;
  }

  .star-map.compact {
    transform: none;
  }

  .star-map .tag-dot,
  .star-map .more-dot {
    opacity: 0;
    pointer-events: none;
    transform: translateY(4px);
  }

  .planet-node:hover .tag-dot,
  .planet-node:hover .more-dot,
  .planet-node.active .tag-dot,
  .planet-node.active .more-dot {
    opacity: 1;
    pointer-events: auto;
    transform: translateY(0);
  }

  .time-filter {
    grid-area: filters;
    display: flex;
    justify-content: space-around;
    gap: 8px;
    padding: 12px 10px;
  }

  .time-filter button {
    min-width: 42px;
    font-size: 14px;
    white-space: nowrap;
  }

  .mini-map {
    grid-area: mini;
    height: 132px;
  }

  .planner-drawer {
    grid-area: drawer;
    max-height: none;
  }

  .drawer-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .composer {
    grid-area: composer;
    grid-template-columns: 72px minmax(260px, 1fr) 64px 64px 64px 92px;
    min-height: 84px;
    padding: 14px 22px;
  }

  .dock {
    grid-area: dock;
    min-height: 64px;
    padding: 10px 40px;
  }
}

@media (min-width: 981px) and (max-width: 1560px) {
  .instant-app {
    --page-edge: 30px;
    --bottom-safe: 28px;
    display: grid;
    grid-template-columns: minmax(260px, 290px) minmax(600px, 1fr) minmax(260px, 290px);
    grid-template-areas:
      "topbar topbar topbar"
      "status map insight"
      "toolbar map filters"
      "legend map mini"
      "drawer drawer drawer"
      "composer composer composer"
      "dock dock dock";
    align-items: start;
    gap: 14px;
    overflow: auto;
    overflow-x: hidden;
    padding: 14px var(--page-edge) var(--bottom-safe);
  }

  .topbar {
    grid-area: topbar;
  }

  .legend-card {
    display: block;
  }

  .status-card,
  .insight-card,
  .toolbar,
  .time-filter,
  .mini-map,
  .planner-drawer {
    position: relative;
    inset: auto;
    width: 100%;
    margin: 0;
  }

  .status-card {
    grid-area: status;
  }

  .insight-card {
    grid-area: insight;
  }

  .toolbar {
    grid-area: toolbar;
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 8px;
    padding: 10px;
  }

  .toolbar button {
    font-size: 24px;
  }

  .time-filter {
    grid-area: filters;
    display: flex;
    justify-content: space-around;
    gap: 8px;
    padding: 12px 10px;
  }

  .time-filter button {
    min-width: 42px;
    font-size: 14px;
    white-space: nowrap;
  }

  .star-map {
    grid-area: map;
    width: 100%;
    max-width: 900px;
    height: clamp(470px, calc(100vh - 330px), 560px);
    margin: 0 auto;
    transform-origin: 50% 48%;
    overflow: hidden;
    border-radius: 28px;
  }

  .planner-drawer {
    grid-area: drawer;
    max-height: none;
  }

  .drawer-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .mini-card {
    min-height: auto;
  }

  .mini-map {
    grid-area: mini;
    height: 132px;
  }

  .composer {
    grid-area: composer;
    position: relative;
    left: auto;
    right: auto;
    bottom: auto;
    width: 100%;
    grid-template-columns: 64px minmax(0, 1fr) repeat(3, 58px) 84px;
    min-height: 76px;
    padding: 10px 14px;
  }

  .command-input {
    height: 56px;
  }

  .dock {
    grid-area: dock;
    position: relative;
    left: auto;
    right: auto;
    bottom: auto;
    width: 100%;
    min-height: 56px;
    padding: 8px 10px;
  }

  .dock button {
    min-height: 40px;
  }
}

@media (max-width: 980px) {
  .topbar {
    grid-template-columns: 1fr;
  }

  .search-pill {
    justify-self: stretch;
    width: 100%;
  }

  .meta {
    justify-content: space-between;
  }

  .instant-app {
    --page-edge: 18px;
    --bottom-safe: 20px;
    grid-template-columns: 1fr;
    grid-template-areas:
      "topbar"
      "status"
      "toolbar"
      "map"
      "insight"
      "drawer"
      "filters"
      "mini"
      "composer"
      "dock";
    gap: 16px;
  }

  .composer {
    left: var(--page-edge);
    right: var(--page-edge);
  }
}

@media (max-width: 760px) {
  .instant-app {
    --bottom-safe: 18px;
    display: block;
    padding-top: 16px;
  }

  .status-card,
  .toolbar,
  .star-map,
  .insight-card,
  .planner-drawer,
  .time-filter,
  .mini-map,
  .composer,
  .dock {
    width: 100%;
    max-width: 100%;
    margin-top: 16px;
  }

  .topbar {
    gap: 12px;
  }

  .brand strong {
    font-size: 26px;
  }

  .brand-orbit {
    width: 58px;
    height: 58px;
  }

  .search-pill {
    height: 56px;
    padding: 0 20px;
  }

  .meta {
    font-size: 15px;
  }

  .search-pill input,
  .command-input input {
    font-size: 16px;
  }

  .status-card {
    padding: 20px;
    overflow: hidden;
  }

  .card-title {
    align-items: flex-start;
  }

  .card-title span {
    display: none;
  }

  .status-card h1 {
    font-size: 28px;
  }

  .status-card p {
    font-size: 14px;
  }

  .progress-list li {
    grid-template-columns: 18px minmax(76px, 88px) minmax(0, 1fr);
    gap: 7px;
    font-size: 12px;
  }

  .progress-list em {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .progress-list strong {
    display: none;
  }

  .star-map {
    width: 100%;
    height: 500px;
    transform: none;
    transform-origin: 50% 50%;
    margin: 0;
  }

  .planet-center {
    width: 118px;
    height: 118px;
  }

  .planet-node,
  .planet-node .planet {
    width: 92px;
    height: 92px;
  }

  .planet-node .planet span {
    font-size: 18px;
  }

  .tag-dot {
    min-width: 54px;
    min-height: 30px;
    padding: 0 7px;
    font-size: 10px;
  }

  .more-dot {
    width: 38px;
    min-width: 38px;
    min-height: 30px;
    right: -30px;
  }

  .drawer-grid,
  .profile-grid,
  .plan-results article,
  .feedback-line {
    grid-template-columns: 1fr;
  }

  .composer {
    grid-template-columns: 1fr 1fr 1fr;
    gap: 10px;
    min-height: 0;
    border-radius: 22px;
    padding: 12px;
  }

  .footer-orbit,
  .command-input,
  .composer .primary-button {
    grid-column: 1 / -1;
  }

  .command-input {
    height: 58px;
    padding: 0 18px;
  }

  .composer > button:not(.brand-orbit):not(.primary-button) {
    width: 100%;
    height: 48px;
    border-radius: 999px;
  }

  .dock {
    grid-template-columns: repeat(4, 1fr);
    min-height: 64px;
  }

  .dock button {
    min-width: 0;
    width: 100%;
    font-size: 13px;
  }

  .dock button span {
    display: block;
    margin: 0 0 2px;
    font-size: 20px;
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: .4; transform: scale(.7); }
}

@keyframes drift {
  0%, 100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(8px, -10px, 0);
  }
}
</style>
