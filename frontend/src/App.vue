<template>
  <main class="shell">
    <section class="sidebar">
      <div class="brand">
        <span>LA</span>
        <div>
          <strong>Local Life Agent</strong>
          <p>本地路线智能规划 Demo</p>
        </div>
      </div>

      <section class="panel">
        <p class="eyebrow">Session</p>
        <h1>一句话安排下午</h1>
        <div class="inline">
          <input v-model="nickname" placeholder="昵称" />
          <button @click="login" :disabled="loading">{{ token ? '换会话' : '创建会话' }}</button>
        </div>
        <textarea v-model="message" rows="7" placeholder="今天下午带老婆孩子出去玩，孩子5岁，老婆在减肥，别离家太远" />
        <div class="samples">
          <button v-for="sample in samples" :key="sample.label" @click="message = sample.text">{{ sample.label }}</button>
        </div>
        <button class="primary" @click="plan" :disabled="!canPlan">{{ loading ? loadingText : '生成 Top-3 方案' }}</button>
        <p v-if="error" class="error">{{ error }}</p>
      </section>

      <section class="panel compact">
        <p class="eyebrow">Constraints</p>
        <div class="constraint-grid">
          <span>4-6 小时</span>
          <span>≥3 POI</span>
          <span>餐饮 + 活动</span>
          <span>Mock 执行</span>
        </div>
      </section>

      <section v-if="currentPlanId" class="panel compact">
        <p class="eyebrow">Feedback</p>
        <textarea v-model="feedback" rows="3" placeholder="比如：不要太远，晚饭换清淡一点" />
        <button class="ghost full" @click="adjustPlan" :disabled="!feedback.trim() || loading">实时调整</button>
      </section>
    </section>

    <section class="content">
      <header class="topbar">
        <div>
          <p class="eyebrow">Workspace</p>
          <h2>{{ execution ? '执行完成' : options.length ? '推荐方案' : '等待规划' }}</h2>
        </div>
        <div class="status-pill">{{ token ? 'Session Ready' : 'Need Session' }}</div>
      </header>

      <div v-if="!options.length" class="empty">
        <h2>把手机递给家人或朋友前，先让 Agent 做完粗活。</h2>
        <p>系统会规划去哪玩、去哪吃、额外活动，以及确认后的 Mock 下单/预约/分享动作。</p>
      </div>

      <div v-else class="layout">
        <section class="plans">
          <article v-for="option in options" :key="option.rank" :class="['plan-card', selectedRank === option.rank && 'selected']">
            <div class="plan-head">
              <div>
                <p class="eyebrow">Plan {{ option.rank }}</p>
                <h3>{{ option.tagline }}</h3>
              </div>
              <strong>{{ option.score }}</strong>
            </div>
            <div class="meta">
              <span>{{ Math.round(option.totalMinutes / 60 * 10) / 10 }} 小时</span>
              <span>约 ¥{{ option.budgetEstimate }}</span>
              <span>{{ option.route.distanceKm }} km</span>
            </div>
            <ol class="timeline">
              <li v-for="item in option.timeline" :key="item.name">
                <time>{{ item.time }}</time>
                <div>
                  <strong>{{ item.name }}</strong>
                  <p>{{ item.subtype }} · {{ item.durationMinutes }}分钟 · {{ item.address }}</p>
                </div>
              </li>
            </ol>
            <div class="reasons">
              <span v-for="reason in option.fitReasons" :key="reason">{{ reason }}</span>
            </div>
            <div class="actions">
              <button class="ghost" @click="selectedRank = option.rank">查看</button>
              <button @click="confirm(option.rank)" :disabled="loading">确认并执行</button>
            </div>
          </article>
        </section>

        <aside class="trace">
          <section class="panel compact">
            <p class="eyebrow">Intent</p>
            <pre>{{ pretty(intent) }}</pre>
          </section>

          <section class="panel compact">
            <p class="eyebrow">Tool Trace</p>
            <div class="trace-list">
              <div v-for="(item, idx) in trace" :key="idx" :class="['trace-item', item.status]">
                <strong>{{ item.tool }}</strong>
                <span>{{ item.status }} · {{ item.mode || 'mock' }} · {{ item.provider || 'local' }} · {{ item.durationMs }}ms</span>
                <p>{{ summarizeTrace(item) }}</p>
              </div>
            </div>
          </section>

          <section v-if="execution" class="panel compact">
            <p class="eyebrow">Execution</p>
            <div class="orders">
              <div v-for="order in execution.orders" :key="order.orderNo">
                <strong>{{ order.target }}</strong>
                <span>{{ order.action }} · {{ order.status }}</span>
                <code>{{ order.orderNo }}</code>
              </div>
            </div>
            <div class="share">
              <strong>分享消息</strong>
              <p>{{ execution.shareMessage }}</p>
            </div>
          </section>
        </aside>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { confirmPlan, createPlan, createSession, sendFeedback } from './api.js'

const nickname = ref('auditor')
const token = ref(localStorage.getItem('lla_token') || '')
const message = ref('今天下午带老婆孩子出去玩，孩子5岁，老婆在减肥，别离家太远')
const feedback = ref('')
const loading = ref(false)
const loadingText = ref('Agent 编排中...')
const error = ref('')
const currentPlanId = ref('')
const options = ref([])
const trace = ref([])
const intent = ref({})
const execution = ref(null)
const selectedRank = ref(1)

const samples = [
  { label: '家庭低卡', text: '今天下午带老婆孩子出去玩，孩子5岁，老婆在减肥，别离家太远' },
  { label: '朋友聚会', text: '今天下午和朋友出去玩，总共4个人，2男2女，想玩点有意思的再吃饭' },
  { label: '清淡调整', text: '下午想安排文化展览和清淡晚饭，路线不要折腾' }
]

const canPlan = computed(() => token.value && message.value.trim() && !loading.value)

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
  error.value = ''
  execution.value = null
  try {
    const data = await createPlan(message.value)
    applyPlan(data)
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
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
  selectedRank.value = options.value[0]?.rank || 1
}

function pretty(value) {
  return JSON.stringify(value, null, 2)
}

function summarizeTrace(item) {
  const out = item.output || {}
  if (item.mode === 'fallback') return `降级原因：${item.externalStatus || out.reason || 'external unavailable'}`
  if (out.replacement) return `自动替换为：${out.replacement}`
  if (out.count) return `候选数量：${out.count}`
  if (out.available === false) return '不可用，进入异常恢复'
  if (out.travelMinutes) return `交通 ${out.travelMinutes} 分钟`
  if (out.message) return out.message
  return JSON.stringify(out)
}
</script>
