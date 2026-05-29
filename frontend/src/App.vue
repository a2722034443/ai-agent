<template>
  <main class="app-shell">
    <section class="workspace">
      <header class="app-header">
        <div class="brand-lockup">
          <span class="brand-mark">本</span>
          <div>
            <strong>本地生活 Agent</strong>
            <p>真实地点编排台</p>
          </div>
        </div>

        <nav class="view-tabs" aria-label="视图">
          <button :class="{ active: view === 'compose' }" @click="view = 'compose'">需求</button>
          <button :class="{ active: view === 'trace' }" @click="view = 'trace'" :disabled="!trace.length">链路</button>
          <button :class="{ active: view === 'result' }" @click="view = 'result'" :disabled="!options.length">方案</button>
        </nav>

        <div class="run-state">
          <span :class="['run-dot', loading && 'active']"></span>
          <strong>{{ loading ? loadingText : '就绪' }}</strong>
        </div>
      </header>

      <section class="hero-workbench">
        <div class="hero-copy">
          <p class="eyebrow">多 Agent 真实规划</p>
          <h1>{{ pageTitle }}</h1>
          <p>{{ pageSubtitle }}</p>
        </div>
        <div class="hero-metrics">
          <div>
            <span>会话</span>
            <strong>{{ token ? '已连接' : '待创建' }}</strong>
          </div>
          <div>
            <span>方案</span>
            <strong>{{ options.length || planCount }} 套</strong>
          </div>
          <div>
            <span>链路</span>
            <strong>{{ trace.length || 0 }} 步</strong>
          </div>
        </div>
      </section>

      <section v-if="view === 'compose'" class="compose-layout">
        <section class="request-panel">
          <div class="panel-title">
            <div>
              <p class="eyebrow">输入</p>
              <h2>需求任务</h2>
            </div>
            <button class="ghost-button" @click="login" :disabled="loading">
              {{ token ? '刷新会话' : '创建会话' }}
            </button>
          </div>

          <div class="identity-row">
            <label>
              <span>称呼</span>
              <input v-model="nickname" placeholder="体验用户" />
            </label>
            <label>
              <span>方案数量</span>
              <select v-model.number="planCount">
                <option v-for="count in [1, 2, 3, 4, 5]" :key="count" :value="count">{{ count }} 套</option>
              </select>
            </label>
            <label>
              <span>密度</span>
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
              placeholder="例如：今天下午2点在杭州西湖附近，两个人，预算600元，想轻松逛逛再吃晚餐，时间3小时左右"
            />
          </label>

          <div class="sample-strip">
            <button v-for="sample in samples" :key="sample.label" @click="message = sample.text">
              {{ sample.label }}
            </button>
          </div>

          <section v-if="clarificationFields.length" class="clarification-panel">
            <div class="clarification-head">
              <span>Agent 追问</span>
              <strong>{{ clarification.message }}</strong>
            </div>
            <div class="clarification-list">
              <article v-for="field in clarificationFields" :key="field.key" class="clarification-item">
                <div class="field-copy">
                  <span>{{ fieldIndex(field) }}</span>
                  <div>
                    <strong>{{ field.label }}</strong>
                    <p>{{ field.question }}</p>
                  </div>
                </div>
                <div v-if="(field.suggestions || []).length" class="choice-strip">
                  <button
                    v-for="(suggestion, idx) in field.suggestions || []"
                    :key="`${field.key}-${suggestion}`"
                    :class="{ selected: clarificationAnswers[field.key] === suggestion }"
                    @click="clarificationAnswers[field.key] = suggestion"
                  >
                    <b>{{ choiceLabel(idx) }}</b>{{ suggestion }}
                  </button>
                </div>
                <div v-else-if="field.key === 'location'" class="field-helper">
                  需要真实城市、地标或浏览器定位；不会默认使用任何城市。
                </div>
                <button
                  v-if="field.key === 'location'"
                  class="location-button"
                  type="button"
                  @click="useBrowserLocation"
                  :disabled="loading || locating"
                >
                  {{ locating ? '定位中' : '使用当前位置' }}
                </button>
                <input v-model="clarificationAnswers[field.key]" :placeholder="customPlaceholder(field)" />
              </article>
            </div>
            <button class="primary-button wide" @click="submitClarification" :disabled="loading">补齐后生成</button>
          </section>

          <div class="action-bar">
            <button class="primary-button" @click="plan" :disabled="!canPlan">
              {{ loading ? '生成中' : '生成真实方案' }}
            </button>
            <span v-if="error" class="error-line">{{ error }}</span>
          </div>
        </section>

        <aside class="agent-panel">
          <div class="panel-title">
            <div>
              <p class="eyebrow">编排</p>
              <h2>Agent 链路</h2>
            </div>
          </div>
          <ol class="agent-ladder">
            <li v-for="(step, index) in agentSteps" :key="step.name" :class="{ done: traceToolNames.has(step.tool), active: loading && index === activeAgentIndex }">
              <span>{{ index + 1 }}</span>
              <div>
                <strong>{{ step.name }}</strong>
                <p>{{ step.desc }}</p>
              </div>
            </li>
          </ol>
        </aside>
      </section>

      <section v-if="view === 'result'" class="result-layout">
        <aside class="insight-column">
          <section v-if="warnings.length" class="notice-panel">
            <strong>风险提示</strong>
            <p v-for="warning in warnings" :key="warning">{{ warning }}</p>
          </section>

          <section class="weather-panel">
            <span>天气</span>
            <strong>{{ weatherInfo?.weather || '暂不可用' }}</strong>
            <p>{{ weatherInfo?.temperature ? `${weatherInfo.temperature}℃` : '温度待确认' }}</p>
            <small>{{ weatherInfo?.suggestion || '建议出发前再次确认天气变化。' }}</small>
          </section>

          <section class="intent-panel">
            <strong>需求摘要</strong>
            <dl>
              <div v-for="row in intentRows" :key="row.label">
                <dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd>
              </div>
            </dl>
          </section>
        </aside>

        <section class="plans-panel">
          <div class="result-toolbar">
            <div>
              <p class="eyebrow">{{ options.length }} 套真实候选</p>
              <h2>方案对比</h2>
            </div>
            <label class="feedback-box">
              <input v-model="feedback" placeholder="预算太高、不要太远、换清淡餐厅" />
              <button class="ghost-button" @click="adjustPlan" :disabled="!feedback.trim() || loading">调整</button>
            </label>
          </div>

          <div class="plan-stack">
            <article v-for="option in options" :key="option.rank" :class="['plan-item', selectedRank === option.rank && 'selected']">
              <button class="rank-chip" @click="selectedRank = option.rank">{{ option.rank }}</button>
              <div class="plan-content">
                <div class="plan-heading">
                  <div>
                    <h3>{{ option.tagline || `方案 ${option.rank}` }}</h3>
                    <p>{{ option.name || '真实可行方案' }}</p>
                  </div>
                  <strong>{{ option.score ?? '-' }}</strong>
                </div>

                <div class="plan-stats">
                  <span>{{ formatHours(option.totalMinutes) }}</span>
                  <span>约 {{ formatMoney(option.budgetEstimate) }}</span>
                  <span>{{ option.route?.distanceKm ?? '-' }} 公里</span>
                </div>

                <ol class="timeline">
                  <li v-for="item in option.timeline || []" :key="`${option.rank}-${item.time}-${item.name}`">
                    <time>{{ item.time }}</time>
                    <div>
                      <strong>{{ item.name }}</strong>
                      <p>{{ item.subtype || '地点' }} · {{ item.durationMinutes || '-' }} 分钟 · {{ item.address || '地址待确认' }}</p>
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

      <section v-if="view === 'trace'" class="trace-layout">
        <section class="trace-overview">
          <div>
            <p class="eyebrow">解析结果</p>
            <h2>意图摘要</h2>
          </div>
          <dl>
            <div v-for="row in intentRows" :key="row.label">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </div>
          </dl>
        </section>

        <section class="trace-panel">
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
            <span>{{ item.durationMs }} 毫秒</span>
            <p>{{ summarizeTrace(item) }}</p>
          </div>
        </section>
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
  { label: '朋友聚会', text: '今天晚上7点在上海静安寺附近，4个朋友，预算800元，想先逛一个有意思的地方，再吃饭，路线不要太折腾' },
  { label: '清淡晚餐', text: '今天下午3点在杭州西湖附近，两个人，预算600元，想安排文化展览和清淡晚餐，步行距离尽量短，不要太吵' }
]

const agentSteps = [
  { name: '需求澄清', desc: '检查地点、时间、人数、预算、时长', tool: 'IntentParserAgent' },
  { name: '天气判断', desc: '获取城市天气并影响排序', tool: 'AmapWeatherTool' },
  { name: '联网核验', desc: '检索评价、排队、近期活动', tool: 'WebSearchTool' },
  { name: '真实地点', desc: '高德 POI 候选检索', tool: 'AmapPoiSearchTool' },
  { name: '路线评估', desc: '预筛后计算可执行路线', tool: 'AmapRouteEstimateTool' },
  { name: '异常恢复', desc: '跳过无座、无票、过远、冲突候选', tool: 'ExceptionRecoveryTool' },
  { name: '确认执行', desc: '确认后模拟订座、购票、分享', tool: 'BookingTool' }
]

const canPlan = computed(() => token.value && message.value.trim() && !loading.value)
const clarificationFields = computed(() => clarification.value?.fields || [])
const traceToolNames = computed(() => new Set(trace.value.map(item => item.tool)))
const activeAgentIndex = computed(() => Math.min(trace.value.length, agentSteps.length - 1))
const pageTitle = computed(() => {
  if (view.value === 'trace') return '工具链路与证据'
  if (view.value === 'result') return '真实候选方案'
  return '描述你的本地生活需求'
})
const pageSubtitle = computed(() => {
  if (view.value === 'trace') return '每一步调用都会写入状态、耗时、模式和外部服务结果。'
  if (view.value === 'result') return '方案只使用真实候选地点，数量不足时会说明原因。'
  return '先补齐关键条件，再查询真实地点、天气、路线并生成可执行方案。'
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
    { label: '地点', value: [location.city, location.district].filter(Boolean).join(' · ') || '待确认' },
    { label: '时间', value: `${timeWindow.start || '待定'} 至 ${timeWindow.end || '待定'}` },
    { label: '预算', value: budgetText(preferences.budget) }
  ]
})

function fieldIndex(field) {
  const index = clarificationFields.value.findIndex(item => item.key === field.key)
  return String.fromCharCode(65 + Math.max(0, index))
}

function choiceLabel(index) {
  return String.fromCharCode(65 + index)
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
  loadingText.value = '真实服务调用中'
  error.value = ''
  execution.value = null
  try {
    const data = await createPlan(planPayload())
    applyPlan(data)
    view.value = data.status === 'NEEDS_CLARIFICATION' ? 'compose' : 'result'
  } catch (err) {
    error.value = err.message
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
  loadingText.value = '补充信息后重新规划'
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
    error.value = err.message
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
    error.value = err.message
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
  } catch (err) {
    error.value = err.message
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

function planPayload() {
  return {
    message: message.value,
    planCount: planCount.value,
    stopCountPreference: stopCountPreference.value
  }
}

function customPlaceholder(field) {
  if (field.key === 'location') return '请输入真实城市和地标，例如：杭州西湖附近'
  if (field.key === 'timeWindow') return '例如：10:00、14:30、晚上7点'
  if (field.key === 'duration') return '例如：3小时左右、晚饭后结束'
  if (field.key === 'group') return '例如：我自己、情侣两人、4个朋友'
  if (field.key === 'budget') return '例如：300、600、1000'
  if (field.key === 'preferences') return '例如：轻松逛逛和吃饭'
  return `自定义${field.label}`
}

function validateClarificationAnswers() {
  const locationField = clarificationFields.value.find(field => field.key === 'location')
  if (!locationField) return ''
  const location = String(clarificationAnswers.value.location || '').trim()
  if (!location) return '请补充具体地点：城市 + 商圈/地标/地址。'
  const invalidTemplates = ['我所在城市 + 具体商圈', '我所在城市 + 地铁站/地标', '具体地址或附近道路']
  if (invalidTemplates.includes(location) || location.includes('+')) {
    return '地点不能使用示例模板，请填写真实城市和地标，例如：杭州西湖附近。'
  }
  if (['我附近', '附近', '在我附近', '当前位置', '当前位置附近'].includes(location)) {
    return '“附近”需要真实定位。请点击“使用当前位置”，或填写城市和地标，例如：杭州西湖附近。'
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
  if (value === undefined || value === null || value === '') return '-'
  return `¥${value}`
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
    IntentParserAgent: '意图解析',
    WebSearchTool: '联网核验',
    SearchVerifierAgent: '联网核验',
    AmapPoiSearchTool: '高德地点',
    AmapRouteEstimateTool: '高德路线',
    PlanGeneratorAgent: '方案生成',
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
  return map[tool] || '系统工具'
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
    unknown: '普通出行'
  }
  return map[value] || '普通出行'
}

function budgetText(value) {
  const map = {
    low: '偏低',
    medium: '适中',
    high: '偏高'
  }
  return map[value] || '待确认'
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
