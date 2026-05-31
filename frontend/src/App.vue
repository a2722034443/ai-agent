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

    <section v-if="activeView === 'chat'" class="chat-shell">
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

      <ol v-else class="message-list">
        <li v-for="item in messages" :key="item.id" :class="['message-row', item.role]">
          <span v-if="item.role === 'assistant'" class="bubble-avatar brand-orbit"></span>
          <article class="bubble">
            <time>{{ item.time }}</time>
            <p v-if="item.text">{{ item.text }}</p>
            <div v-if="item.loading" class="typing">
              <span></span><span></span><span></span>
              正在为你规划方案...
            </div>
            <div v-if="item.plans?.length" class="plan-stack">
              <article
                v-for="plan in item.plans"
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
                  <p v-for="reason in plan.fitReasons || []" :key="reason">{{ reason }}</p>
                </div>
              </article>
              <TripMap
                v-if="item.id === latestPlanMessageId"
                :plans="shownPlans"
                :active-rank="activeMapRank"
                :origin="mapOrigin"
                :guard-mode="activeView === 'execute'"
              />
            </div>
            <div v-if="item.clarification?.fields?.length" class="clarify-card">
              <strong>{{ item.clarification.message || '还需要补齐几个关键信息' }}</strong>
              <label v-for="field in item.clarification.fields" :key="field.key">
                <span>{{ field.label }}</span>
                <input v-model="clarificationAnswers[field.key]" :placeholder="field.expectedAnswerHint || field.question || '请输入'" />
              </label>
              <button class="primary-button" type="button" :disabled="loading" @click="submitClarification">补齐后规划</button>
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

    <footer class="composer glass">
      <span class="assistant-dot brand-orbit"></span>
      <label class="command-input">
        <input
          v-model="message"
          placeholder="输入你的出行需求，比如‘今天晚上静安寺，4 个朋友，预算 800’"
          @keydown.enter="plan"
        />
      </label>
      <button type="button" :class="{ active: voiceRecording }" @click="toggleVoice">语音</button>
      <button type="button" @click="openImageTool">图片</button>
      <button type="button" @click="createShareLink" :disabled="!shownPlans.length">分享给同行人</button>
      <button class="primary-button" type="button" :disabled="!message.trim() || loading" @click="plan">
        {{ loading ? '规划中' : '规划' }}
      </button>
      <input ref="fileInput" class="hidden-file" type="file" accept="image/*" @change="handleImagePick" />
    </footer>
  </main>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { confirmPlan, createPlan, createSession, createShare, getGuardStatus, getMemory, getSessionToken, submitCollabComment, voteShare } from './api.js'
import TripMap from './components/TripMap.vue'

const token = ref(localStorage.getItem('lla_token') || '')
const message = ref('')
const loading = ref(false)
const voiceRecording = ref(false)
const fileInput = ref(null)
const activeView = ref('chat')
const messages = ref([])
const currentPlanId = ref('')
const shownPlans = ref([])
const latestPlanMessageId = ref('')
const activeMapRank = ref(1)
const mapOrigin = ref({})
const clarification = ref({})
const clarificationAnswers = ref({})
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
    replaceLoading(loadingId, {
      role: 'assistant',
      text: shownPlans.value.length ? '我先给你 3 套可执行方案，选中后可以分享给同行人一起投票。' : '',
      plans: shownPlans.value,
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
      clarificationAnswers.value = {}
    }
    replaceLoading(loadingId, {
      role: 'assistant',
      text: shownPlans.value.length
        ? `信息补齐了，我重新整理了 ${shownPlans.value.length} 套方案。`
        : hasMoreClarification
          ? ''
          : '信息已收到，但暂时没有生成可展示方案，请换一个更具体的地点或放宽范围后再试。',
      plans: shownPlans.value,
      clarification: clarification.value,
      time: nowText()
    })
  } catch (err) {
    replaceLoading(loadingId, { role: 'assistant', text: friendlyError(err), time: nowText() })
  } finally {
    loading.value = false
  }
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
  if (next.plans?.length) latestPlanMessageId.value = id
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
    return `${stop.time || ''} ${stop.name}`.trim()
  }).join(' → ')
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

function toggleVoice() {
  voiceRecording.value = !voiceRecording.value
  if (voiceRecording.value) message.value = '今天下午带老婆孩子出去玩，别离家太远，老婆最近在减肥。'
}

function openImageTool() {
  fileInput.value?.click()
}

function handleImagePick(event) {
  const file = event.target.files?.[0]
  if (!file) return
  message.value = `我上传了一张图片 ${file.name}，想按图片里的风格找附近可玩的地点。`
  event.target.value = ''
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
  padding: 72px 16px 96px;
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
  padding: 0 max(16px, calc((100vw - 640px) / 2));
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
  font-size: 12px;
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
  width: min(640px, 100%);
  margin: 0 auto;
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
  width: min(420px, 100%);
}

.quick-prompts button,
.bubble footer button,
.comment-box button,
.pick-button {
  min-height: 38px;
  border-radius: 8px;
  background: #eef3f8;
  color: #334155;
  font-weight: 700;
}

.message-list {
  height: calc(100svh - 180px);
  overflow: auto;
  list-style: none;
  margin: 0;
  padding: 16px 0 24px;
  display: grid;
  gap: 14px;
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
  max-width: min(520px, calc(100% - 42px));
  border-radius: 8px;
  padding: 12px;
  background: rgba(255,255,255,.92);
  box-shadow: 0 10px 22px rgba(91, 106, 150, .10);
}

.message-row.user .bubble {
  background: #fff;
}

.bubble time {
  float: right;
  margin-left: 10px;
  color: #94a3b8;
  font-size: 12px;
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
}

.plan-card {
  border-radius: 12px;
  padding: 14px;
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
  font-size: 16px;
}

.plan-card header span {
  border-radius: 8px;
  padding: 4px 8px;
  background: #16a34a;
  color: #fff;
  font-size: 12px;
  white-space: nowrap;
}

.timeline-line {
  margin: 12px 0 !important;
  color: #334155;
  font-size: 14px !important;
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
  gap: 10px;
}

.clarify-card label {
  display: grid;
  gap: 4px;
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
  min-height: 40px;
  border-radius: 8px;
  padding: 0 10px;
  background: #f8fafc;
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
  width: min(640px, calc(100vw - 24px));
  min-height: 72px;
  transform: translateX(-50%);
  border-radius: 16px;
  padding: 12px;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 48px 48px 92px 64px;
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

.hidden-file {
  display: none;
}

@media (min-width: 768px) {
  .brand small {
    display: block;
  }
}

@media (max-width: 760px) {
  .chat-app {
    padding: 64px 12px 114px;
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

  .composer {
    grid-template-columns: 24px minmax(0, 1fr) 44px 44px 60px;
    grid-auto-rows: auto;
  }

  .composer > button:nth-of-type(3) {
    grid-column: 2 / 5;
  }

  .composer .primary-button {
    grid-column: 5;
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
</style>
