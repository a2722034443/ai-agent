<template>
  <main class="chat-app">
    <div class="sky" aria-hidden="true">
      <span v-for="star in stars" :key="star.id" class="star" :style="star.style"></span>
    </div>

    <header class="topbar glass">
      <button class="brand" type="button" @click="activeView = 'chat'">
        <span class="brand-orbit"></span>
        <span>
          <strong>立刻游</strong>
          <small>多人协同本地短时出行 AI 助理</small>
        </span>
      </button>
      <div class="meta">
        <span>{{ todayText }}</span>
        <button class="avatar" type="button" @click="activeView = 'profile'">
          <span></span>
          <i></i>
        </button>
      </div>
    </header>

    <section v-if="activeView === 'chat'" :class="['chat-shell', { 'result-mode': showPlanWorkspace }]">
      <div v-if="messages.length === 0" class="empty-state">
        <span class="empty-logo brand-orbit"></span>
        <h1>嗨，我是你的出行助理</h1>
        <p>一句话告诉我所有人的需求，我会帮你查地点、排路线、订东西，出发当天继续守护。</p>
        <div class="quick-prompts">
          <button v-for="sample in quickPrompts" :key="sample.label" type="button" @click="useSample(sample.text)">
            {{ sample.label }}
          </button>
        </div>
      </div>

      <div v-else class="step-summary">
        <div class="step-card-list">
          <button
            v-for="step in stepCards"
            :key="step.key"
            type="button"
            :class="['step-card', step.status]"
            @click="goToStep(step.key)"
          >
            <span>{{ step.index }}</span>
            <strong>{{ step.title }}</strong>
            <small>{{ step.summary }}</small>
          </button>
        </div>
        <button
          v-if="hasFinalPlans"
          class="summary-share"
          type="button"
          @click="createShareLink"
        >
          分享给同行人
        </button>
      </div>

      <section v-if="hasFinalPlans" v-show="showPlanWorkspace" class="result-workspace">
        <aside class="result-plans" aria-label="方案列表">
          <article
            v-for="plan in shownPlans"
            :key="plan.rank"
            :class="['plan-card', { active: activeMapRank === plan.rank }]"
            @click="viewPlanOnMap(plan.rank)"
          >
            <header>
              <strong>{{ plan.name }}</strong>
              <span>{{ plan.tag }}</span>
            </header>
            <p class="timeline-line">{{ compactTimeline(plan.timeline) }}</p>
            <div class="plan-meta">
              <span>预算 {{ formatMoney(plan.budgetEstimate) }}</span>
              <span>{{ formatHours(plan.totalMinutes) }}</span>
              <span>距离 {{ plan.route?.distanceKm || '2.1' }}km</span>
            </div>
            <footer>
              <button type="button" @click.stop="expandedRank = expandedRank === plan.rank ? null : plan.rank">查看详情</button>
              <button class="pick-button" type="button" @click.stop="selectPlan(plan.rank)">选这个</button>
            </footer>
            <div v-if="expandedRank === plan.rank" class="plan-detail">
              <p v-for="reason in planAdvantages(plan)" :key="reason">{{ reason }}</p>
            </div>
          </article>
        </aside>
        <div class="result-map">
          <TripMap
            :plans="shownPlans"
            :active-rank="activeMapRank"
            :origin="mapOrigin"
            :guard-mode="activeView === 'execute'"
          />
        </div>
      </section>

      <ol v-if="!showPlanWorkspace" class="message-list">
        <li v-for="item in messages" :key="item.id" :class="['message-row', item.role]">
          <span v-if="item.role === 'assistant'" class="bubble-avatar brand-orbit"></span>
          <article class="bubble">
            <time>{{ item.time }}</time>
            <p v-if="item.text">{{ item.text }}</p>
            <div v-if="item.loading" class="typing">
              <span></span><span></span><span></span>
              正在为你规划方案...
            </div>
            <div v-if="item.clarification?.fields?.length" class="clarify-card">
              <header class="clarify-head">
                <strong>{{ item.clarification.message || '还需要补齐几个关键信息' }}</strong>
              </header>
              <div class="clarify-layout">
                <form class="clarify-form" @submit.prevent="submitClarification">
                  <label v-for="field in orderedClarificationFields(item.clarification.fields)" :key="field.key">
                    <span>{{ field.label }}</span>
                    <input
                      v-model="clarificationAnswers[field.key]"
                      :placeholder="clarificationPlaceholder(field)"
                      @input="handleClarificationInput(field.key)"
                    />
                  </label>
                  <button class="primary-button clarify-submit" type="submit" :disabled="loading">补齐后规划</button>
                </form>
                <aside class="clarify-presets" aria-label="智能补齐选项">
                  <button
                    v-for="preset in clarificationPresets(item)"
                    :key="preset.code"
                    type="button"
                    :class="['preset-card', { selected: selectedClarificationPreset === preset.code }]"
                    @click="applyClarificationPreset(preset)"
                  >
                    <span class="preset-check">✓</span>
                    <strong><b>{{ preset.code }}</b>{{ preset.title }}</strong>
                    <small>{{ preset.summary }}</small>
                  </button>
                </aside>
              </div>
            </div>
          </article>
        </li>
      </ol>
    </section>

    <section v-else-if="activeView === 'collab'" class="page-panel">
      <header>
        <span class="brand-orbit"></span>
        <div>
          <h1>小明分享的出行方案</h1>
          <p>大家一起选方案、提意见，AI 会自动调整。</p>
        </div>
      </header>
      <div class="shared-plans">
        <article v-for="plan in shownPlans" :key="plan.rank" class="plan-card">
          <header>
            <strong>{{ plan.name }}</strong>
            <span>{{ voteCount(plan.rank) }} 票</span>
          </header>
          <p class="timeline-line">{{ compactTimeline(plan.timeline) }}</p>
          <button class="pick-button" type="button" @click="vote(plan.rank)">
            {{ votedRank === plan.rank ? '已投票' : '投票选这个' }}
          </button>
        </article>
      </div>
      <label class="comment-box">
        <input v-model="collabComment" placeholder="提你的意见，比如：能不能把吃饭时间调后半小时？" />
        <button type="button" @click="submitComment">提交意见</button>
      </label>
      <ul class="comments">
        <li v-for="comment in comments" :key="comment.id">
          <span>{{ comment.name }}</span>
          <p>{{ comment.text }}</p>
        </li>
      </ul>
    </section>

    <section v-else-if="activeView === 'execute'" class="page-panel execution-panel">
      <h1>AI 正在帮你把事做完</h1>
      <ol>
        <li v-for="step in executionSteps" :key="step.name" :class="step.status">
          <span>{{ step.status === 'done' ? '✓' : step.status === 'doing' ? '...' : '!' }}</span>
          {{ step.name }}
        </li>
      </ol>
      <button class="primary-button" type="button" @click="copyShareMessage">发给同行人</button>
    </section>

    <section v-else class="page-panel profile-panel">
      <h1>全员记忆</h1>
      <p>老婆最近在减肥、孩子需要亲子设施、朋友不吃辣，下次规划会自动适配。</p>
      <div class="memory-grid">
        <span v-for="item in memoryTags" :key="item">{{ item }}</span>
      </div>
    </section>

    <footer v-if="showComposer" class="composer glass">
      <span class="assistant-dot brand-orbit"></span>
      <label class="command-input">
        <input
          v-model="message"
          placeholder="输入你的出行需求，比如‘今天晚上静安寺，4 个朋友，预算 800’"
          @keydown.enter="plan"
        />
      </label>
      <button
        type="button"
        :class="{ active: voiceRecording || voiceTranscribing }"
        :disabled="voiceTranscribing"
        :title="voiceHint"
        @click="toggleVoice"
      >
        {{ voiceButtonText }}
      </button>
      <button class="primary-button" type="button" :disabled="!message.trim() || loading" @click="plan">
        {{ loading ? '规划中' : '规划' }}
      </button>
    </footer>
  </main>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { confirmPlan, createPlan, createSession, createShare, getGuardStatus, getMemory, getSessionToken, submitCollabComment, transcribeAudio, voteShare } from './api.js'
import TripMap from './components/TripMap.vue'

const token = ref(localStorage.getItem('lla_token') || '')
const message = ref('')
const loading = ref(false)
const voiceRecording = ref(false)
const voiceTranscribing = ref(false)
const voiceError = ref('')
const mediaRecorder = ref(null)
const audioChunks = ref([])
const recordingTimer = ref(null)
const activeView = ref('chat')
const currentStep = ref('need')
const messages = ref([])
const currentPlanId = ref('')
const shownPlans = ref([])
const activeMapRank = ref(1)
const mapOrigin = ref({})
const clarification = ref({})
const clarificationAnswers = ref({})
const completedClarificationAnswers = ref({})
const selectedClarificationPreset = ref('')
const expandedRank = ref(null)
const selectedRank = ref(null)
const shareId = ref('')
const votedRank = ref(null)
const collabComment = ref('')
const comments = ref([{ id: 1, name: '老婆', text: '方案 1 不错，吃饭时间能不能晚半小时？' }])
const executionSteps = ref([
  { name: '正在买童梦亲子乐园门票', status: 'doing' },
  { name: '正在订低卡餐厅座位', status: 'waiting' },
  { name: '正在安排孩子小礼物配送', status: 'waiting' }
])
const memoryTags = ref(['老婆减肥', '孩子要亲子设施', '朋友不吃辣', '周末不跑远'])

const hasFinalPlans = computed(() => shownPlans.value.length > 0)
const showPlanWorkspace = computed(() => activeView.value === 'chat' && hasFinalPlans.value && currentStep.value === 'plans')
const showComposer = computed(() => activeView.value !== 'chat' || currentStep.value !== 'plans' || !hasFinalPlans.value)
const voiceButtonText = computed(() => {
  if (voiceTranscribing.value) return '识别中'
  return voiceRecording.value ? '停止' : '语音'
})
const voiceHint = computed(() => voiceError.value || (voiceRecording.value ? '点击停止录音' : '点击开始语音输入'))
const stepCards = computed(() => {
  const userText = latestUserText()
  const answerSource = hasFinalPlans.value ? completedClarificationAnswers.value : clarificationAnswers.value
  const clarificationText = Object.entries(answerSource)
    .filter(([, value]) => value)
    .map(([key, value]) => `${fieldLabel(key)} ${value}`)
    .join(' · ')
  return [
    {
      key: 'need',
      index: 1,
      title: '出行需求',
      summary: userText || '一句话告诉我需求',
      status: currentStep.value === 'need' ? 'current' : userText ? 'done' : 'pending'
    },
    {
      key: 'clarify',
      index: 2,
      title: '信息补齐',
      summary: clarificationText || (hasFinalPlans.value ? '已补齐关键信息' : '等待补齐'),
      status: currentStep.value === 'clarify' ? 'current' : hasFinalPlans.value || clarificationText ? 'done' : 'pending'
    },
    {
      key: 'plans',
      index: 3,
      title: '方案地图',
      summary: hasFinalPlans.value ? `${shownPlans.value.length} 套方案已生成` : '生成后展开查看',
      status: currentStep.value === 'plans' && hasFinalPlans.value ? 'current' : hasFinalPlans.value ? 'done' : 'pending'
    }
  ]
})

const quickPrompts = [
  { label: '家庭周末游', text: '今天下午有空，带老婆孩子出去玩，别离家太远，老婆最近在减肥。' },
  { label: '朋友 4 人小聚', text: '今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。' },
  { label: '情侣约会', text: '周六晚上想和对象在附近约会，预算 600，想要安静一点、有氛围感。' },
  { label: 'Citywalk 逛吃', text: '明天下午想在附近 Citywalk，边逛边吃，路线轻松一点。' }
]

const stars = Array.from({ length: 72 }, (_, index) => ({
  id: index,
  style: {
    left: `${(index * 37) % 100}%`,
    top: `${(index * 61) % 100}%`,
    width: `${index % 5 === 0 ? 3 : 2}px`,
    height: `${index % 5 === 0 ? 3 : 2}px`,
    animationDelay: `${(index % 12) * .35}s`,
    opacity: .22 + (index % 7) * .07
  }
}))

const todayText = computed(() => {
  const now = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  return `${String(now.getMonth() + 1).padStart(2, '0')}.${String(now.getDate()).padStart(2, '0')} ${week} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
})

function nowText() {
  const now = new Date()
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
}

function latestUserText() {
  return messages.value.findLast(item => item.role === 'user')?.text || message.value || ''
}

async function ensureSession() {
  const existingToken = getSessionToken()
  if (existingToken) {
    token.value = existingToken
    return
  }
  const data = await createSession('立刻游用户')
  token.value = data.token
}

function useSample(text) {
  message.value = text
  plan()
}

async function plan() {
  const text = message.value.trim()
  if (!text || loading.value) return
  loading.value = true
  activeView.value = 'chat'
  currentStep.value = 'need'
  shownPlans.value = []
  completedClarificationAnswers.value = {}
  messages.value.push({ id: crypto.randomUUID(), role: 'user', text, time: nowText() })
  const loadingId = crypto.randomUUID()
  messages.value.push({ id: loadingId, role: 'assistant', loading: true, time: nowText() })
  try {
    await ensureSession()
    const planningMessage = await enrichMessageWithCurrentLocation(text)
    const data = await createPlan({ message: planningMessage, planCount: 3, stopCountPreference: '标准' })
    currentPlanId.value = data.planId || ''
    shownPlans.value = normalizePlans(data.options || [])
    syncMapState(data, shownPlans.value)
    clarification.value = data.clarification || {}
    selectedClarificationPreset.value = ''
    if (!shownPlans.value.length) {
      completedClarificationAnswers.value = {}
      currentStep.value = clarification.value?.fields?.length ? 'clarify' : 'need'
    } else {
      currentStep.value = 'plans'
    }
    replaceLoading(loadingId, {
      role: 'assistant',
      text: shownPlans.value.length ? '方案已生成，下面展开查看地图和路线。' : '',
      clarification: clarification.value,
      time: nowText()
    })
    message.value = ''
  } catch (err) {
    replaceLoading(loadingId, {
      role: 'assistant',
      text: friendlyError(err),
      time: nowText()
    })
  } finally {
    loading.value = false
    nextTick(scrollToBottom)
  }
}

async function submitClarification() {
  loading.value = true
  const loadingId = crypto.randomUUID()
  messages.value.push({ id: loadingId, role: 'assistant', loading: true, time: nowText() })
  try {
    await ensureSession()
    const answers = await buildClarificationAnswers()
    const data = await createPlan({
      message: messages.value.findLast(item => item.role === 'user')?.text || message.value,
      planCount: 3,
      stopCountPreference: '标准',
      clarificationAnswers: answers,
      previousPlanId: currentPlanId.value || null
    })
    currentPlanId.value = data.planId || ''
    shownPlans.value = normalizePlans(data.options || [])
    syncMapState(data, shownPlans.value)
    clarification.value = shownPlans.value.length ? {} : (data.clarification || {})
    const hasMoreClarification = !!clarification.value?.fields?.length
    if (shownPlans.value.length) {
      completedClarificationAnswers.value = answers
      clarificationAnswers.value = {}
      selectedClarificationPreset.value = ''
      currentStep.value = 'plans'
    } else if (hasMoreClarification) {
      selectedClarificationPreset.value = ''
      currentStep.value = 'clarify'
    }
    replaceLoading(loadingId, {
      role: 'assistant',
      text: shownPlans.value.length
        ? `信息补齐了，已生成 ${shownPlans.value.length} 套方案，下面展开查看地图和路线。`
        : hasMoreClarification
          ? ''
          : '信息已收到，但暂时没有生成可展示方案，请换一个更具体的地点或放宽范围后再试。',
      clarification: clarification.value,
      time: nowText()
    })
  } catch (err) {
    replaceLoading(loadingId, { role: 'assistant', text: friendlyError(err), time: nowText() })
  } finally {
    loading.value = false
  }
}

function orderedClarificationFields(fields = []) {
  const order = ['location', 'duration', 'group', 'budget', 'timeWindow', 'preferences']
  return [...fields].sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
}

function clarificationPlaceholder(field) {
  if (field?.key === 'location') return '例如：杭州西湖附近；或：当前位置'
  return field?.expectedAnswerHint || field?.question || '请输入'
}

function clarificationPresets(item) {
  const fields = item?.clarification?.fields || []
  const text = latestUserText()
  const scenario = inferPresetScenario(text)
  const presets = presetTemplates(scenario)
  return presets.map(preset => {
    const values = normalizePresetValues(preset.values)
    return {
      ...preset,
      values,
      fieldKeys: fields.map(field => field.key),
      summary: presetSummary(values, fields)
    }
  })
}

function inferPresetScenario(text) {
  if (/孩子|小孩|亲子|老婆|老公|家庭|宝宝/.test(text)) return 'family'
  if (/朋友|聚会|同学|同事|轰趴|烤肉|烧烤|电影/.test(text)) return 'friends'
  if (/情侣|约会|对象|Citywalk|citywalk|逛吃|散步/.test(text)) return 'citywalk'
  return 'nearby'
}

function presetTemplates(scenario) {
  const templates = {
    family: [
      { code: 'A', title: '家庭周末游', values: { location: '当前位置', duration: '4小时', group: '2大1小', budget: '400元', preferences: '亲子活动和轻松晚餐' } },
      { code: 'B', title: '亲子室内游', values: { location: '当前位置', duration: '3小时', group: '2大1小', budget: '600元', preferences: '儿童友好室内活动和简餐' } },
      { code: 'C', title: '低步行家庭游', values: { location: '当前位置', duration: '3小时', group: '一家三口', budget: '500元', preferences: '少走路、可休息、清淡餐厅' } }
    ],
    friends: [
      { code: 'A', title: '朋友4人小聚', values: { location: '当前位置', duration: '3小时', group: '4个朋友', budget: '800元', preferences: '娱乐活动、烤肉和电影' } },
      { code: 'B', title: '轻松电影局', values: { location: '当前位置', duration: '3小时', group: '2个朋友', budget: '500元', preferences: '看电影、吃饭、路线顺路' } },
      { code: 'C', title: '聚会逛吃局', values: { location: '当前位置', duration: '4小时', group: '4个朋友', budget: '1000元', preferences: '聚会、烧烤、少折腾' } }
    ],
    citywalk: [
      { code: 'A', title: '情侣Citywalk', values: { location: '当前位置', duration: '3小时', group: '情侣两人', budget: '500元', preferences: 'Citywalk、咖啡、安静晚餐' } },
      { code: 'B', title: '轻松逛吃', values: { location: '当前位置', duration: '4小时', group: '两个人', budget: '600元', preferences: '边逛边吃、少走回头路' } },
      { code: 'C', title: '展览散步', values: { location: '当前位置', duration: '3小时', group: '情侣两人', budget: '700元', preferences: '展览、散步、有氛围感' } }
    ],
    nearby: [
      { code: 'A', title: '附近轻松游', values: { location: '当前位置', duration: '3小时', group: '我自己', budget: '300元', preferences: '轻松逛逛和吃饭' } },
      { code: 'B', title: '本地半日游', values: { location: '当前位置', duration: '4小时', group: '两个人', budget: '500元', preferences: '活动、简餐、路线顺路' } },
      { code: 'C', title: '省心短途游', values: { location: '当前位置', duration: '2小时', group: '我自己', budget: '200元', preferences: '附近可玩、少走路' } }
    ]
  }
  return templates[scenario] || templates.nearby
}

function normalizePresetValues(values) {
  return {
    location: values.location,
    duration: values.duration,
    group: values.group,
    budget: values.budget,
    timeWindow: values.timeWindow,
    preferences: values.preferences
  }
}

function presetSummary(values, fields = []) {
  const availableKeys = new Set(fields.map(field => field.key))
  const get = key => availableKeys.size && !availableKeys.has(key) ? '' : values[key]
  return [get('location'), get('duration'), get('group'), get('budget')]
    .filter(Boolean)
    .join(' · ')
}

function applyClarificationPreset(preset) {
  const next = { ...clarificationAnswers.value }
  const allowedKeys = new Set(preset.fieldKeys || Object.keys(preset.values))
  for (const [key, value] of Object.entries(preset.values)) {
    if (value && allowedKeys.has(key)) next[key] = value
  }
  clarificationAnswers.value = next
  selectedClarificationPreset.value = preset.code
}

function handleClarificationInput() {
  selectedClarificationPreset.value = ''
}

async function buildClarificationAnswers() {
  const answers = { ...clarificationAnswers.value }
  if (isCurrentLocationAnswer(answers.location)) {
    const position = await getBrowserPosition()
    if (position) {
      answers.location = `当前位置 ${position.lng.toFixed(6)},${position.lat.toFixed(6)}`
      clarificationAnswers.value = { ...clarificationAnswers.value, location: answers.location }
    }
  }
  return answers
}

async function enrichMessageWithCurrentLocation(text) {
  if (!mentionsCurrentLocation(text) || hasCoordinates(text)) return text
  const position = await getBrowserPosition()
  if (!position) return text
  return `${text} 当前位置 ${position.lng.toFixed(6)},${position.lat.toFixed(6)}`
}

function isCurrentLocationAnswer(value) {
  const text = String(value || '').trim()
  return ['附近', '我附近', '在我附近', '当前地点', '当前位置', '本地'].includes(text)
}

function mentionsCurrentLocation(value) {
  const text = String(value || '')
  return ['我附近', '在我附近', '当前位置', '当前地点', '本地', '附近'].some(keyword => text.includes(keyword))
}

function hasCoordinates(value) {
  return /-?\d{2,3}\.\d{3,}\s*[,，\s]\s*-?\d{1,2}\.\d{3,}/.test(String(value || ''))
}

function getBrowserPosition() {
  if (!navigator.geolocation) return Promise.resolve(null)
  return new Promise(resolve => {
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        lng: position.coords.longitude,
        lat: position.coords.latitude
      }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 1500, maximumAge: 300000 }
    )
  })
}

function replaceLoading(id, next) {
  const index = messages.value.findIndex(item => item.id === id)
  if (index >= 0) messages.value[index] = { id, ...next }
}

function syncMapState(data, plans) {
  mapOrigin.value = data?.intent?.location || {}
  if (!plans.some(plan => plan.rank === activeMapRank.value)) {
    activeMapRank.value = plans[0]?.rank || 1
  }
}

function viewPlanOnMap(rank) {
  activeMapRank.value = rank
}

function goToStep(key) {
  if (key === 'plans' && !hasFinalPlans.value) return
  currentStep.value = key
  activeView.value = 'chat'
  if (key === 'need') {
    nextTick(scrollToBottom)
    return
  }
  if (key === 'clarify') {
    const lastClarification = [...messages.value].reverse().find(item => item.clarification?.fields?.length)
    if (lastClarification) {
      messages.value = messages.value.filter(item => item.role === 'user' || item.id === lastClarification.id || item.loading)
    }
    nextTick(scrollToBottom)
  }
}

function fieldLabel(key) {
  return {
    location: '地点',
    duration: '时长',
    group: '同行人',
    budget: '预算',
    timeWindow: '时间',
    preferences: '需求'
  }[key] || key
}

function normalizePlans(options) {
  return options.map((option, index) => ({
    ...option,
    rank: option.rank || index + 1,
    name: option.name || `方案 ${index + 1}`,
    tag: tagFor(option, index)
  }))
}

function tagFor(option, index) {
  const text = JSON.stringify(option)
  if (text.includes('低卡') || text.includes('减肥') || text.includes('轻食')) return '适配减肥'
  if (text.includes('亲子') || text.includes('孩子')) return '亲子友好'
  return ['路线顺路', '预算友好', '少折腾'][index] || '省心'
}

function compactTimeline(timeline = []) {
  if (!timeline.length) return '14:00 出发 → 15:00 周边活动 → 18:00 晚餐'
  return timeline.slice(0, 4).map((stop, index) => {
    if (index === 0) return `${stop.time || '现在'} 出发`
    return `${stop.time || ''} ${simplifyPoiName(stop.name)}`.trim()
  }).join(' → ')
}

function simplifyPoiName(name) {
  const raw = String(name || '周边地点').trim()
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

function planAdvantages(plan) {
  const timeline = Array.isArray(plan?.timeline) ? plan.timeline : []
  const hasDining = timeline.some(stop => stop.type === '餐饮')
  const activityNames = timeline.filter(stop => stop.type !== '餐饮').map(stop => simplifyPoiName(stop.name)).filter(Boolean)
  const route = plan?.route || {}
  const distance = route.distanceKm ? `全程约 ${route.distanceKm}km` : '路线距离已控制'
  const minutes = route.travelMinutes ? `路上约 ${route.travelMinutes} 分钟` : '路程耗时较短'
  const firstActivity = activityNames[0] || '核心活动'
  return [
    `${firstActivity} 和${hasDining ? '餐饮' : '休息点'}顺路安排，减少来回折返。`,
    `${distance}，${minutes}，适合本地短时出行。`,
    `预算和节奏更稳，适合直接选中后分享给同行人确认。`
  ]
}

function formatHours(minutes) {
  if (!minutes) return '约 3 小时'
  return `${Math.round(minutes / 6) / 10} 小时`
}

function formatMoney(value) {
  if (!value) return '¥待定'
  return `¥${value}`
}

async function selectPlan(rank) {
  selectedRank.value = rank
  if (currentPlanId.value) {
    try {
      await confirmPlan(currentPlanId.value, rank)
      executionSteps.value = [
        { name: '乐园门票已锁定', status: 'done' },
        { name: '低卡餐厅剩余 4 座，已预约', status: 'done' },
        { name: '孩子小礼物已安排配送到乐园', status: 'done' }
      ]
    } catch {
      executionSteps.value = [
        { name: '餐厅满位，已为你更换同评分低卡餐厅', status: 'done' },
        { name: '门票和配送继续执行', status: 'done' }
      ]
    }
  }
  activeView.value = 'execute'
}

async function vote(rank) {
  votedRank.value = rank
  if (shareId.value) await voteShare(shareId.value, { rank, voter: '同行人' }).catch(() => {})
}

function voteCount(rank) {
  return rank === votedRank.value ? 2 : rank === 1 ? 1 : 0
}

async function submitComment() {
  const text = collabComment.value.trim()
  if (!text) return
  comments.value.push({ id: Date.now(), name: '同行人', text })
  if (shareId.value) await submitCollabComment(shareId.value, { author: '同行人', text }).catch(() => {})
  collabComment.value = ''
}

async function createShareLink() {
  if (!currentPlanId.value) return
  const data = await createShare({ planId: currentPlanId.value, selectedRank: selectedRank.value || 1 }).catch(() => null)
  shareId.value = data?.shareId || 'mock-share'
  activeView.value = 'collab'
}

async function toggleVoice() {
  if (voiceTranscribing.value) return
  if (voiceRecording.value) {
    stopVoiceRecording()
    return
  }
  await startVoiceRecording()
}

async function startVoiceRecording() {
  voiceError.value = ''
  if (!navigator.mediaDevices?.getUserMedia) {
    voiceError.value = '当前浏览器不支持录音'
    return
  }
  try {
    await ensureSession()
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const recorder = new MediaRecorder(stream)
    audioChunks.value = []
    recorder.ondataavailable = event => {
      if (event.data?.size) audioChunks.value.push(event.data)
    }
    recorder.onstop = async () => {
      stream.getTracks().forEach(track => track.stop())
      await submitVoiceRecording()
    }
    mediaRecorder.value = recorder
    recorder.start()
    voiceRecording.value = true
    recordingTimer.value = window.setTimeout(() => stopVoiceRecording(), 30000)
  } catch (err) {
    voiceError.value = err?.name === 'NotAllowedError' ? '麦克风权限被拒绝' : '无法启动录音'
  }
}

function stopVoiceRecording() {
  if (recordingTimer.value) {
    window.clearTimeout(recordingTimer.value)
    recordingTimer.value = null
  }
  const recorder = mediaRecorder.value
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  voiceRecording.value = false
}

async function submitVoiceRecording() {
  if (!audioChunks.value.length) return
  voiceTranscribing.value = true
  voiceError.value = ''
  try {
    const rawBlob = new Blob(audioChunks.value, { type: audioChunks.value[0]?.type || 'audio/webm' })
    const wavBlob = await convertBlobToWav(rawBlob)
    const file = new File([wavBlob], `voice-${Date.now()}.wav`, { type: 'audio/wav' })
    const result = await transcribeAudio(file)
    if (result?.text) {
      message.value = message.value ? `${message.value}${result.text}` : result.text
    } else {
      voiceError.value = '没有识别到文字'
    }
  } catch (err) {
    voiceError.value = err?.payload?.error || err?.message || '语音识别失败'
  } finally {
    voiceTranscribing.value = false
    audioChunks.value = []
    mediaRecorder.value = null
  }
}

async function convertBlobToWav(blob) {
  const audioContext = new AudioContext()
  try {
    const arrayBuffer = await blob.arrayBuffer()
    const decoded = await audioContext.decodeAudioData(arrayBuffer)
    const wavBuffer = audioBufferToWav(resampleToMono(decoded, 16000), 16000)
    return new Blob([wavBuffer], { type: 'audio/wav' })
  } finally {
    audioContext.close?.()
  }
}

function resampleToMono(buffer, targetRate) {
  const source = buffer.getChannelData(0)
  const ratio = buffer.sampleRate / targetRate
  const length = Math.max(1, Math.round(source.length / ratio))
  const output = new Float32Array(length)
  for (let i = 0; i < length; i++) {
    const index = Math.min(source.length - 1, Math.floor(i * ratio))
    output[i] = source[index]
  }
  return output
}

function audioBufferToWav(samples, sampleRate) {
  const bytesPerSample = 2
  const blockAlign = bytesPerSample
  const buffer = new ArrayBuffer(44 + samples.length * bytesPerSample)
  const view = new DataView(buffer)
  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + samples.length * bytesPerSample, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * blockAlign, true)
  view.setUint16(32, blockAlign, true)
  view.setUint16(34, 16, true)
  writeAscii(view, 36, 'data')
  view.setUint32(40, samples.length * bytesPerSample, true)
  let offset = 44
  for (let i = 0; i < samples.length; i++, offset += 2) {
    const sample = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
  }
  return buffer
}

function writeAscii(view, offset, text) {
  for (let i = 0; i < text.length; i++) {
    view.setUint8(offset + i, text.charCodeAt(i))
  }
}

function copyShareMessage() {
  const text = '搞定啦，下午按方案出发，门票、餐厅位和配送我都安排好了。'
  navigator.clipboard?.writeText(text)
}

function scrollToBottom() {
  document.querySelector('.message-list')?.scrollTo({ top: 99999, behavior: 'smooth' })
}

function friendlyError(err) {
  if (err?.status === 422) return '抱歉，暂时没有找到完全符合的方案，要不要扩大到 5km 内，或者放宽一点需求？'
  return '抱歉，刚刚网络有点小问题，要不要再试一次？'
}

getMemory().then(data => {
  if (Array.isArray(data?.tags)) memoryTags.value = data.tags
}).catch(() => {})

getGuardStatus().then(data => {
  if (Array.isArray(data?.steps)) executionSteps.value = data.steps
}).catch(() => {})
</script>

<style scoped>
.chat-app {
  min-height: 100vh;
  min-height: 100svh;
  position: relative;
  overflow: hidden;
  color: #1d2436;
  background:
    radial-gradient(circle at 18% 24%, rgba(255, 125, 0, .14), transparent 22%),
    radial-gradient(circle at 78% 18%, rgba(22, 93, 255, .14), transparent 24%),
    linear-gradient(135deg, #f8fbff 0%, #eef5ff 52%, #fff8f0 100%);
  padding: 80px 24px 104px;
  font-size: 16px;
}

.chat-app:has(.result-mode) {
  padding-bottom: 32px;
}

.sky {
  position: fixed;
  inset: 0;
  pointer-events: none;
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
  background: rgba(255,255,255,.72);
  box-shadow: 0 14px 32px rgba(91, 106, 150, .13), inset 0 1px 0 rgba(255,255,255,.8);
  backdrop-filter: blur(22px);
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
  opacity: .45;
  cursor: not-allowed;
}

input {
  border: 0;
  outline: 0;
  background: transparent;
  color: #1d2436;
}

.topbar {
  position: fixed;
  z-index: 20;
  inset: 0 0 auto;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 max(24px, calc((100vw - 800px) / 2));
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  background: transparent;
  color: #111827;
  text-align: left;
}

.brand strong {
  display: block;
  font-size: 18px;
  font-weight: 800;
}

.brand small {
  display: none;
  color: #718096;
  font-size: 14px;
}

.brand-orbit {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff7d00, #165dff);
  box-shadow: 0 0 0 6px rgba(255,255,255,.28), 0 0 18px rgba(22,93,255,.28);
  flex-shrink: 0;
}

.meta {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #718096;
  font-size: 14px;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #fff;
  position: relative;
}

.avatar span,
.avatar i {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  background: #165dff;
  border-radius: 999px;
}

.avatar span {
  top: 7px;
  width: 9px;
  height: 9px;
}

.avatar i {
  bottom: 6px;
  width: 17px;
  height: 9px;
}

.chat-shell,
.page-panel {
  position: relative;
  z-index: 2;
  width: min(800px, 100%);
  margin: 0 auto;
}

.chat-shell.result-mode {
  width: min(1000px, 100%);
  animation: workspace-expand .36s cubic-bezier(.2, .86, .28, 1);
}

.empty-state {
  min-height: calc(100svh - 200px);
  display: grid;
  place-items: center;
  align-content: center;
  gap: 14px;
  text-align: center;
}

.empty-logo {
  width: 64px;
  height: 64px;
  animation: float 3.8s ease-in-out infinite;
}

.empty-state h1 {
  margin: 0;
  font-size: 18px;
}

.empty-state p {
  max-width: 520px;
  margin: 0;
  color: #64748b;
  font-size: 16px;
  line-height: 1.7;
}

.quick-prompts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 8px;
  width: min(520px, 100%);
}

.quick-prompts button,
.bubble footer button,
.comment-box button,
.pick-button {
  min-height: 48px;
  border-radius: 8px;
  background: #eef3f8;
  color: #334155;
  font-weight: 700;
}

.message-list {
  height: calc(100svh - 190px);
  overflow: auto;
  list-style: none;
  margin: 0;
  padding: 16px 0 24px;
  display: grid;
  gap: 16px;
}

.step-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: stretch;
  margin-bottom: 16px;
  animation: result-rise .32s ease both;
}

.step-card-list {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.step-card {
  min-width: 0;
  min-height: 76px;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  grid-template-rows: auto auto;
  gap: 4px 10px;
  align-items: center;
  border-radius: 12px;
  padding: 12px;
  background: rgba(255,255,255,.82);
  color: #1d2436;
  text-align: left;
  box-shadow: 0 10px 22px rgba(91, 106, 150, .10);
  border: 1px solid rgba(226, 232, 240, .78);
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
}

.step-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 14px 28px rgba(91, 106, 150, .14);
}

.step-card span {
  grid-row: 1 / 3;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #eef3f8;
  color: #64748b;
  font-size: 14px;
  font-weight: 900;
}

.step-card strong {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-card small {
  min-width: 0;
  overflow: hidden;
  color: #64748b;
  font-size: 13px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-card.done span {
  background: rgba(22, 93, 255, .10);
  color: #165dff;
}

.step-card.current {
  border-color: rgba(22, 93, 255, .32);
  box-shadow: 0 14px 30px rgba(22, 93, 255, .12);
}

.step-card.current span {
  background: #165dff;
  color: #fff;
}

.step-card.pending {
  opacity: .72;
}

.summary-share {
  min-width: 128px;
  min-height: 76px;
  border-radius: 12px;
  padding: 0 18px;
  background: #165dff;
  color: #fff;
  font-size: 15px;
  font-weight: 900;
  box-shadow: 0 14px 30px rgba(22, 93, 255, .16);
  transition: transform .2s ease, box-shadow .2s ease;
}

.summary-share:hover {
  transform: translateY(-1px);
  box-shadow: 0 18px 36px rgba(22, 93, 255, .2);
}

.result-workspace {
  display: grid;
  grid-template-columns: 380px minmax(0, 580px);
  gap: 24px;
  align-items: start;
  animation: result-rise .42s cubic-bezier(.2, .86, .28, 1) both;
}

.result-plans {
  max-height: 580px;
  overflow: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
  display: grid;
  gap: 14px;
}

.result-plans::-webkit-scrollbar {
  width: 8px;
}

.result-plans::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(148, 163, 184, .45);
}

.result-plans .plan-card {
  min-height: 168px;
  padding: 18px;
  border-width: 2px;
}

.result-plans .plan-card strong {
  font-size: 20px;
}

.result-plans .timeline-line {
  font-size: 17px !important;
  line-height: 1.55;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-plans .plan-meta {
  flex-wrap: wrap;
  gap: 10px 16px;
  font-size: 15px;
}

.result-plans .plan-card footer button {
  min-height: 48px;
  border-radius: 10px;
  padding: 0 16px;
  font-size: 15px;
}

.result-plans .plan-card.active {
  border-color: #165dff;
  box-shadow: 0 16px 34px rgba(22, 93, 255, .18);
}

.result-map {
  min-width: 0;
}

.result-map :deep(.trip-map-panel) {
  margin-top: 0;
}

.result-map :deep(.map-body) {
  height: 580px;
}

.message-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.message-row.user {
  justify-content: flex-end;
}

.bubble-avatar {
  width: 32px;
  height: 32px;
  margin-top: 4px;
}

.bubble {
  max-width: min(720px, calc(100% - 42px));
  border-radius: 8px;
  padding: 16px;
  background: rgba(255,255,255,.92);
  box-shadow: 0 10px 22px rgba(91, 106, 150, .10);
}

.message-row.user .bubble {
  max-width: min(560px, calc(100% - 42px));
  background: #fff;
}

.bubble time {
  float: right;
  margin-left: 10px;
  color: #94a3b8;
  font-size: 14px;
}

.bubble p {
  margin: 0;
  font-size: 16px;
  line-height: 1.65;
}

.typing {
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 5px;
}

.typing span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #165dff;
  animation: blink 1s ease-in-out infinite;
}

.typing span:nth-child(2) { animation-delay: .16s; }
.typing span:nth-child(3) { animation-delay: .32s; }

.plan-stack {
  display: grid;
  gap: 12px;
  width: 100%;
}

.plan-card {
  border-radius: 12px;
  padding: 16px;
  background: #fff;
  box-shadow: 0 10px 24px rgba(91, 106, 150, .10);
  border: 1px solid transparent;
  transition: border-color .2s ease, box-shadow .2s ease, transform .2s ease;
  cursor: pointer;
}

.plan-card.active {
  border-color: rgba(22, 93, 255, .38);
  box-shadow: 0 14px 30px rgba(22, 93, 255, .14);
  transform: translateY(-1px);
}

.plan-card header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.plan-card strong {
  font-size: 18px;
}

.plan-card header span {
  border-radius: 8px;
  padding: 4px 8px;
  background: #16a34a;
  color: #fff;
  font-size: 14px;
  white-space: nowrap;
}

.timeline-line {
  margin: 12px 0 !important;
  color: #334155;
  font-size: 16px !important;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-meta {
  display: flex;
  gap: 14px;
  color: #64748b;
  font-size: 14px;
}

.plan-meta span:first-child {
  color: #ff7d00;
  font-weight: 800;
}

.plan-card footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.pick-button,
.primary-button {
  background: #165dff !important;
  color: #fff !important;
}

.plan-detail {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #eef2f7;
  color: #64748b;
}

.plan-detail p {
  font-size: 14px;
}

.clarify-card {
  display: grid;
  gap: 16px;
  width: min(720px, 100%);
}

.clarify-head strong {
  display: block;
  font-size: 18px;
  line-height: 1.5;
}

.clarify-layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr);
  gap: 16px;
  align-items: stretch;
}

.clarify-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.clarify-card label {
  display: grid;
  gap: 6px;
}

.clarify-card label span {
  color: #64748b;
  font-size: 14px;
}

.clarify-card input,
.comment-box input,
.command-input input {
  width: 100%;
}

.clarify-card input {
  min-height: 48px;
  border-radius: 12px;
  padding: 0 12px;
  background: #fff;
  box-shadow: inset 0 0 0 1px rgba(226, 232, 240, .96);
  transition: box-shadow .2s ease, background-color .2s ease, transform .2s ease;
}

.clarify-card input:focus {
  box-shadow: inset 0 0 0 1px rgba(22, 93, 255, .46), 0 0 0 4px rgba(22, 93, 255, .10);
  background: #fff;
}

.clarify-submit {
  grid-column: 1 / -1;
  min-height: 48px;
  border-radius: 12px;
}

.clarify-presets {
  display: grid;
  gap: 8px;
  align-content: stretch;
}

.preset-card {
  position: relative;
  min-height: 80px;
  display: grid;
  align-content: center;
  gap: 8px;
  border-radius: 12px;
  padding: 12px 14px;
  background: #fff;
  color: #1d2436;
  text-align: left;
  box-shadow: 0 10px 22px rgba(91, 106, 150, .10);
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
  border: 1px solid rgba(226, 232, 240, .82);
}

.preset-card:hover {
  transform: scale(1.02);
  box-shadow: 0 16px 32px rgba(91, 106, 150, .16);
}

.preset-card.selected {
  border-color: rgba(22, 93, 255, .46);
  box-shadow: 0 16px 34px rgba(22, 93, 255, .15);
}

.preset-card strong {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  line-height: 1.2;
}

.preset-card strong b {
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 8px;
  background: rgba(22, 93, 255, .10);
  color: #165dff;
  font-size: 13px;
  font-weight: 900;
}

.preset-card small {
  color: #64748b;
  font-size: 14px;
  line-height: 1.35;
}

.preset-check {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #165dff;
  color: #fff;
  font-size: 12px;
  font-weight: 900;
  opacity: 0;
  transform: scale(.7);
  transition: opacity .18s ease, transform .18s ease;
}

.preset-card.selected .preset-check {
  opacity: 1;
  transform: scale(1);
}

.page-panel {
  margin-top: 24px;
  border-radius: 16px;
  padding: 18px;
  background: rgba(255,255,255,.72);
  box-shadow: 0 16px 36px rgba(91, 106, 150, .12);
}

.page-panel > header {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.page-panel h1 {
  margin: 0;
  font-size: 18px;
}

.page-panel p {
  margin: 4px 0 0;
  color: #64748b;
}

.shared-plans {
  display: grid;
  gap: 12px;
}

.comment-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 10px;
  margin-top: 14px;
}

.comment-box input {
  min-height: 42px;
  border-radius: 8px;
  padding: 0 12px;
  background: #fff;
}

.comments {
  list-style: none;
  margin: 14px 0 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.comments li {
  border-radius: 8px;
  padding: 10px;
  background: #fff;
}

.comments span {
  color: #165dff;
  font-weight: 800;
}

.execution-panel ol {
  list-style: none;
  margin: 18px 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.execution-panel li {
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}

.execution-panel li span {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  margin-right: 8px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #165dff;
  font-weight: 900;
}

.execution-panel li.done span {
  background: #dcfce7;
  color: #16a34a;
}

.memory-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.memory-grid span {
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff;
  color: #334155;
}

.composer {
  position: fixed;
  z-index: 20;
  left: 50%;
  bottom: 14px;
  width: min(800px, calc(100vw - 48px));
  min-height: 72px;
  transform: translateX(-50%);
  border-radius: 16px;
  padding: 12px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 48px 48px 72px;
  align-items: center;
  gap: 8px;
}

.assistant-dot {
  width: 24px;
  height: 24px;
}

.command-input {
  min-height: 48px;
  border-radius: 12px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  background: #fff;
}

.composer > button {
  min-height: 48px;
  border-radius: 12px;
  background: #eef3f8;
  color: #334155;
  font-size: 14px;
  font-weight: 800;
}

.composer > button.active {
  background: #fff1f2;
  color: #be123c;
  box-shadow: inset 0 0 0 1px rgba(225, 29, 72, .28);
}

@media (min-width: 768px) {
  .brand small {
    display: block;
  }
}

@media (max-width: 760px) {
  .chat-app {
    padding: 64px 12px 114px;
    font-size: 15px;
  }

  .chat-app:has(.result-mode) {
    padding-bottom: 24px;
  }

  .topbar {
    padding: 0 16px;
  }

  .chat-shell,
  .page-panel {
    width: min(640px, 100%);
  }

  .chat-shell.result-mode {
    width: min(640px, 100%);
  }

  .meta span {
    display: none;
  }

  .quick-prompts {
    grid-template-columns: 1fr;
  }

  .message-list {
    height: calc(100svh - 190px);
  }

  .result-workspace {
    grid-template-columns: 1fr;
  }

  .step-summary {
    grid-template-columns: 1fr;
  }

  .step-card-list {
    grid-template-columns: 1fr;
  }

  .step-card {
    min-height: 68px;
  }

  .summary-share {
    min-height: 48px;
  }

  .result-plans {
    max-height: none;
    overflow: visible;
    padding-right: 0;
  }

  .result-plans .plan-card {
    min-height: 0;
  }

  .result-map :deep(.map-body) {
    height: 300px;
  }

  .bubble {
    max-width: min(520px, calc(100% - 42px));
    padding: 12px;
  }

  .message-row.user .bubble {
    max-width: min(520px, calc(100% - 42px));
  }

  .clarify-layout,
  .clarify-form {
    grid-template-columns: 1fr;
  }

  .clarify-card {
    width: 100%;
  }

  .composer {
    width: min(640px, calc(100vw - 24px));
    grid-template-columns: 24px minmax(0, 1fr) 44px 44px 60px;
    grid-auto-rows: auto;
  }
}

@keyframes drift {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50% { transform: translate3d(8px, -10px, 0); }
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

@keyframes blink {
  0%, 100% { opacity: .25; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-2px); }
}

@keyframes workspace-expand {
  from {
    opacity: .86;
    transform: scale(.985);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes result-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
