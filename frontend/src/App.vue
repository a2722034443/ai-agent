<template>
  <main class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">LL</span>
        <div>
          <strong>本地生活管家</strong>
          <p>真实地点 · 真实路线 · 先澄清再规划</p>
        </div>
      </div>

      <nav class="view-tabs" aria-label="工作区">
        <button :class="{ active: view === 'compose' }" @click="view = 'compose'">需求</button>
        <button :class="{ active: view === 'result' }" @click="view = 'result'" :disabled="!options.length">方案</button>
        <button :class="{ active: view === 'trace' }" @click="view = 'trace'" :disabled="!trace.length">证据</button>
      </nav>

      <div class="session-pill">
        <span :class="['status-dot', loading && 'busy']"></span>
        <strong>{{ loading ? loadingText : sessionText }}</strong>
      </div>
    </header>

    <section class="map-hero">
      <div class="hero-copy">
        <p class="eyebrow">AI LOCAL LIFE PLANNER</p>
        <h1>{{ pageTitle }}</h1>
        <p>{{ pageSubtitle }}</p>
      </div>
      <div class="map-board" aria-hidden="true">
        <div class="route-line"></div>
        <span class="pin pin-a">A</span>
        <span class="pin pin-b">B</span>
        <span class="pin pin-c">C</span>
        <div class="map-card">
          <strong>{{ options.length || clarificationFields.length || 0 }}</strong>
          <span>{{ options.length ? '套候选方案' : '个待补信息' }}</span>
        </div>
      </div>
    </section>

    <section v-if="view === 'compose'" class="compose-grid">
      <section class="panel request-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">输入 Brief</p>
            <h2>告诉我你想怎么过这段时间</h2>
          </div>
          <button class="secondary-button" @click="login" :disabled="loading">
            {{ token ? '刷新会话' : '创建会话' }}
          </button>
        </div>

        <div class="form-grid">
          <label>
            <span>称呼</span>
            <input v-model="nickname" placeholder="体验用户" />
          </label>
          <label>
            <span>方案数</span>
            <select v-model.number="planCount">
              <option v-for="count in [1, 2, 3, 4, 5]" :key="count" :value="count">{{ count }} 套</option>
            </select>
          </label>
          <label>
            <span>行程密度</span>
            <select v-model="stopCountPreference">
              <option value="标准">标准</option>
              <option value="简洁">简洁</option>
              <option value="丰富">丰富</option>
            </select>
          </label>
        </div>

        <label class="message-box">
          <span>自然语言需求</span>
          <textarea
            v-model="message"
            rows="8"
            placeholder="例如：今天晚上7点在上海静安寺附近，4个朋友，预算800元，想先去一个有意思的地方再吃饭，路线不要太折腾。"
          />
        </label>

        <div class="sample-strip">
          <button v-for="sample in samples" :key="sample.label" @click="message = sample.text">
            {{ sample.label }}
          </button>
        </div>

        <section v-if="clarificationFields.length" class="clarification-panel">
          <div class="section-head">
            <div>
              <p class="eyebrow">澄清卡片</p>
              <h2>{{ clarification.message || '还需要补齐关键信息' }}</h2>
            </div>
            <span>{{ clarificationFields.length }} 项</span>
          </div>

          <div class="clarification-list">
            <article v-for="field in clarificationFields" :key="field.key" class="clarification-card">
              <div class="field-title">
                <span>{{ fieldIndex(field) }}</span>
                <div>
                  <strong>{{ field.label }}</strong>
                  <p>{{ field.question }}</p>
                </div>
              </div>

              <div v-if="fieldChoices(field).length" class="choice-grid">
                <button
                  v-for="(choice, idx) in fieldChoices(field)"
                  :key="`${field.key}-${choice}`"
                  :class="{ selected: clarificationAnswers[field.key] === choice }"
                  @click="clarificationAnswers[field.key] = choice"
                >
                  <b>{{ choiceLabel(idx) }}</b>
                  <span>{{ choice }}</span>
                </button>
              </div>

              <p class="field-reason">{{ field.reason || field.expectedAnswerHint || '用自然语言填写即可。' }}</p>

              <div class="answer-row">
                <input v-model="clarificationAnswers[field.key]" :placeholder="customPlaceholder(field)" />
                <button
                  v-if="field.key === 'location'"
                  class="location-button"
                  type="button"
                  @click="useBrowserLocation"
                  :disabled="loading || locating"
                >
                  {{ locating ? '定位中' : '当前位置' }}
                </button>
              </div>
            </article>
          </div>

          <button class="primary-button wide" @click="submitClarification" :disabled="loading">补齐后生成方案</button>
        </section>

        <div class="action-bar">
          <button class="primary-button" @click="plan" :disabled="!canPlan">
            {{ loading ? '处理中' : '开始规划' }}
          </button>
          <span v-if="error" class="error-line">{{ error }}</span>
        </div>
      </section>

      <aside class="panel concierge-panel">
        <div class="panel-head compact">
          <div>
            <p class="eyebrow">管家检查</p>
            <h2>不会跳过的产品底线</h2>
          </div>
        </div>
        <ol class="quality-list">
          <li v-for="item in qualityItems" :key="item.title">
            <strong>{{ item.title }}</strong>
            <p>{{ item.text }}</p>
          </li>
        </ol>

        <div class="agent-flow">
          <p class="eyebrow">Agent 链路</p>
          <ol>
            <li v-for="(step, index) in agentSteps" :key="step.tool" :class="{ done: traceToolNames.has(step.tool), active: loading && index === activeAgentIndex }">
              <span>{{ index + 1 }}</span>
              <div>
                <strong>{{ step.name }}</strong>
                <p>{{ step.desc }}</p>
              </div>
            </li>
          </ol>
        </div>
      </aside>
    </section>

    <section v-if="view === 'result'" class="result-grid">
      <aside class="panel summary-panel">
        <div class="section-head stacked">
          <p class="eyebrow">需求摘要</p>
          <h2>当前规划条件</h2>
        </div>
        <dl class="intent-list">
          <div v-for="row in intentRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value }}</dd>
          </div>
        </dl>

        <div v-if="warnings.length" class="notice-box">
          <strong>风险提示</strong>
          <p v-for="warning in warnings" :key="warning">{{ warning }}</p>
        </div>

        <div class="weather-box">
          <span>天气</span>
          <strong>{{ weatherInfo?.weather || '暂不可用' }}</strong>
          <p>{{ weatherInfo?.temperature ? `${weatherInfo.temperature}℃` : '温度待确认' }}</p>
          <small>{{ weatherInfo?.suggestion || '建议出发前再次确认天气变化。' }}</small>
        </div>
      </aside>

      <section class="panel plans-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">{{ options.length }} 套真实候选</p>
            <h2>方案对比</h2>
          </div>
          <label class="feedback-box">
            <input v-model="feedback" placeholder="例如：预算太高、太远、换清淡餐厅" />
            <button class="secondary-button" @click="adjustPlan" :disabled="!feedback.trim() || loading">调整</button>
          </label>
        </div>

        <div class="plan-stack">
          <article v-for="option in options" :key="option.rank" :class="['plan-card', selectedRank === option.rank && 'selected']">
            <button class="rank-button" @click="selectedRank = option.rank">{{ option.rank }}</button>
            <div class="plan-main">
              <div class="plan-title">
                <div>
                  <h3>{{ option.name || `方案 ${option.rank}` }}</h3>
                  <p>{{ option.tagline || '真实可执行方案' }}</p>
                </div>
                <strong>{{ option.score ?? '-' }}</strong>
              </div>

              <div class="metric-row">
                <span>{{ formatHours(option.totalMinutes) }}</span>
                <span>{{ formatMoney(option.budgetEstimate) }}</span>
                <span>{{ option.route?.distanceKm ?? '-' }} 公里</span>
              </div>

              <ol class="timeline">
                <li v-for="item in option.timeline || []" :key="`${option.rank}-${item.time}-${item.name}`">
                  <time>{{ item.time || '--:--' }}</time>
                  <div>
                    <strong>{{ item.name }}</strong>
                    <p>{{ item.subtype || item.type || '地点' }} · {{ item.durationMinutes || '-' }} 分钟 · {{ item.address || '地址待确认' }}</p>
                  </div>
                </li>
              </ol>

              <div class="tag-row">
                <span v-for="reason in option.fitReasons || []" :key="reason">{{ reason }}</span>
              </div>
              <div class="tag-row warning">
                <span v-for="risk in option.riskNotes || []" :key="risk">{{ risk }}</span>
              </div>
            </div>
            <button class="primary-button confirm-button" @click="confirm(option.rank)" :disabled="loading">确认执行</button>
          </article>
        </div>

        <section v-if="execution" class="execution-panel">
          <h2>执行结果</h2>
          <div class="order-grid">
            <div v-for="order in execution.orders || []" :key="order.orderNo">
              <strong>{{ order.target }}</strong>
              <span>{{ order.action }} · {{ order.status }}</span>
              <code>{{ order.orderNo }}</code>
            </div>
          </div>
          <p>{{ execution.shareMessage }}</p>
        </section>
      </section>
    </section>

    <section v-if="view === 'trace'" class="trace-grid">
      <section class="panel trace-summary">
        <p class="eyebrow">证据链</p>
        <h2>每一步都可追溯</h2>
        <dl class="intent-list">
          <div v-for="row in intentRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>{{ row.value }}</dd>
          </div>
        </dl>
      </section>

      <section class="panel trace-panel">
        <div class="trace-head">
          <span>工具</span>
          <span>状态</span>
          <span>模式</span>
          <span>耗时</span>
          <span>摘要</span>
        </div>
        <div v-for="(item, idx) in trace" :key="idx" class="trace-row">
          <strong>{{ toolText(item.tool) }}</strong>
          <span :class="['status-text', item.status]">{{ statusText(item.status) }}</span>
          <span>{{ modeText(item.mode) }}</span>
          <span>{{ item.durationMs }} ms</span>
          <p>{{ summarizeTrace(item) }}</p>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { confirmPlan, createPlan, createSession, sendFeedback } from './api.js'

const nickname = ref('体验用户')
const token = ref(localStorage.getItem('lla_token') || '')
const message = ref('想在附近玩玩')
const feedback = ref('')
const planCount = ref(3)
const stopCountPreference = ref('标准')
const loading = ref(false)
const locating = ref(false)
const loadingText = ref('Agent 编排中')
const error = ref('')
const currentPlanId = ref('')
const options = ref([])
const trace = ref([])
const intent = ref({})
const execution = ref(null)
const clarification = ref({})
const clarificationAnswers = ref({})
const weatherInfo = ref(null)
const warnings = ref([])
const selectedRank = ref(1)
const view = ref('compose')

const samples = [
  { label: '附近随逛', text: '想在附近玩玩' },
  { label: '朋友聚会', text: '今天晚上7点在上海静安寺附近，4个朋友，预算800元，想先去一个有意思的地方再吃饭，路线不要太折腾。' },
  { label: '亲子半天', text: '明天下午2点在杭州西湖附近，两个大人一个6岁孩子，预算600元，想安排室内亲子活动和清淡晚餐，4小时左右。' },
  { label: '约会晚餐', text: '今晚7点在成都太古里附近，情侣两人，预算700元，想安静一点，少排队，最好有散步和晚餐。' }
]

const qualityItems = [
  { title: '先澄清', text: '地点、时间、时长、人群、预算、核心需求缺一项都不查真实地点。' },
  { title: '真实数据', text: '正常运行只接受真实 POI 和路线，天气/网页失败只做提示。' },
  { title: '管家思维', text: '儿童、老人、忌口、停车、地铁、排队、天气都会影响推荐。' },
  { title: '全国可用', text: '不会默认城市，也不会把“附近”当成真实地址。' }
]

const agentSteps = [
  { name: '需求澄清', desc: 'LLM 判断缺口，必要时返回卡片', tool: 'IntentParserAgent' },
  { name: '澄清卡片', desc: 'A/B/C 选项加自定义输入', tool: 'ClarificationAgent' },
  { name: '天气判断', desc: '可选天气建议，不编造数据', tool: 'AmapWeatherTool' },
  { name: '联网核验', desc: '辅助新鲜度和口碑证据', tool: 'WebSearchTool' },
  { name: '真实地点', desc: '高德 POI 候选检索', tool: 'AmapPoiSearchTool' },
  { name: '路线评估', desc: '高德路线和距离约束', tool: 'AmapRouteEstimateTool' },
  { name: '确认执行', desc: '订座、购票、分享模拟执行', tool: 'BookingTool' }
]

const canPlan = computed(() => token.value && message.value.trim() && !loading.value)
const clarificationFields = computed(() => clarification.value?.fields || [])
const traceToolNames = computed(() => new Set(trace.value.map(item => item.tool)))
const activeAgentIndex = computed(() => Math.min(trace.value.length, agentSteps.length - 1))
const sessionText = computed(() => token.value ? '会话已连接' : '等待创建会话')
const pageTitle = computed(() => {
  if (view.value === 'trace') return '像查账一样看清每次工具调用'
  if (view.value === 'result') return '比较真实候选，选一套能执行的方案'
  return '先把需求问清楚，再开始查真实地点'
})
const pageSubtitle = computed(() => {
  if (view.value === 'trace') return '每个 Agent、外部服务、降级和阻断原因都会写进证据链。'
  if (view.value === 'result') return '方案只使用真实候选地点；数量不足、天气缺失、路线风险都会明确提示。'
  return '像地图产品一样可靠，像点评产品一样解释理由，像订座产品一样关注执行细节。'
})
const intentRows = computed(() => {
  const group = intent.value.group || {}
  const location = intent.value.location || {}
  const timeWindow = intent.value.time_window || {}
  const preferences = intent.value.soft_preferences || {}
  return [
    { label: '场景', value: scenarioText(intent.value.scenario) },
    { label: '人数', value: group.total ? `${group.total} 人` : '待确认' },
    { label: '同行', value: group.composition || '待确认' },
    { label: '地点', value: [location.city, location.district].filter(Boolean).join(' · ') || coordinatesText(location) || '待确认' },
    { label: '时间', value: timeWindow.start ? `${timeWindow.start}${timeWindow.end ? ` 至 ${timeWindow.end}` : ''}` : '待确认' },
    { label: '预算', value: budgetText(preferences.budget, preferences.budgetAmount) }
  ]
})

function fieldIndex(field) {
  const index = clarificationFields.value.findIndex(item => item.key === field.key)
  return String.fromCharCode(65 + Math.max(0, index))
}

function choiceLabel(index) {
  return String.fromCharCode(65 + index)
}

function fieldChoices(field) {
  if (!field) return []
  if (Array.isArray(field.options) && field.options.length) {
    return field.options.map(option => typeof option === 'string' ? option : option?.text).filter(Boolean)
  }
  return field.suggestions || []
}

async function login() {
  loading.value = true
  error.value = ''
  try {
    const data = await createSession(nickname.value)
    token.value = data.token
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function plan() {
  loading.value = true
  loadingText.value = '判断需求是否完整'
  error.value = ''
  execution.value = null
  try {
    const data = await createPlan(planPayload())
    applyPlan(data)
    view.value = data.status === 'NEEDS_CLARIFICATION' ? 'compose' : 'result'
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
    loadingText.value = 'Agent 编排中'
  }
}

async function submitClarification() {
  const validationError = validateClarificationAnswers()
  if (validationError) {
    error.value = validationError
    return
  }
  loading.value = true
  loadingText.value = '补齐信息后查询真实地点'
  error.value = ''
  try {
    const data = await createPlan({
      ...planPayload(),
      clarificationAnswers: clarificationAnswers.value,
      previousPlanId: currentPlanId.value || null
    })
    applyPlan(data)
    if (data.status !== 'NEEDS_CLARIFICATION') {
      clarification.value = {}
      clarificationAnswers.value = {}
      view.value = 'result'
    }
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
    loadingText.value = 'Agent 编排中'
  }
}

async function confirm(rank) {
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
  loading.value = true
  error.value = ''
  try {
    const data = await sendFeedback(currentPlanId.value, feedback.value)
    applyPlan(data)
    feedback.value = ''
    view.value = data.status === 'NEEDS_CLARIFICATION' ? 'compose' : 'result'
  } catch (err) {
    handleRequestError(err)
  } finally {
    loading.value = false
  }
}

function applyPlan(data) {
  currentPlanId.value = data.planId
  options.value = data.options || []
  trace.value = data.trace || []
  intent.value = data.intent || {}
  clarification.value = data.clarification || {}
  weatherInfo.value = Object.keys(data.weather || {}).length ? data.weather : null
  warnings.value = data.warnings || []
  selectedRank.value = options.value[0]?.rank || 1
}

function handleRequestError(err) {
  error.value = err.message
  const payload = err.payload || {}
  if (!payload || !Object.keys(payload).length) return
  currentPlanId.value = payload.planId || currentPlanId.value
  trace.value = payload.trace || trace.value
  warnings.value = payload.error ? [payload.error] : warnings.value
  if (payload.status === 'BLOCKED' && trace.value.length) view.value = 'trace'
}

function planPayload() {
  return {
    message: message.value,
    planCount: planCount.value,
    stopCountPreference: stopCountPreference.value
  }
}

function customPlaceholder(field) {
  const hints = {
    location: '例如：杭州西湖附近；或：当前位置 120.123456,30.123456',
    timeWindow: '例如：10:00、14:30、晚上7点',
    duration: '例如：3小时左右、晚饭后结束',
    group: '例如：情侣两人、两个大人一个6岁孩子、4个朋友',
    budget: '例如：总预算600元、每人200左右',
    preferences: '例如：亲子室内活动和清淡晚餐，少走路'
  }
  return hints[field.key] || `填写${field.label}`
}

function validateClarificationAnswers() {
  const locationField = clarificationFields.value.find(field => field.key === 'location')
  if (!locationField) return ''
  const location = String(clarificationAnswers.value.location || '').trim()
  if (!location) return '请补充具体地点：城市 + 商圈/地标/地址，或当前位置坐标。'
  if (location.includes('+')) return '地点不能使用示例模板，请填写真实城市和地标。'
  if (['我附近', '附近', '在我附近', '当前位置', '当前位置附近', '本地'].includes(location)) {
    return '“附近”需要真实定位。请点击“当前位置”，或填写城市和地标。'
  }
  return ''
}

function useBrowserLocation() {
  if (!navigator.geolocation) {
    error.value = '当前浏览器不支持定位，请手动填写城市和地标。'
    return
  }
  locating.value = true
  error.value = ''
  navigator.geolocation.getCurrentPosition(
    (position) => {
      const lng = position.coords.longitude.toFixed(6)
      const lat = position.coords.latitude.toFixed(6)
      clarificationAnswers.value.location = `当前位置 ${lng},${lat}`
      locating.value = false
    },
    () => {
      error.value = '定位未授权或失败，请手动填写城市和地标。'
      locating.value = false
    },
    { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
  )
}

function formatHours(minutes) {
  if (!minutes) return '- 小时'
  return `${Math.round((minutes / 60) * 10) / 10} 小时`
}

function formatMoney(value) {
  if (value === undefined || value === null || value === '') return '预算待确认'
  return `约 ${value} 元`
}

function coordinatesText(location) {
  if (location?.lng && location?.lat) return `${location.lng},${location.lat}`
  return ''
}

function statusText(status) {
  const map = {
    ok: '成功',
    empty: '空结果',
    fallback: '降级',
    blocked: '阻断',
    external_error: '异常'
  }
  return map[status] || status || '未知'
}

function toolText(tool) {
  const map = {
    IntentParserAgent: '需求解析',
    ClarificationAgent: '澄清卡片',
    WebSearchTool: '联网核验',
    SearchVerifierAgent: '联网核验',
    AmapPoiSearchTool: '高德地点',
    AmapGeocodeTool: '高德定位',
    AmapRouteEstimateTool: '高德路线',
    PlanGeneratorAgent: '方案表达',
    PoiSearchTool: '测试地点',
    RouteEstimateTool: '测试路线',
    RestaurantAvailabilityTool: '座位检查',
    TicketAvailabilityTool: '票务检查',
    ExceptionRecoveryTool: '异常恢复',
    BookingTool: '执行预订',
    DeliveryGiftTool: '配送安排',
    ShareMessageTool: '分享文案',
    AmapWeatherTool: '高德天气',
    FeedbackIntentPatchAgent: '反馈解析'
  }
  return map[tool] || tool || '系统工具'
}

function modeText(mode) {
  const map = {
    real: '真实',
    rule: '规则',
    mock: '测试',
    fallback: '降级',
    skipped: '跳过',
    blocked: '阻断'
  }
  return map[mode] || '本地'
}

function scenarioText(value) {
  const map = {
    family: '亲子',
    friends: '朋友聚会',
    couple: '约会',
    solo: '单人',
    business: '商务',
    general: '普通出行',
    unknown: '待确认'
  }
  return map[value] || '待确认'
}

function budgetText(value, amount) {
  if (amount) return `约 ${amount} 元`
  const map = { low: '偏低', medium: '适中', high: '偏高' }
  return map[value] || value || '待确认'
}

function summarizeTrace(item) {
  const out = item.output || {}
  if (item.mode === 'fallback') return `降级原因：${item.externalStatus || out.reason || '外部服务不可用'}`
  if (out.count !== undefined) return `候选数量：${out.count}`
  if (out.travelMinutes) return `交通耗时：${out.travelMinutes} 分钟，距离 ${out.distanceKm || '-'} 公里`
  if (out.message) return out.message
  if (out.reason) return out.reason
  return item.externalStatus ? `外部状态：${item.externalStatus}` : '已记录'
}
</script>
