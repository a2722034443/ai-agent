<template>
  <main class="app-shell">
    <aside class="workspace-rail">
      <div class="brand-row">
        <span class="brand-mark">本</span>
        <div>
          <strong>本地生活助手</strong>
          <p>真实地点 · 多角色编排</p>
        </div>
      </div>

      <nav class="rail-nav" aria-label="工作区">
        <button :class="{ active: view === 'compose' }" @click="view = 'compose'">需求</button>
        <button :class="{ active: view === 'trace' }" @click="view = 'trace'" :disabled="!trace.length">链路</button>
        <button :class="{ active: view === 'result' }" @click="view = 'result'" :disabled="!options.length">方案</button>
      </nav>

      <section class="system-state">
        <div>
          <span>会话</span>
          <strong>{{ token ? '已创建' : '未创建' }}</strong>
        </div>
        <div>
          <span>数据源</span>
          <strong>真实服务</strong>
        </div>
        <div>
          <span>方案数</span>
          <strong>{{ options.length || 0 }}</strong>
        </div>
      </section>
    </aside>

    <section class="main-stage">
      <header class="topbar">
        <div>
          <p class="caption">规划控制台</p>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="top-actions">
          <span :class="['live-dot', loading && 'loading']"></span>
          <span>{{ loading ? loadingText : '就绪' }}</span>
        </div>
      </header>

      <section v-if="view === 'compose'" class="compose-grid">
        <div class="input-pane">
          <div class="field-row">
            <label>
              <span>称呼</span>
              <input v-model="nickname" placeholder="例如：体验用户" />
            </label>
            <button class="secondary" @click="login" :disabled="loading">
              {{ token ? '更新会话' : '创建会话' }}
            </button>
          </div>

          <label class="message-box">
            <span>你的需求</span>
            <textarea
              v-model="message"
              rows="9"
              placeholder="例如：今天下午在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右"
            />
          </label>

          <div class="quick-row">
            <button v-for="sample in samples" :key="sample.label" class="text-button" @click="message = sample.text">
              {{ sample.label }}
            </button>
          </div>

          <div class="preference-grid">
            <label>
              <span>方案数量</span>
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

          <section v-if="clarificationFields.length" class="clarification-panel">
            <div class="clarification-head">
              <p class="caption">需要确认</p>
              <h2>补齐关键条件后再规划</h2>
              <p>{{ clarification.message }}</p>
            </div>
            <div class="clarification-grid">
              <div v-for="field in clarificationFields" :key="field.key" class="clarification-card">
                <div class="card-index">{{ fieldIndex(field) }}</div>
                <div class="card-copy">
                  <strong>{{ field.label }}</strong>
                  <p>{{ field.question }}</p>
                </div>
                <div class="choice-grid">
                  <button
                    v-for="(suggestion, idx) in field.suggestions || []"
                    :key="`${field.key}-${suggestion}`"
                    :class="['choice-button', clarificationAnswers[field.key] === suggestion && 'selected']"
                    @click="clarificationAnswers[field.key] = suggestion"
                  >
                    <span>{{ choiceLabel(idx) }}</span>
                    {{ suggestion }}
                  </button>
                </div>
                <input v-model="clarificationAnswers[field.key]" :placeholder="`自定义${field.label}`" />
              </div>
            </div>
            <button class="primary" @click="submitClarification" :disabled="loading">补齐后生成</button>
          </section>

          <div class="submit-row">
            <button class="primary" @click="plan" :disabled="!canPlan">
              {{ loading ? '生成中' : '生成真实方案' }}
            </button>
            <p>正常模式只使用真实高德、联网搜索和大模型；失败会中文阻断，不编造地点。</p>
          </div>

          <p v-if="error" class="error-line">{{ error }}</p>
        </div>

        <aside class="brief-pane">
          <div class="metric-line">
            <span>当前令牌</span>
            <code>{{ token ? '已保存' : '等待创建' }}</code>
          </div>
          <div class="metric-line">
            <span>当前计划</span>
            <code>{{ currentPlanId ? '已生成' : '未生成' }}</code>
          </div>
          <div class="metric-line">
            <span>执行策略</span>
            <code>真实规划 / 模拟执行</code>
          </div>
        </aside>
      </section>

      <section v-if="view === 'result'" class="result-view">
        <section v-if="warnings.length" class="warning-panel">
          <div v-for="warning in warnings" :key="warning" class="warning-item">
            {{ warning }}
          </div>
        </section>

        <section v-if="weatherInfo" class="weather-panel">
          <div>
            <span>天气</span>
            <strong>{{ weatherInfo.weather || '暂不可用' }}</strong>
          </div>
          <div>
            <span>温度</span>
            <strong>{{ weatherInfo.temperature ? `${weatherInfo.temperature}℃` : '-' }}</strong>
          </div>
          <p>{{ weatherInfo.suggestion || '建议出发前再次确认天气变化。' }}</p>
        </section>

        <div class="result-toolbar">
          <div>
          <p class="caption">{{ options.length }} 套方案</p>
            <h2>候选方案</h2>
          </div>
          <label class="feedback-box">
            <input v-model="feedback" placeholder="提出调整，例如：预算太高、不要太远、换清淡餐厅" />
            <button class="secondary" @click="adjustPlan" :disabled="!feedback.trim() || loading">调整</button>
          </label>
        </div>

        <div class="plan-table">
          <article v-for="option in options" :key="option.rank" :class="['plan-row', selectedRank === option.rank && 'selected']">
            <button class="rank-button" @click="selectedRank = option.rank">{{ option.rank }}</button>
            <div class="plan-main">
              <div class="plan-title">
                <h3>{{ option.tagline || `方案 ${option.rank}` }}</h3>
                <strong>{{ option.score ?? '-' }}</strong>
              </div>
              <div class="plan-meta">
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
              <div class="reason-line">
                <span v-for="reason in option.fitReasons || []" :key="reason">{{ reason }}</span>
              </div>
              <div class="reason-line muted">
                <span v-for="risk in option.riskNotes || []" :key="risk">{{ risk }}</span>
              </div>
            </div>
            <button class="primary slim" @click="confirm(option.rank)" :disabled="loading">确认执行</button>
          </article>
        </div>

        <section v-if="execution" class="execution-panel">
          <h2>执行结果</h2>
          <div class="order-list">
            <div v-for="order in execution.orders || []" :key="order.orderNo">
              <strong>{{ order.target }}</strong>
              <span>{{ order.action }} · {{ order.status }}</span>
              <code>{{ order.orderNo }}</code>
            </div>
          </div>
          <p>{{ execution.shareMessage }}</p>
        </section>
      </section>

      <section v-if="view === 'trace'" class="trace-view">
        <div class="trace-summary">
          <div>
            <span>意图解析</span>
            <dl class="intent-list">
              <div v-for="row in intentRows" :key="row.label">
                <dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd>
              </div>
            </dl>
          </div>
          <div>
            <span>链路概览</span>
            <strong>{{ trace.length }} 步</strong>
          </div>
        </div>

        <div class="trace-table">
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
const message = ref('今天下午在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右')
const feedback = ref('')
const planCount = ref(3)
const stopCountPreference = ref('标准')
const loading = ref(false)
const loadingText = ref('助手正在编排')
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
  { label: '亲子半日', text: '今天下午2点在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右' },
  { label: '朋友聚会', text: '今天晚上7点在大连中山区，4个朋友，预算800元，想先逛一个有意思的地方，再吃饭，路线不要太折腾' },
  { label: '清淡调整', text: '下午想安排文化展览和清淡晚餐，步行距离尽量短，不要太吵' }
]

const canPlan = computed(() => token.value && message.value.trim() && !loading.value)
const clarificationFields = computed(() => clarification.value?.fields || [])
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
const pageTitle = computed(() => {
  if (view.value === 'trace') return '执行链路'
  if (view.value === 'result') return '真实候选方案'
  return '输入需求并生成规划'
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
    loadingText.value = '助手正在编排'
  }
}

async function submitClarification() {
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
    loadingText.value = '助手正在编排'
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
