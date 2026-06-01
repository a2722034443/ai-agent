<template>
  <main :class="['chat-app', `theme-${demoTheme}`]">
    <div class="paper-doodles" aria-hidden="true">
      <span class="doodle doodle-peach"></span>
      <span class="doodle doodle-blue"></span>
      <span class="doodle doodle-yellow"></span>
      <span class="doodle doodle-line"></span>
    </div>

    <header class="topbar scrapbook-panel">
      <div class="topbar-left">
        <button
          class="history-toggle"
          type="button"
          :title="sidebarOpen ? '关闭规划历史侧栏' : '打开规划历史侧栏'"
          :aria-label="sidebarOpen ? '关闭规划历史侧栏' : '打开规划历史侧栏'"
          @click="sidebarOpen = !sidebarOpen"
        >
          <svg
            class="history-toggle-icon"
            viewBox="0 0 24 24"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <rect x="3.5" y="4.5" width="17" height="15" rx="3.5" stroke="currentColor" stroke-width="1.7" />
            <path d="M8 4.5V19.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <path d="M11.5 8H17" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <path d="M11.5 12H16.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <path d="M11.5 16H15.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
        </button>
        <button class="brand" type="button" @click="activeView = 'chat'">
          <span class="brand-mark">
            <svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
              <circle cx="32" cy="32" r="27" fill="#ffd995" stroke="#73452f" stroke-width="3" />
              <path d="M18 29c4-10 24-15 32-8 6 5 5 17-2 24-7 7-22 7-30-1-6-6-7-10 0-15Z" fill="#76a8ff" opacity=".82" />
              <path d="M22 22c3-4 9-6 14-6 6 0 11 2 14 5" stroke="#73452f" stroke-width="3" stroke-linecap="round" />
              <circle cx="25" cy="31" r="2.6" fill="#73452f" />
              <circle cx="39" cy="31" r="2.6" fill="#73452f" />
              <path d="M27 40c2 2 8 2 10 0" stroke="#73452f" stroke-width="3" stroke-linecap="round" />
            </svg>
          </span>
          <span class="brand-copy">
            <strong>立刻游</strong>
            <small>像手账一样把本地出行安排明白</small>
          </span>
        </button>
      </div>
      <div class="meta">
        <span class="date-sticker">{{ todayText }}</span>
        <button class="avatar" type="button" @click="activeView = 'profile'">
          <svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <circle cx="24" cy="24" r="21" fill="#fff4df" stroke="#73452f" stroke-width="2.5" />
            <circle cx="24" cy="18" r="6" fill="#ff9d7b" />
            <path d="M13 35c3-6 8-9 11-9s8 3 11 9" fill="#7faeff" stroke="#73452f" stroke-width="2.5" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </header>

    <div class="app-layout">
      <section class="theme-switcher scrapbook-panel">
        <div class="theme-switcher-copy">
          <span class="section-sticker">Demo themes</span>
          <strong>同页切换 3 套方向</strong>
        </div>
        <div class="theme-switcher-tabs">
          <button
            v-for="option in themeOptions"
            :key="option.key"
            type="button"
            :class="['theme-chip', { active: demoTheme === option.key }]"
            @click="setDemoTheme(option.key)"
          >
            <strong>{{ option.label }}</strong>
            <small>{{ option.note }}</small>
          </button>
        </div>
      </section>

      <HistorySidebar
        :threads="historyThreads"
        :selected-thread-id="currentThreadId"
        :open="sidebarOpen"
        @new-thread="startNewThread"
        @open-thread="openThread"
        @rename-thread="renameThread"
        @delete-thread="removeThread"
      />
      <button v-if="sidebarOpen" type="button" class="sidebar-mask" @click="sidebarOpen = false"></button>

      <section v-if="activeView === 'chat'" :class="['chat-shell', { 'result-mode': showPlanWorkspace }]">
        <div v-if="messages.length === 0" class="empty-state">
          <AnimalCard
            v-if="isAnimalTheme"
            type="title"
            color="app-green"
            class="welcome-card animal-welcome-card animal-demo-shell"
          >
            <template #title>Animal-inspired Demo</template>
            <div class="animal-demo-copy">
              <h1>{{ themeHero.title }}</h1>
              <p>{{ themeHero.description }}</p>
            </div>
            <div class="animal-demo-prompts">
              <AnimalButton
                v-for="sample in quickPrompts"
                :key="sample.label"
                type="primary"
                size="middle"
                @click="useSample(sample.text)"
              >
                {{ sample.label }}
              </AnimalButton>
            </div>
          </AnimalCard>

          <section v-else :class="['welcome-card', 'scrapbook-panel', { 'irasutoya-welcome': isIrasutoyaTheme }]">
            <span class="welcome-tape tape-left"></span>
            <span class="welcome-tape tape-right"></span>
            <div class="welcome-copy">
              <span class="welcome-badge">{{ themeHero.badge }}</span>
              <h1>{{ themeHero.title }}</h1>
              <p>{{ themeHero.description }}</p>
            </div>
            <div v-if="isIrasutoyaTheme" class="welcome-irasutoya-art">
              <img :src="themeHero.image" alt="いらすとや 演示插画" />
            </div>
            <div v-else class="welcome-mascot" aria-hidden="true">
              <svg viewBox="0 0 160 140" xmlns="http://www.w3.org/2000/svg">
                <path d="M24 84c0-26 21-48 48-48h27c22 0 37 16 37 36s-12 40-37 40H58c-18 0-34-10-34-28Z" fill="#fff4df" stroke="#73452f" stroke-width="4" />
                <path d="M86 18c11 0 21 4 28 11" stroke="#73452f" stroke-width="4" stroke-linecap="round" />
                <path d="M45 62c8-7 22-12 42-12 12 0 28 5 37 11" fill="#87b3ff" stroke="#73452f" stroke-width="4" stroke-linejoin="round" />
                <circle cx="60" cy="70" r="4" fill="#73452f" />
                <circle cx="97" cy="70" r="4" fill="#73452f" />
                <path d="M67 87c6 4 17 4 23 0" stroke="#73452f" stroke-width="4" stroke-linecap="round" />
                <circle cx="29" cy="29" r="13" fill="#ffd995" stroke="#73452f" stroke-width="3" />
                <path d="M120 98c14 2 23 8 28 18" stroke="#73452f" stroke-width="4" stroke-linecap="round" />
              </svg>
            </div>
            <div class="quick-prompts">
              <button v-for="sample in quickPrompts" :key="sample.label" type="button" class="prompt-sticker" @click="useSample(sample.text)">
                <strong>{{ sample.label }}</strong>
                <small>{{ sample.note }}</small>
              </button>
            </div>
          </section>
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
              <span class="step-icon">{{ step.icon }}</span>
              <div class="step-copy">
                <em>步骤 {{ step.index }}</em>
                <strong>{{ step.title }}</strong>
                <small>{{ step.summary }}</small>
              </div>
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
              :class="['plan-card', { active: activeMapRank === plan.rank, picked: selectedRank === plan.rank }]"
              @click="viewPlanOnMap(plan.rank)"
            >
              <span class="plan-ribbon">手账路线 {{ plan.rank }}</span>
              <header>
                <div class="plan-heading">
                  <strong>{{ plan.name }}</strong>
                  <small>轻松好走，适合一起出发</small>
                </div>
                <span class="plan-tag">{{ plan.tag }}</span>
              </header>
              <p class="timeline-line">{{ compactTimeline(plan.timeline) }}</p>
              <div class="plan-meta">
                <span class="meta-chip money">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <circle cx="12" cy="12" r="9" fill="currentColor" opacity=".16" />
                    <path d="M10 7h3a2 2 0 1 1 0 4h-2a2 2 0 0 0 0 4h3M12 5v14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
                  </svg>
                  预算 {{ formatMoney(plan.budgetEstimate) }}
                </span>
                <span class="meta-chip time">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <circle cx="12" cy="12" r="9" fill="currentColor" opacity=".14" />
                    <path d="M12 7v5l3 2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  {{ formatHours(plan.totalMinutes) }}
                </span>
                <span class="meta-chip distance">
                  <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                    <path d="M5 18c4-8 10-11 14-12-1 4-4 10-12 14l1-5-3 3Z" fill="currentColor" opacity=".18" />
                    <path d="M7 17c2-4 6-8 11-10" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
                  </svg>
                  距离 {{ plan.route?.distanceKm || '2.1' }}km
                </span>
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
            <span v-if="item.role === 'assistant'" class="bubble-avatar">
              <svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <circle cx="24" cy="24" r="20" fill="#ffd995" stroke="#73452f" stroke-width="2.6" />
                <path d="M13 26c5-8 15-12 24-10 5 1 8 6 8 12 0 9-8 13-17 13-11 0-20-5-20-15 0-4 2-7 5-10Z" fill="#7faeff" opacity=".88" />
                <circle cx="19" cy="24" r="2.4" fill="#73452f" />
                <circle cx="30" cy="24" r="2.4" fill="#73452f" />
                <path d="M20 31c3 2 7 2 10 0" stroke="#73452f" stroke-width="2.6" stroke-linecap="round" />
              </svg>
            </span>
            <article class="bubble">
              <span class="bubble-pin">{{ item.role === 'assistant' ? 'AI 小贴士' : '你的便签' }}</span>
              <time>{{ item.time }}</time>
              <p v-if="item.text">{{ item.text }}</p>
              <div v-if="item.loading" class="typing">
                <span></span><span></span><span></span>
                正在为你规划方案...
              </div>
              <div v-if="item.clarification?.fields?.length" class="clarify-card">
                <header class="clarify-head">
                  <span class="clarify-badge">补齐小卡</span>
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

      <section v-else-if="activeView === 'collab'" :class="['page-panel', 'scrapbook-panel', { 'irasutoya-panel': isIrasutoyaTheme }]">
        <header>
          <span class="section-sticker">同行投票</span>
          <div>
            <h1>小明分享的出行方案</h1>
            <p>大家一起选方案、提意见，AI 会自动调整。</p>
          </div>
        </header>
        <div v-if="isIrasutoyaTheme" class="panel-illustration">
          <img :src="IRASUTOYA_IMAGES.collab" alt="いらすとや 协同演示插画" />
        </div>
        <div class="shared-plans">
          <article v-for="plan in shownPlans" :key="plan.rank" class="plan-card vote-card">
            <header>
              <div class="plan-heading">
                <strong>{{ plan.name }}</strong>
                <small>看看大家最想去哪一套</small>
              </div>
              <span class="plan-tag">{{ voteCount(plan.rank) }} 票</span>
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

      <section v-else-if="activeView === 'execute'" class="page-panel scrapbook-panel execution-panel">
        <header>
          <span class="section-sticker">执行中</span>
          <div>
            <h1>AI 正在帮你把事做完</h1>
            <p>门票、餐厅和提醒会按顺序推进，你只用准备出发。</p>
          </div>
        </header>
        <ol>
          <li v-for="step in executionSteps" :key="step.name" :class="step.status">
            <span>{{ step.status === 'done' ? '✓' : step.status === 'doing' ? '...' : '!' }}</span>
            {{ step.name }}
          </li>
        </ol>
        <button class="primary-button" type="button" @click="copyShareMessage">发给同行人</button>
      </section>

      <section v-else :class="['page-panel', 'scrapbook-panel', 'profile-panel', { 'irasutoya-panel': isIrasutoyaTheme }]">
        <header>
          <span class="section-sticker">偏好记忆</span>
          <div>
            <h1>全员记忆</h1>
            <p>老婆最近在减肥、孩子需要亲子设施、朋友不吃辣，下次规划会自动适配。</p>
          </div>
        </header>
        <div v-if="isIrasutoyaTheme" class="panel-illustration memory-illustration">
          <img :src="IRASUTOYA_IMAGES.memory" alt="いらすとや 记忆演示插画" />
        </div>
        <div class="memory-grid">
          <span v-for="item in memoryTags" :key="item">{{ item }}</span>
        </div>
      </section>

      <footer v-if="showComposer" class="composer scrapbook-panel">
        <span class="assistant-dot">
          <svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <circle cx="24" cy="24" r="21" fill="#ffd995" stroke="#73452f" stroke-width="2.5" />
            <path d="M10 26c4-7 13-11 22-10 6 1 10 5 10 11 0 10-8 14-18 14S6 38 6 29c0-2 2-5 4-7Z" fill="#8eb7ff" />
            <circle cx="19" cy="24" r="2.3" fill="#73452f" />
            <circle cx="29" cy="24" r="2.3" fill="#73452f" />
            <path d="M19 31c3 2 7 2 10 0" stroke="#73452f" stroke-width="2.5" stroke-linecap="round" />
          </svg>
        </span>
        <AnimalInput
          v-if="isAnimalTheme"
          v-model="message"
          class="animal-demo-input"
          size="large"
          :shadow="true"
          placeholder="输入你的出行需求，比如‘今天晚上静安寺，4 个朋友，预算 800’"
          @keydown.enter="plan"
        />
        <label v-else class="command-input">
          <span class="input-tag">便签输入</span>
          <input
            v-model="message"
            placeholder="输入你的出行需求，比如‘今天晚上静安寺，4 个朋友，预算 800’"
            @keydown.enter="plan"
          />
        </label>
        <button type="button" class="tool-button" :class="{ active: voiceRecording }" @click="toggleVoice">
          <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <rect x="8" y="4" width="8" height="12" rx="4" stroke="currentColor" stroke-width="2" />
            <path d="M5 12a7 7 0 1 0 14 0M12 19v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          </svg>
          <span>语音</span>
        </button>
        <button type="button" class="tool-button" @click="openImageTool">
          <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <rect x="4" y="5" width="16" height="14" rx="3" stroke="currentColor" stroke-width="2" />
            <circle cx="9" cy="10" r="1.5" fill="currentColor" />
            <path d="M6 16l4-4 3 3 2-2 3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>图片</span>
        </button>
        <button class="primary-button plan-button" type="button" :disabled="!message.trim() || loading" @click="plan">
          {{ loading ? '规划中' : '规划' }}
        </button>
        <input ref="fileInput" class="hidden-file" type="file" accept="image/*" @change="handleImagePick" />
      </footer>
    </div>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Button as AnimalButton, Card as AnimalCard, Input as AnimalInput } from 'animal-island-vue'
import 'animal-island-vue/style'
import irasutoyaCollab from './assets/demo-only/irasutoya/collab-talk.png'
import irasutoyaMemory from './assets/demo-only/irasutoya/family-memory.png'
import irasutoyaWelcome from './assets/demo-only/irasutoya/family-outing.png'
import {
  confirmPlan,
  createPlan,
  createSession,
  createShare,
  deleteHistoryThread,
  getGuardStatus,
  getHistoryThread,
  getHistoryThreads,
  getMemory,
  getSessionToken,
  renameHistoryThread,
  submitCollabComment,
  voteShare
} from './api.js'
import HistorySidebar from './components/HistorySidebar.vue'
import { clarificationSummary, restoreThreadState } from './historyState.js'
import TripMap from './components/TripMap.vue'

const DEMO_THEMES = ['animal', 'irasutoya', 'scrapbook']
const IRASUTOYA_IMAGES = {
  welcome: irasutoyaWelcome,
  collab: irasutoyaCollab,
  memory: irasutoyaMemory
}

const token = ref(localStorage.getItem('lla_token') || '')
const message = ref('')
const loading = ref(false)
const voiceRecording = ref(false)
const fileInput = ref(null)
const demoTheme = ref('scrapbook')
const activeView = ref('chat')
const currentStep = ref('need')
const messages = ref([])
const defaultExecutionSteps = [
  { name: '正在买童梦亲子乐园门票', status: 'doing' },
  { name: '正在订低卡餐厅座位', status: 'waiting' },
  { name: '正在安排孩子小礼物配送', status: 'waiting' }
]
const currentThreadId = ref('')
const historyThreads = ref([])
const sidebarOpen = ref(false)
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
const executionSteps = ref([...defaultExecutionSteps])
const memoryTags = ref(['老婆减肥', '孩子要亲子设施', '朋友不吃辣', '周末不跑远'])

const hasFinalPlans = computed(() => shownPlans.value.length > 0)
const isAnimalTheme = computed(() => demoTheme.value === 'animal')
const isIrasutoyaTheme = computed(() => demoTheme.value === 'irasutoya')
const showPlanWorkspace = computed(() => activeView.value === 'chat' && hasFinalPlans.value && currentStep.value === 'plans')
const showComposer = computed(() => activeView.value !== 'chat' || currentStep.value !== 'plans' || !hasFinalPlans.value)
const themeOptions = computed(() => [
  { key: 'animal', label: 'Animal-inspired', note: '局部借用 animal-island-vue' },
  { key: 'irasutoya', label: 'Irasutoya playful', note: '本地演示插画素材' },
  { key: 'scrapbook', label: 'Refined scrapbook', note: '当前手账方向加强版' }
])
const themeHero = computed(() => {
  if (isIrasutoyaTheme.value) {
    return {
      badge: 'いらすとや 演示素材',
      title: '用熟悉的日式插画，把出门计划做得更亲切',
      description: '欢迎区、协同页和记忆页直接用插画承接情绪，品牌和头像不再靠手写 SVG 撑场。',
      image: IRASUTOYA_IMAGES.welcome
    }
  }
  if (isAnimalTheme.value) {
    return {
      badge: 'Animal 演示模式',
      title: '把首页和卡片先切成更像游戏 UI 的动物风格',
      description: '只局部接入按钮、卡片和输入框，让你快速判断这条路线值不值得继续推进。',
      image: ''
    }
  }
  return {
    badge: 'AI 旅行小贴士',
    title: '把这次出门，做成一页轻松的小手账',
    description: '一句话告诉我所有人的需求，我会帮你查地点、排路线、订东西，出发当天继续守护。',
    image: ''
  }
})

const initialTheme = new URLSearchParams(window.location.search).get('theme')
if (DEMO_THEMES.includes(initialTheme)) demoTheme.value = initialTheme
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
      icon: '聊',
      title: '出行需求',
      summary: userText || '一句话告诉我需求',
      status: currentStep.value === 'need' ? 'current' : userText ? 'done' : 'pending'
    },
    {
      key: 'clarify',
      index: 2,
      icon: '补',
      title: '信息补齐',
      summary: clarificationText || (hasFinalPlans.value ? '已补齐关键信息' : '等待补齐'),
      status: currentStep.value === 'clarify' ? 'current' : hasFinalPlans.value || clarificationText ? 'done' : 'pending'
    },
    {
      key: 'plans',
      index: 3,
      icon: '看',
      title: '方案地图',
      summary: hasFinalPlans.value ? `${shownPlans.value.length} 套方案已生成` : '生成后展开查看',
      status: currentStep.value === 'plans' && hasFinalPlans.value ? 'current' : hasFinalPlans.value ? 'done' : 'pending'
    }
  ]
})

const quickPrompts = [
  { label: '家庭周末游', note: '亲子轻松半日', text: '今天下午有空，带老婆孩子出去玩，别离家太远，老婆最近在减肥。' },
  { label: '朋友 4 人小聚', note: '路线别太折腾', text: '今天晚上 7 点在上海静安寺附近，4 个朋友，预算 800 元，想先找一个有意思的地方再吃饭，路线不要太折腾。' },
  { label: '情侣约会', note: '安静又有氛围', text: '周六晚上想和对象在附近约会，预算 600，想要安静一点、有氛围感。' },
  { label: 'Citywalk 逛吃', note: '边逛边吃不赶路', text: '明天下午想在附近 Citywalk，边逛边吃，路线轻松一点。' }
]

const todayText = computed(() => {
  const now = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  return `${String(now.getMonth() + 1).padStart(2, '0')}.${String(now.getDate()).padStart(2, '0')} ${week} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
})

onMounted(() => {
  loadThreads()
  window.addEventListener('keydown', handleWindowKeydown)
  getMemory().then(data => {
    if (Array.isArray(data?.tags)) memoryTags.value = data.tags
  }).catch(() => {})
  getGuardStatus().then(data => {
    if (Array.isArray(data?.steps)) executionSteps.value = data.steps
  }).catch(() => {})
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleWindowKeydown)
})

function handleWindowKeydown(event) {
  if (event.key === 'Escape') {
    sidebarOpen.value = false
  }
}

function setDemoTheme(theme) {
  if (!DEMO_THEMES.includes(theme)) return
  demoTheme.value = theme
  const url = new URL(window.location.href)
  url.searchParams.set('theme', theme)
  window.history.replaceState({}, '', url)
}

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

async function loadThreads() {
  try {
    await ensureSession()
    historyThreads.value = await getHistoryThreads()
  } catch {
    historyThreads.value = []
  }
}

function resetConversation() {
  message.value = ''
  activeView.value = 'chat'
  currentStep.value = 'need'
  messages.value = []
  currentThreadId.value = ''
  currentPlanId.value = ''
  shownPlans.value = []
  activeMapRank.value = 1
  mapOrigin.value = {}
  clarification.value = {}
  clarificationAnswers.value = {}
  completedClarificationAnswers.value = {}
  selectedClarificationPreset.value = ''
  expandedRank.value = null
  selectedRank.value = null
  shareId.value = ''
}

function startNewThread() {
  resetConversation()
  sidebarOpen.value = false
}

function useSample(text) {
  startNewThread()
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
    const data = await createPlan({
      message: planningMessage,
      planCount: 3,
      stopCountPreference: '标准',
      ...(currentThreadId.value ? { threadId: currentThreadId.value } : {})
    })
    applyPlanResponse(data)
    replaceLoading(loadingId, {
      role: 'assistant',
      text: shownPlans.value.length ? '方案已生成，下面展开查看地图和路线。' : '',
      clarification: clarification.value,
      time: nowText()
    })
    message.value = ''
    await loadThreads()
  } catch (err) {
    replaceLoading(loadingId, {
      role: 'assistant',
      text: friendlyError(err),
      time: nowText()
    })
    await loadThreads()
  } finally {
    loading.value = false
    nextTick(scrollToBottom)
  }
}

async function submitClarification() {
  if (loading.value) return
  loading.value = true
  const answers = await buildClarificationAnswers()
  const summaryText = clarificationSummary(answers, clarification.value?.fields || [])
  messages.value.push({ id: crypto.randomUUID(), role: 'user', text: summaryText, time: nowText() })
  const loadingId = crypto.randomUUID()
  messages.value.push({ id: loadingId, role: 'assistant', loading: true, time: nowText() })
  try {
    await ensureSession()
    const data = await createPlan({
      message: summaryText,
      planCount: 3,
      stopCountPreference: '标准',
      clarificationAnswers: answers,
      previousPlanId: currentPlanId.value || null,
      threadId: currentThreadId.value || null
    })
    applyPlanResponse(data)
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
    await loadThreads()
  } catch (err) {
    replaceLoading(loadingId, { role: 'assistant', text: friendlyError(err), time: nowText() })
    await loadThreads()
  } finally {
    loading.value = false
    nextTick(scrollToBottom)
  }
}

function applyPlanResponse(data) {
  currentThreadId.value = data.threadId || currentThreadId.value
  currentPlanId.value = data.planId || ''
  shownPlans.value = normalizePlans(data.options || [])
  syncMapState(data, shownPlans.value)
  clarification.value = shownPlans.value.length ? {} : (data.clarification || {})
  selectedClarificationPreset.value = ''
  if (!shownPlans.value.length) {
    completedClarificationAnswers.value = {}
    currentStep.value = clarification.value?.fields?.length ? 'clarify' : 'need'
  } else {
    currentStep.value = 'plans'
  }
}

async function openThread(threadId) {
  try {
    await ensureSession()
    const detail = await getHistoryThread(threadId)
    const restored = restoreThreadState(detail, normalizePlans)
    currentThreadId.value = threadId
    currentPlanId.value = restored.currentPlanId
    shownPlans.value = restored.shownPlans
    clarification.value = restored.clarification
    currentStep.value = restored.currentStep
    activeView.value = restored.activeView
    mapOrigin.value = restored.mapOrigin
    messages.value = restored.messages
    executionSteps.value = restored.executionSteps.length ? restored.executionSteps : [...defaultExecutionSteps]
    clarificationAnswers.value = {}
    completedClarificationAnswers.value = {}
    selectedClarificationPreset.value = ''
    expandedRank.value = null
    selectedRank.value = null
    activeMapRank.value = restored.shownPlans[0]?.rank || 1
    sidebarOpen.value = false
    nextTick(scrollToBottom)
  } catch {
    startNewThread()
  }
}

async function renameThread(thread) {
  try {
    await ensureSession()
    await renameHistoryThread(thread.threadId, thread.title)
    await loadThreads()
  } catch {}
}

async function removeThread(thread) {
  if (!window.confirm(`确认删除“${thread.title}”吗？`)) return
  try {
    await ensureSession()
    await deleteHistoryThread(thread.threadId)
    if (currentThreadId.value === thread.threadId) {
      startNewThread()
    }
    await loadThreads()
  } catch {}
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
      const data = await confirmPlan(currentPlanId.value, rank)
      executionSteps.value = buildExecutionSteps(data.execution)
      await loadThreads()
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

function buildExecutionSteps(execution = {}) {
  const steps = []
  for (const order of execution.orders || []) {
    steps.push({ name: order.targetName || '执行事项', status: 'done' })
  }
  if (execution.gift?.targetName) {
    steps.push({ name: execution.gift.targetName, status: 'done' })
  }
  if (execution.shareMessage) {
    steps.push({ name: '分享消息已生成', status: 'done' })
  }
  return steps.length ? steps : [...defaultExecutionSteps]
}

function scrollToBottom() {
  document.querySelector('.message-list')?.scrollTo({ top: 99999, behavior: 'smooth' })
}

function friendlyError(err) {
  if (err?.status === 422) return '抱歉，暂时没有找到完全符合的方案，要不要扩大到 5km 内，或者放宽一点需求？'
  return '抱歉，刚刚网络有点小问题，要不要再试一次？'
}
</script>

<style scoped>
.chat-app {
  --topbar-h: 4.5rem;
  --panel-gap: 1rem;
  --content-max: min(70rem, calc(100vw - 3rem));
  --sidebar-w: clamp(20rem, 28vw, 23rem);
  --history-card-min-h: 8rem;
  --control-size: clamp(2.75rem, 4vw, 3rem);
  --ink: #6f4732;
  --ink-strong: #563321;
  --paper: #fff8eb;
  --paper-soft: #fffef8;
  --peach: #ffb892;
  --peach-deep: #ff9368;
  --blue: #8cb2ff;
  --blue-deep: #5d87ef;
  --yellow: #ffd87f;
  --mint: #a9dfbb;
  --rose: #ff9ab0;
  --line: rgba(111, 71, 50, .22);
  --panel-shadow: 0 16px 0 rgba(111, 71, 50, .07), 0 28px 38px rgba(111, 71, 50, .12);
  min-height: 100vh;
  min-height: 100svh;
  position: relative;
  overflow: hidden;
  color: var(--ink-strong);
  background:
    radial-gradient(circle at 12% 18%, rgba(255, 184, 146, .42), transparent 16%),
    radial-gradient(circle at 86% 12%, rgba(140, 178, 255, .34), transparent 16%),
    radial-gradient(circle at 82% 82%, rgba(255, 216, 127, .25), transparent 18%),
    linear-gradient(180deg, #fffef8 0%, #fff6e8 48%, #ffeeda 100%);
  padding: calc(var(--topbar-h) + 1.75rem) 1.5rem 7rem;
  font-family: "Trebuchet MS", "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif;
  font-size: 1rem;
}

.chat-app:has(.result-mode) {
  padding-bottom: 2rem;
}

.paper-doodles {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}

.doodle {
  position: absolute;
  border-radius: 999px;
  opacity: .9;
}

.doodle-peach {
  top: 5%;
  left: -4rem;
  width: 14rem;
  height: 6rem;
  background: rgba(255, 184, 146, .45);
  transform: rotate(-14deg);
}

.doodle-blue {
  top: 10%;
  right: 4%;
  width: 7rem;
  height: 7rem;
  background: rgba(140, 178, 255, .3);
}

.doodle-yellow {
  right: 14%;
  bottom: 12%;
  width: 10rem;
  height: 5rem;
  background: rgba(255, 216, 127, .38);
  transform: rotate(16deg);
}

.doodle-line {
  left: 8%;
  bottom: 8%;
  width: 10rem;
  height: .8rem;
  background: repeating-linear-gradient(90deg, rgba(111, 71, 50, .18) 0 12px, transparent 12px 20px);
  border-radius: 999px;
  transform: rotate(-8deg);
}

.scrapbook-panel {
  border: 3px solid var(--ink-strong);
  background: rgba(255, 250, 241, .92);
  box-shadow: var(--panel-shadow);
}

button,
input {
  font: inherit;
}

button {
  cursor: pointer;
  border: 2px solid var(--ink-strong);
  background: var(--paper-soft);
  color: var(--ink-strong);
  transition: transform .18s ease, box-shadow .18s ease, background-color .18s ease;
}

button:hover:not(:disabled),
button:focus-visible:not(:disabled) {
  transform: translateY(-2px) rotate(-.6deg);
  box-shadow: 0 8px 0 rgba(111, 71, 50, .08);
}

button:disabled {
  opacity: .5;
  cursor: not-allowed;
}

input {
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink-strong);
}

.topbar {
  position: fixed;
  z-index: 20;
  inset: 0 0 auto;
  height: var(--topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: .55rem max(1rem, calc((100vw - 58rem) / 2)) .75rem;
  border-radius: 0 0 1.8rem 1.8rem;
}

.topbar-left,
.meta {
  display: flex;
  align-items: center;
  gap: .75rem;
}

.history-toggle {
  width: var(--control-size);
  height: var(--control-size);
  border-radius: 1rem;
  display: grid;
  place-items: center;
  background: linear-gradient(180deg, #fffef8 0%, #ffe9cb 100%);
}

.history-toggle-icon {
  width: 1.35rem;
  height: 1.35rem;
}

.history-toggle[aria-label='关闭规划历史侧栏'] .history-toggle-icon {
  transform: scaleX(-1);
}

.brand {
  display: flex;
  align-items: center;
  gap: .85rem;
  border: 0;
  background: transparent;
  box-shadow: none;
  padding: 0;
  text-align: left;
}

.brand:hover,
.brand:focus-visible {
  transform: none;
  box-shadow: none;
}

.brand-mark,
.brand-mark svg {
  width: 3rem;
  height: 3rem;
  display: block;
}

.brand-copy strong {
  display: block;
  font-size: 1.25rem;
  line-height: 1;
}

.brand-copy small {
  display: block;
  margin-top: .2rem;
  color: #8f6a54;
  font-size: .875rem;
}

.date-sticker {
  border: 2px solid var(--ink-strong);
  border-radius: 999px;
  padding: .45rem .9rem;
  background: #fff0bf;
  color: var(--ink-strong);
  font-size: .875rem;
  font-weight: 800;
  transform: rotate(-2deg);
}

.avatar {
  width: 3rem;
  height: 3rem;
  border-radius: 1rem;
  padding: .1rem;
  background: linear-gradient(180deg, #fffef8 0%, #ffd6ba 100%);
}

.avatar svg {
  width: 100%;
  height: 100%;
  display: block;
}

.app-layout {
  position: relative;
  z-index: 2;
  width: var(--content-max);
  margin: 0 auto;
}

.theme-switcher {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 1rem;
  align-items: center;
  margin-bottom: 1rem;
  border-radius: 1.5rem;
  padding: .95rem 1rem;
}

.theme-switcher-copy {
  display: grid;
  gap: .25rem;
}

.theme-switcher-copy strong {
  font-size: 1rem;
}

.theme-switcher-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: .7rem;
}

.theme-chip {
  min-height: 4.2rem;
  border-radius: 1.1rem;
  padding: .7rem .85rem;
  display: grid;
  gap: .2rem;
  text-align: left;
}

.theme-chip strong {
  font-size: .92rem;
}

.theme-chip small {
  color: #8c6752;
  font-size: .78rem;
  line-height: 1.35;
}

.theme-chip.active {
  background: linear-gradient(180deg, #fff7ee 0%, #ffd8bf 100%);
}

.sidebar-mask {
  position: fixed;
  inset: 0;
  z-index: 23;
  border: 0;
  background: rgba(86, 51, 33, .18);
}

.chat-shell,
.page-panel {
  position: relative;
  z-index: 2;
  width: 100%;
}

.chat-shell.result-mode {
  width: min(68rem, 100%);
  animation: result-rise .36s ease both;
}

.empty-state {
  min-height: calc(100svh - 210px);
  display: grid;
  place-items: center;
}

.welcome-card {
  position: relative;
  width: min(54rem, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(220px, .9fr);
  gap: 1.5rem 1rem;
  border-radius: 2rem;
  padding: 2rem;
}

.animal-demo-shell {
  width: min(54rem, 100%);
}

.animal-welcome-card {
  padding: 1.25rem;
}

.animal-demo-copy {
  display: grid;
  gap: .8rem;
  margin-bottom: 1rem;
}

.animal-demo-copy h1 {
  margin: 0;
  font-size: clamp(1.7rem, 3vw, 2.2rem);
  line-height: 1.2;
}

.animal-demo-copy p {
  margin: 0;
  color: #66734a;
  line-height: 1.7;
}

.animal-demo-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: .75rem;
}

.irasutoya-welcome {
  grid-template-columns: minmax(0, 1.1fr) minmax(240px, 1fr);
}

.welcome-tape {
  position: absolute;
  top: -1rem;
  width: 5.25rem;
  height: 1.5rem;
  background: rgba(140, 178, 255, .34);
  border: 2px solid rgba(111, 71, 50, .16);
  border-radius: .5rem;
}

.tape-left {
  left: 1.75rem;
  transform: rotate(-8deg);
}

.tape-right {
  right: 1.75rem;
  background: rgba(255, 184, 146, .4);
  transform: rotate(9deg);
}

.welcome-copy {
  display: grid;
  align-content: center;
  gap: .75rem;
}

.welcome-badge,
.section-sticker,
.bubble-pin,
.clarify-badge {
  width: fit-content;
  border: 2px solid var(--ink-strong);
  border-radius: 999px;
  padding: .28rem .7rem;
  background: #fff0bf;
  color: var(--ink-strong);
  font-size: .8rem;
  font-weight: 900;
  letter-spacing: .02em;
}

.welcome-copy h1 {
  margin: 0;
  font-size: clamp(1.8rem, 4vw, 2.6rem);
  line-height: 1.12;
}

.welcome-copy p,
.page-panel p {
  margin: 0;
  color: #8c6752;
  font-size: 1rem;
  line-height: 1.7;
}

.welcome-mascot {
  display: grid;
  place-items: center;
  align-self: center;
}

.welcome-mascot svg {
  width: min(100%, 18rem);
  height: auto;
  animation: bob 4.4s ease-in-out infinite;
}

.welcome-irasutoya-art {
  display: grid;
  place-items: center;
}

.welcome-irasutoya-art img {
  width: min(100%, 21rem);
  height: auto;
  object-fit: contain;
}

.quick-prompts {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .9rem;
}

.prompt-sticker {
  min-height: 5.3rem;
  border-radius: 1.35rem;
  padding: 1rem 1.1rem;
  display: grid;
  gap: .35rem;
  text-align: left;
  background: linear-gradient(180deg, #fffef8 0%, #fff3dd 100%);
}

.prompt-sticker:nth-child(2n) {
  background: linear-gradient(180deg, #fffaf7 0%, #ffe3d6 100%);
}

.prompt-sticker:nth-child(3n) {
  background: linear-gradient(180deg, #fefeff 0%, #eaf1ff 100%);
}

.prompt-sticker strong {
  font-size: 1rem;
}

.prompt-sticker small {
  color: #8c6752;
  font-size: .875rem;
}

.step-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: stretch;
  margin-bottom: 1rem;
}

.step-card-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: .9rem;
}

.step-card {
  min-width: 0;
  min-height: 6.8rem;
  border-radius: 1.45rem;
  padding: 1rem;
  display: grid;
  grid-template-columns: 3rem minmax(0, 1fr);
  gap: .85rem;
  align-items: start;
  text-align: left;
  background: linear-gradient(180deg, #fffef8 0%, #fff3dc 100%);
}

.step-icon {
  width: 3rem;
  height: 3rem;
  display: grid;
  place-items: center;
  border-radius: 1rem;
  background: #ffd995;
  color: var(--ink-strong);
  font-size: 1rem;
  font-weight: 900;
}

.step-copy {
  min-width: 0;
  display: grid;
  gap: .22rem;
}

.step-copy em {
  font-style: normal;
  color: #8c6752;
  font-size: .78rem;
  font-weight: 800;
}

.step-copy strong {
  min-width: 0;
  font-size: 1rem;
}

.step-copy small {
  min-width: 0;
  color: #8c6752;
  font-size: .84rem;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.step-card.done {
  background: linear-gradient(180deg, #fffef8 0%, #eaf7ee 100%);
}

.step-card.done .step-icon {
  background: #a9dfbb;
}

.step-card.current {
  background: linear-gradient(180deg, #fffaf7 0%, #ffe3d6 100%);
  box-shadow: 0 14px 0 rgba(111, 71, 50, .06), 0 24px 30px rgba(255, 147, 104, .2);
}

.step-card.current .step-icon {
  background: var(--blue);
  color: #fff;
}

.step-card.pending {
  opacity: .8;
}

.summary-share,
.primary-button,
.pick-button,
.comment-box button {
  min-height: 3.2rem;
  border-radius: 1.15rem;
  padding: 0 1rem;
  background: linear-gradient(180deg, var(--blue) 0%, var(--blue-deep) 100%);
  color: #fff;
  font-weight: 900;
}

.summary-share {
  min-width: 9.4rem;
}

.result-workspace {
  display: grid;
  grid-template-columns: minmax(23rem, clamp(24rem, 35vw, 29rem)) minmax(0, 1fr);
  gap: 1.5rem;
  align-items: start;
}

.result-plans {
  max-height: min(38rem, calc(100svh - 13rem));
  overflow: auto;
  padding-right: .25rem;
  display: grid;
  gap: 1rem;
}

.result-plans::-webkit-scrollbar {
  width: .55rem;
}

.result-plans::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(111, 71, 50, .18);
}

.plan-card {
  position: relative;
  border: 3px solid var(--ink-strong);
  border-radius: 1.65rem;
  padding: 1.35rem 1.25rem 1.2rem;
  background: linear-gradient(180deg, #fffef8 0%, #fff3df 100%);
  cursor: pointer;
  box-shadow: 0 14px 0 rgba(111, 71, 50, .06), 0 22px 28px rgba(111, 71, 50, .1);
}

.plan-card.picked {
  background: linear-gradient(180deg, #fff7ee 0%, #ffe1cf 100%);
}

.plan-card.active {
  transform: translateY(-2px) rotate(-.45deg);
  box-shadow: 0 16px 0 rgba(111, 71, 50, .08), 0 28px 34px rgba(140, 178, 255, .28);
}

.plan-ribbon {
  position: absolute;
  top: -.8rem;
  left: 1.2rem;
  border: 2px solid var(--ink-strong);
  border-radius: .8rem;
  padding: .28rem .7rem;
  background: #ffd995;
  font-size: .78rem;
  font-weight: 900;
}

.plan-card header {
  display: flex;
  justify-content: space-between;
  gap: .8rem;
  align-items: flex-start;
}

.plan-heading {
  min-width: 0;
  display: grid;
  gap: .25rem;
}

.plan-heading strong {
  font-size: 1.2rem;
  line-height: 1.2;
}

.plan-heading small {
  color: #8c6752;
  font-size: .82rem;
}

.plan-tag {
  flex: none;
  max-width: 7.5rem;
  border: 2px solid var(--ink-strong);
  border-radius: 1rem;
  padding: .38rem .7rem;
  background: #a9dfbb;
  font-size: .8rem;
  font-weight: 900;
  line-height: 1.2;
  text-align: center;
}

.timeline-line {
  margin: 1rem 0 !important;
  color: #725542;
  font-size: 1rem !important;
  line-height: 1.65;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.plan-meta {
  display: flex;
  flex-wrap: wrap;
  gap: .65rem;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: .4rem;
  border: 2px solid var(--ink-strong);
  border-radius: 999px;
  padding: .38rem .75rem;
  background: rgba(255, 255, 255, .82);
  color: #725542;
  font-size: .84rem;
  font-weight: 800;
}

.meta-chip svg {
  width: 1rem;
  height: 1rem;
  flex: none;
}

.meta-chip.money { color: #c55b2f; }
.meta-chip.time { color: #557be6; }
.meta-chip.distance { color: #499060; }

.plan-card footer {
  display: flex;
  justify-content: flex-end;
  gap: .75rem;
  margin-top: 1rem;
}

.plan-card footer button {
  min-height: 2.9rem;
  border-radius: 1rem;
  padding: 0 .9rem;
  background: rgba(255, 255, 255, .82);
}

.plan-card footer .pick-button {
  background: linear-gradient(180deg, var(--peach) 0%, var(--peach-deep) 100%);
  color: #fff;
}

.plan-detail {
  margin-top: .95rem;
  padding-top: .95rem;
  border-top: 2px dashed rgba(111, 71, 50, .18);
  color: #8c6752;
}

.plan-detail p {
  margin: 0 0 .5rem;
  font-size: .92rem;
  line-height: 1.6;
}

.result-map {
  min-width: 0;
}

.result-map :deep(.trip-map-panel) {
  margin-top: 0;
}

.message-list {
  height: calc(100svh - 190px);
  overflow: auto;
  list-style: none;
  margin: 0;
  padding: .2rem 0 1rem;
  display: grid;
  gap: 1rem;
}

.message-row {
  display: flex;
  gap: .8rem;
  align-items: flex-start;
}

.message-row.user {
  justify-content: flex-end;
}

.bubble-avatar,
.assistant-dot {
  width: 3rem;
  height: 3rem;
  flex: none;
}

.bubble-avatar svg,
.assistant-dot svg {
  width: 100%;
  height: 100%;
  display: block;
}

.bubble {
  position: relative;
  max-width: min(44rem, calc(100% - 4rem));
  border: 3px solid var(--ink-strong);
  border-radius: 1.6rem;
  padding: 1.1rem 1rem 1rem;
  background: linear-gradient(180deg, #fffef8 0%, #fff4df 100%);
  box-shadow: 0 14px 0 rgba(111, 71, 50, .06), 0 18px 26px rgba(111, 71, 50, .1);
}

.message-row.user .bubble {
  background: linear-gradient(180deg, #fffaf7 0%, #ffe1cf 100%);
}

.bubble-pin {
  margin-bottom: .7rem;
}

.bubble time {
  float: right;
  margin-left: .7rem;
  color: #9d7b68;
  font-size: .8rem;
  font-weight: 700;
}

.bubble p {
  margin: 0;
  font-size: 1rem;
  line-height: 1.75;
}

.typing {
  margin-top: .35rem;
  color: #8c6752;
  display: flex;
  align-items: center;
  gap: .35rem;
}

.typing span {
  width: .38rem;
  height: .38rem;
  border-radius: 50%;
  background: var(--peach-deep);
  animation: blink 1s ease-in-out infinite;
}

.typing span:nth-child(2) { animation-delay: .16s; }
.typing span:nth-child(3) { animation-delay: .32s; }

.clarify-card {
  display: grid;
  gap: 1rem;
  width: min(44rem, 100%);
  margin-top: .9rem;
}

.clarify-head {
  display: grid;
  gap: .55rem;
}

.clarify-head strong {
  font-size: 1.08rem;
  line-height: 1.5;
}

.clarify-layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr);
  gap: 1rem;
}

.clarify-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .85rem;
}

.clarify-card label {
  display: grid;
  gap: .42rem;
}

.clarify-card label span {
  color: #8c6752;
  font-size: .84rem;
  font-weight: 700;
}

.clarify-card input,
.comment-box input,
.command-input input {
  width: 100%;
}

.clarify-card input,
.comment-box input,
.command-input {
  border: 2px solid var(--ink-strong);
  border-radius: 1rem;
  background: rgba(255, 255, 255, .82);
}

.clarify-card input,
.comment-box input {
  min-height: 3rem;
  padding: 0 .9rem;
}

.clarify-card input:focus,
.comment-box input:focus,
.command-input:focus-within {
  box-shadow: 0 0 0 4px rgba(140, 178, 255, .24);
}

.clarify-submit {
  grid-column: 1 / -1;
}

.clarify-presets {
  display: grid;
  gap: .75rem;
}

.preset-card {
  position: relative;
  min-height: 5.2rem;
  display: grid;
  gap: .45rem;
  align-content: center;
  border-radius: 1.15rem;
  padding: .95rem 1rem;
  background: linear-gradient(180deg, #fffef8 0%, #ffeeda 100%);
}

.preset-card strong {
  display: flex;
  align-items: center;
  gap: .55rem;
  font-size: .95rem;
}

.preset-card strong b {
  display: inline-grid;
  place-items: center;
  width: 1.65rem;
  height: 1.65rem;
  border-radius: .7rem;
  background: #ffd995;
  color: var(--ink-strong);
  font-size: .78rem;
}

.preset-card small {
  color: #8c6752;
  font-size: .82rem;
  line-height: 1.4;
}

.preset-card.selected {
  background: linear-gradient(180deg, #fff7ee 0%, #ffe3d6 100%);
}

.preset-check {
  position: absolute;
  top: .55rem;
  right: .55rem;
  width: 1.25rem;
  height: 1.25rem;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--blue-deep);
  color: #fff;
  font-size: .72rem;
  font-weight: 900;
  opacity: 0;
}

.preset-card.selected .preset-check {
  opacity: 1;
}

.page-panel {
  margin-top: 1.2rem;
  border-radius: 1.8rem;
  padding: 1.5rem;
}

.page-panel > header {
  display: grid;
  gap: .5rem;
  margin-bottom: 1rem;
}

.page-panel h1 {
  margin: 0;
  font-size: 1.35rem;
}

.shared-plans {
  display: grid;
  gap: 1rem;
}

.vote-card {
  background: linear-gradient(180deg, #fffef8 0%, #fff0e0 100%);
}

.comment-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 7rem;
  gap: .8rem;
  margin-top: 1rem;
}

.comments {
  list-style: none;
  margin: 1rem 0 0;
  padding: 0;
  display: grid;
  gap: .75rem;
}

.comments li {
  border: 2px solid var(--ink-strong);
  border-radius: 1rem;
  padding: .9rem 1rem;
  background: rgba(255, 255, 255, .82);
}

.comments span {
  display: inline-block;
  margin-bottom: .35rem;
  color: #557be6;
  font-weight: 900;
}

.comments p {
  margin: 0;
  color: #8c6752;
}

.panel-illustration {
  display: grid;
  place-items: center;
  margin-bottom: 1rem;
}

.panel-illustration img {
  width: min(100%, 17rem);
  height: auto;
  object-fit: contain;
}

.memory-illustration img {
  width: min(100%, 14rem);
}

.execution-panel ol {
  list-style: none;
  margin: 1.2rem 0;
  padding: 0;
  display: grid;
  gap: .85rem;
}

.execution-panel li {
  border: 2px solid var(--ink-strong);
  border-radius: 1rem;
  padding: .95rem 1rem;
  background: rgba(255, 255, 255, .82);
}

.execution-panel li span {
  display: inline-grid;
  place-items: center;
  width: 1.6rem;
  height: 1.6rem;
  margin-right: .55rem;
  border-radius: 999px;
  background: #ffd995;
  color: var(--ink-strong);
  font-weight: 900;
}

.execution-panel li.done span {
  background: #a9dfbb;
}

.execution-panel li.doing span {
  background: #8cb2ff;
  color: #fff;
}

.memory-grid {
  display: flex;
  flex-wrap: wrap;
  gap: .75rem;
  margin-top: 1rem;
}

.memory-grid span {
  border: 2px solid var(--ink-strong);
  border-radius: 999px;
  padding: .5rem .9rem;
  background: rgba(255, 255, 255, .85);
  color: #725542;
  font-weight: 800;
}

.composer {
  position: fixed;
  z-index: 20;
  left: 50%;
  bottom: .85rem;
  width: min(56rem, calc(100vw - 2rem));
  transform: translateX(-50%);
  border-radius: 1.75rem;
  padding: .8rem;
  display: grid;
  grid-template-columns: 3rem minmax(0, 1fr) 5rem 5rem 5.8rem;
  gap: .75rem;
  align-items: center;
}

.command-input {
  min-height: 3.6rem;
  padding: .5rem .85rem;
  display: grid;
  grid-template-rows: auto 1fr;
  gap: .12rem;
}

.animal-demo-input {
  align-self: stretch;
}

.theme-animal {
  --ink: #725d42;
  --ink-strong: #725542;
  --paper: #f7f3df;
  --paper-soft: #f8f8f0;
  --peach: #f8f8f0;
  --peach-deep: #e9d8ba;
  --blue: #8fd3ca;
  --blue-deep: #58baa9;
  --yellow: #f7d46b;
  --mint: #95cf88;
  --rose: #f29ca3;
  --line: rgba(114, 93, 66, .2);
  --panel-shadow: 0 14px 0 rgba(168, 152, 120, .22), 0 24px 34px rgba(61, 52, 40, .12);
  background:
    radial-gradient(circle at 10% 14%, rgba(245, 211, 107, .28), transparent 16%),
    radial-gradient(circle at 84% 16%, rgba(143, 211, 202, .24), transparent 18%),
    linear-gradient(180deg, #f7f5e8 0%, #f2ebd9 100%);
  font-family: Nunito, "Noto Sans SC", "Zen Maru Gothic", "HarmonyOS Sans SC", "MiSans", "Microsoft YaHei", sans-serif;
}

.theme-animal .paper-doodles {
  opacity: .45;
}

.theme-animal .scrapbook-panel,
.theme-animal .welcome-card,
.theme-animal .step-card,
.theme-animal .plan-card,
.theme-animal .bubble,
.theme-animal .page-panel,
.theme-animal .composer,
.theme-animal .history-sidebar,
.theme-animal :deep(.trip-map-panel) {
  border-width: 2px;
  border-color: #a89878;
  background: rgba(248, 248, 240, .94);
  box-shadow: 0 8px 0 rgba(189, 174, 160, .7), 0 18px 28px rgba(61, 52, 40, .08);
}

.theme-animal .topbar {
  background: rgba(248, 248, 240, .92);
}

.theme-animal button {
  border-width: 2px;
  border-color: #aaa69d;
  border-radius: 1.4rem;
}

.theme-animal button:hover:not(:disabled),
.theme-animal button:focus-visible:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 0 rgba(189, 174, 160, .9);
}

.theme-animal .theme-chip.active,
.theme-animal .history-toggle,
.theme-animal .avatar,
.theme-animal .date-sticker,
.theme-animal .prompt-sticker,
.theme-animal .tool-button,
.theme-animal .command-input,
.theme-animal .plan-card footer button,
.theme-animal .meta-chip,
.theme-animal .memory-grid span,
.theme-animal .comments li,
.theme-animal .execution-panel li,
.theme-animal .clarify-card input,
.theme-animal .comment-box input {
  border-color: #c4b89e;
  background: #fdfbf2;
  box-shadow: 0 4px 0 rgba(212, 201, 180, .9);
}

.theme-animal .theme-chip.active,
.theme-animal .step-card.current,
.theme-animal .message-row.user .bubble,
.theme-animal .plan-card.picked,
.theme-animal .tool-button.active {
  background: linear-gradient(180deg, #fffef8 0%, #f7ead4 100%);
}

.theme-animal .summary-share,
.theme-animal .primary-button,
.theme-animal .pick-button,
.theme-animal .comment-box button,
.theme-animal .plan-button {
  border-color: #f8f8f0;
  background: linear-gradient(180deg, #f8f8f0 0%, #efe6d4 100%);
  color: #725542;
  box-shadow: 0 5px 0 #bdaea0;
}

.theme-animal .summary-share:hover:not(:disabled),
.theme-animal .primary-button:hover:not(:disabled),
.theme-animal .pick-button:hover:not(:disabled),
.theme-animal .comment-box button:hover:not(:disabled),
.theme-animal .plan-button:hover:not(:disabled) {
  box-shadow: 0 6px 0 #bdaea0;
}

.theme-animal .welcome-badge,
.theme-animal .section-sticker,
.theme-animal .bubble-pin,
.theme-animal .clarify-badge,
.theme-animal .history-pin,
.theme-animal .plan-ribbon {
  background: #f6dd88;
  border-color: #aaa69d;
}

.theme-animal .step-icon,
.theme-animal .preset-card strong b,
.theme-animal .execution-panel li span {
  background: #95cf88;
  color: #fff;
}

.theme-animal .plan-tag,
.theme-animal .comments span {
  background: #8fd3ca;
  color: #725542;
}

.theme-animal .quick-prompts {
  gap: 1rem;
}

.theme-animal .animal-demo-shell {
  max-width: 58rem;
}

.theme-animal .animal-welcome-card {
  border-radius: 2rem;
  padding: 1.6rem;
}

.theme-animal .animal-demo-copy h1 {
  font-size: clamp(2rem, 3.4vw, 2.7rem);
  color: #725542;
}

.theme-animal .animal-demo-copy p {
  color: #8b7b61;
}

.theme-animal .animal-demo-prompts .animal-btn {
  min-width: 10.5rem;
}

.theme-animal .result-workspace {
  gap: 1.25rem;
}

.theme-animal .bubble {
  border-radius: 1.8rem 1.8rem 1.3rem 1.8rem;
}

.theme-animal .message-row.user .bubble {
  border-radius: 1.8rem 1.8rem 1.8rem 1.3rem;
}

.theme-irasutoya {
  --ink: #7a5a3c;
  --ink-strong: #5b412c;
  --paper: #fff9f1;
  --paper-soft: #fffdfa;
  --peach: #ffba94;
  --peach-deep: #ef8f67;
  --blue: #8bbde8;
  --blue-deep: #6396da;
  --yellow: #ffe07a;
  --mint: #b8df90;
  --rose: #ffb2bc;
  --line: rgba(91, 65, 44, .16);
  --panel-shadow: 0 14px 0 rgba(238, 190, 139, .22), 0 24px 34px rgba(139, 164, 208, .12);
  background:
    radial-gradient(circle at 14% 14%, rgba(255, 226, 155, .46), transparent 16%),
    radial-gradient(circle at 86% 18%, rgba(151, 205, 255, .36), transparent 16%),
    radial-gradient(circle at 18% 88%, rgba(255, 186, 148, .3), transparent 14%),
    linear-gradient(180deg, #fffef8 0%, #fff6e6 48%, #fff0dd 100%);
  font-family: "Hiragino Maru Gothic ProN", "Yu Gothic", "Meiryo", "Microsoft YaHei", sans-serif;
}

.theme-irasutoya .paper-doodles {
  opacity: .7;
}

.theme-irasutoya .scrapbook-panel,
.theme-irasutoya .welcome-card,
.theme-irasutoya .step-card,
.theme-irasutoya .plan-card,
.theme-irasutoya .bubble,
.theme-irasutoya .page-panel,
.theme-irasutoya .composer,
.theme-irasutoya .history-sidebar,
.theme-irasutoya :deep(.trip-map-panel) {
  border-color: rgba(91, 65, 44, .78);
  background: rgba(255, 253, 247, .94);
  box-shadow: 0 12px 0 rgba(255, 215, 157, .38), 0 22px 34px rgba(137, 170, 219, .12);
}

.theme-irasutoya .topbar {
  background: rgba(255, 252, 246, .92);
}

.theme-irasutoya .theme-switcher {
  position: relative;
  overflow: hidden;
}

.theme-irasutoya .theme-switcher::after {
  content: '';
  position: absolute;
  right: 1.2rem;
  top: .8rem;
  width: 4.8rem;
  height: 4.8rem;
  background: radial-gradient(circle, rgba(255, 224, 122, .45) 0 40%, transparent 41%);
  opacity: .8;
  pointer-events: none;
}

.theme-irasutoya .irasutoya-welcome {
  background:
    linear-gradient(180deg, rgba(255, 251, 239, .98) 0%, rgba(255, 240, 221, .98) 100%);
}

.theme-irasutoya .welcome-irasutoya-art img,
.theme-irasutoya .panel-illustration img {
  filter: drop-shadow(0 12px 20px rgba(122, 90, 60, .12));
}

.theme-irasutoya .quick-prompts {
  gap: 1rem;
}

.theme-irasutoya .prompt-sticker {
  background: linear-gradient(180deg, #fffefb 0%, #fff1de 100%);
}

.theme-irasutoya .theme-chip.active,
.theme-irasutoya .step-card.current,
.theme-irasutoya .message-row.user .bubble,
.theme-irasutoya .plan-card.picked,
.theme-irasutoya .tool-button.active {
  background: linear-gradient(180deg, #fff8f2 0%, #ffe4d2 100%);
}

.theme-irasutoya .plan-tag,
.theme-irasutoya .step-card.done .step-icon,
.theme-irasutoya .comments span,
.theme-irasutoya .execution-panel li.done span {
  background: #b8df90;
}

.theme-irasutoya .step-icon,
.theme-irasutoya .execution-panel li span {
  background: #8bbde8;
  color: #fff;
}

.theme-irasutoya .welcome-badge,
.theme-irasutoya .section-sticker,
.theme-irasutoya .bubble-pin,
.theme-irasutoya .clarify-badge,
.theme-irasutoya .history-pin,
.theme-irasutoya .plan-ribbon,
.theme-irasutoya .date-sticker {
  background: #ffe07a;
}

.theme-irasutoya .panel-illustration {
  padding: .5rem 0 .2rem;
}

.theme-irasutoya .irasutoya-panel {
  background-image: radial-gradient(circle at 92% 10%, rgba(255, 224, 122, .2), transparent 18%);
}

.input-tag {
  color: #8c6752;
  font-size: .76rem;
  font-weight: 900;
}

.command-input input {
  padding: 0;
}

.tool-button,
.plan-button {
  min-height: 3.6rem;
  border-radius: 1rem;
  display: grid;
  place-items: center;
  gap: .15rem;
  padding: .35rem .4rem;
}

.tool-button {
  background: linear-gradient(180deg, #fffef8 0%, #ffecc9 100%);
}

.tool-button.active {
  background: linear-gradient(180deg, #fff7ee 0%, #ffd4be 100%);
}

.tool-button svg {
  width: 1.05rem;
  height: 1.05rem;
}

.tool-button span {
  font-size: .78rem;
  font-weight: 900;
}

.plan-button {
  background: linear-gradient(180deg, var(--peach) 0%, var(--peach-deep) 100%);
  color: #fff;
}

.hidden-file {
  display: none;
}

@media (max-width: 1023px) {
  .chat-app {
    --content-max: min(100%, calc(100vw - 2rem));
  }

  .welcome-card,
  .result-workspace {
    grid-template-columns: 1fr;
  }

  .theme-switcher {
    grid-template-columns: 1fr;
  }

  .welcome-mascot {
    order: -1;
  }
}

@media (max-width: 767px) {
  .chat-app {
    --content-max: 100%;
    --sidebar-w: min(calc(100vw - 1.5rem), 21rem);
    --history-card-min-h: 7rem;
    padding: calc(var(--topbar-h) + .8rem) .75rem 8rem;
    font-size: .9375rem;
  }

  .topbar {
    padding-inline: .75rem;
  }

  .brand-copy small,
  .date-sticker {
    display: none;
  }

  .welcome-card {
    padding: 1.35rem;
    border-radius: 1.6rem;
  }

  .theme-switcher-tabs,
  .quick-prompts,
  .step-card-list,
  .clarify-layout,
  .clarify-form,
  .step-summary,
  .result-workspace,
  .comment-box {
    grid-template-columns: 1fr;
  }

  .message-list {
    height: calc(100svh - 210px);
  }

  .bubble {
    max-width: calc(100% - 3.5rem);
  }

  .plan-card footer {
    justify-content: stretch;
    flex-wrap: wrap;
  }

  .plan-card footer button {
    flex: 1 1 10rem;
  }

  .result-plans {
    max-height: none;
    overflow: visible;
    padding-right: 0;
  }

  .composer {
    width: calc(100vw - 1rem);
    grid-template-columns: 2.7rem minmax(0, 1fr) 4.4rem 4.4rem 5rem;
    gap: .45rem;
    padding: .65rem;
  }

  .tool-button span {
    font-size: .72rem;
  }
}

@keyframes blink {
  0%, 100% { opacity: .25; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-2px); }
}

@keyframes bob {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  50% { transform: translateY(-8px) rotate(-2deg); }
}

@keyframes result-rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
