<template>
  <aside ref="rootRef" :class="['history-sidebar glass', { open: open }]">
    <div class="history-head">
      <strong>规划历史</strong>
      <button
        type="button"
        class="history-new"
        aria-label="新建对话"
        @click="$emit('new-thread')"
      >
        新对话
      </button>
    </div>

    <div v-if="!threads.length" class="history-empty">
      <strong>还没有历史</strong>
      <p>开始一次新的出行规划后，会在这里出现。</p>
    </div>

    <ol v-else class="history-list">
      <li
        v-for="thread in threads"
        :key="thread.threadId"
        :class="['history-row', { active: selectedThreadId === thread.threadId, menuing: menuThreadId === thread.threadId }]"
      >
        <button type="button" class="history-item" @click="$emit('open-thread', thread.threadId)">
          <div class="history-title-row">
            <strong>{{ thread.title }}</strong>
            <span>{{ statusText(thread.lastStatus) }}</span>
          </div>
          <p>{{ thread.lastMessagePreview || '暂无消息' }}</p>
          <small>{{ formatTime(thread.lastMessageAt) }}</small>
        </button>

        <button
          type="button"
          class="history-more"
          :aria-expanded="menuThreadId === thread.threadId ? 'true' : 'false'"
          aria-label="更多操作"
          @click.stop="toggleMenu(thread.threadId)"
        >
          <span></span><span></span><span></span>
        </button>

        <div v-if="menuThreadId === thread.threadId" class="history-menu" role="menu">
          <button type="button" role="menuitem" @click.stop="renameThread(thread)">重命名</button>
          <button type="button" role="menuitem" class="danger" @click.stop="deleteThread(thread)">删除</button>
        </div>
      </li>
    </ol>
  </aside>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps({
  threads: { type: Array, default: () => [] },
  selectedThreadId: { type: String, default: '' },
  open: { type: Boolean, default: false }
})

const emit = defineEmits(['new-thread', 'open-thread', 'rename-thread', 'delete-thread'])

const rootRef = ref(null)
const menuThreadId = ref('')

onMounted(() => {
  window.addEventListener('click', handleWindowClick)
  window.addEventListener('keydown', handleWindowKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', handleWindowClick)
  window.removeEventListener('keydown', handleWindowKeydown)
})

function handleWindowClick(event) {
  if (!menuThreadId.value) return
  if (rootRef.value?.contains(event.target)) return
  menuThreadId.value = ''
}

function handleWindowKeydown(event) {
  if (event.key === 'Escape') {
    menuThreadId.value = ''
  }
}

function toggleMenu(threadId) {
  menuThreadId.value = menuThreadId.value === threadId ? '' : threadId
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function statusText(status) {
  return {
    NEEDS_CLARIFICATION: '澄清中',
    READY: '已出方案',
    COMPLETED: '已执行',
    ERROR: '出错'
  }[status] || '进行中'
}

function renameThread(thread) {
  menuThreadId.value = ''
  const nextTitle = window.prompt('输入新的历史标题', thread.title)
  if (!nextTitle || nextTitle.trim() === thread.title) return
  emit('rename-thread', { ...thread, title: nextTitle.trim() })
}

function deleteThread(thread) {
  menuThreadId.value = ''
  emit('delete-thread', thread)
}
</script>

<style scoped>
.history-sidebar {
  position: fixed;
  z-index: 24;
  top: calc(var(--topbar-h) + var(--panel-gap));
  left: var(--panel-gap);
  width: var(--sidebar-w);
  max-height: calc(100svh - var(--topbar-h) - (var(--panel-gap) * 2));
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: .875rem;
  border-radius: 1.25rem;
  padding: 1rem;
  overflow: hidden;
  container-type: inline-size;
  transform: translateX(calc(-100% - var(--panel-gap)));
  opacity: 0;
  pointer-events: none;
  transition: transform .22s ease, opacity .18s ease;
}

.history-sidebar.open {
  transform: translateX(0);
  opacity: 1;
  pointer-events: auto;
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: .75rem;
}

.history-head strong {
  font-size: 1.125rem;
  font-weight: 900;
}

.history-new {
  min-height: 2.125rem;
  border-radius: .625rem;
  padding: 0 .75rem;
  background: #eef3f8;
  color: #334155;
  font-size: .8125rem;
  font-weight: 800;
}

.history-empty {
  border-radius: 1rem;
  padding: 1.125rem 1rem;
  background: rgba(255,255,255,.88);
  color: #334155;
}

.history-empty strong {
  display: block;
  font-size: .9375rem;
}

.history-empty p {
  margin: .5rem 0 0;
  color: #64748b;
  font-size: .8125rem;
  line-height: 1.5;
}

.history-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: auto;
  display: grid;
  gap: .625rem;
}

.history-row {
  position: relative;
}

.history-item {
  width: 100%;
  min-height: var(--history-card-min-h);
  border-radius: .875rem;
  padding: .875rem 3rem .875rem .875rem;
  background: rgba(255,255,255,.92);
  text-align: left;
  box-shadow: 0 10px 24px rgba(91, 106, 150, .10);
  border: 1px solid rgba(226, 232, 240, .78);
  transition: border-color .18s ease, box-shadow .18s ease;
  display: grid;
  align-content: start;
  gap: .5rem;
}

.history-row.active .history-item {
  border-color: rgba(22, 93, 255, .36);
  box-shadow: 0 14px 30px rgba(22, 93, 255, .14);
}

.history-title-row {
  display: flex;
  align-items: flex-start;
  gap: .5rem;
  min-width: 0;
}

.history-title-row strong {
  min-width: 0;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: .9375rem;
  line-height: 1.35;
  font-weight: 900;
}

.history-title-row span {
  flex: none;
  border-radius: 999px;
  padding: .1875rem .5rem;
  background: rgba(22, 93, 255, .10);
  color: #165dff;
  font-size: .6875rem;
  line-height: 1.2;
  font-weight: 900;
  max-width: min(5.25rem, 28%);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item p {
  color: #334155;
  font-size: .8125rem;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-item small {
  display: block;
  margin-top: .125rem;
  color: #94a3b8;
  font-size: .75rem;
}

.history-more {
  position: absolute;
  top: .625rem;
  right: .625rem;
  width: 1.875rem;
  height: 1.875rem;
  border-radius: .625rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: .1875rem;
  background: rgba(238, 243, 248, .92);
  opacity: .12;
  transition: opacity .16s ease, background-color .16s ease;
}

.history-row:hover .history-more,
.history-row.active .history-more,
.history-row.menuing .history-more,
.history-more:focus-visible {
  opacity: 1;
}

.history-more span {
  width: .1875rem;
  height: .1875rem;
  border-radius: 999px;
  background: #5b6476;
}

.history-menu {
  position: absolute;
  top: 2.625rem;
  right: .625rem;
  min-width: 7.25rem;
  display: grid;
  gap: .25rem;
  border-radius: .75rem;
  padding: .375rem;
  background: rgba(255,255,255,.96);
  box-shadow: 0 18px 34px rgba(15, 23, 42, .16);
  border: 1px solid rgba(226, 232, 240, .92);
}

.history-menu button {
  min-height: 2rem;
  border-radius: .5rem;
  padding: 0 .625rem;
  background: transparent;
  color: #334155;
  text-align: left;
  font-size: .8125rem;
  font-weight: 700;
}

.history-menu button:hover,
.history-menu button:focus-visible {
  background: #eef3f8;
}

.history-menu .danger {
  color: #b42318;
}

@container (max-width: 22rem) {
  .history-item {
    padding-right: 2.75rem;
  }

  .history-title-row {
    align-items: flex-start;
  }

  .history-title-row span {
    max-width: 4.5rem;
  }
}

@media (min-width: 768px) and (max-width: 1023px) {
  .history-sidebar {
    left: 1rem;
  }
}

@media (max-width: 767px) {
  .history-sidebar {
    left: .75rem;
  }
}
</style>
