<template>
  <aside ref="rootRef" :class="['history-sidebar', { open: open }]">
    <div class="history-head">
      <div>
        <strong>规划历史</strong>
        <p>最近的出行对话和方案状态</p>
      </div>
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
      <p>开始一次新的出行规划后，这里会记录最近的对话和方案。</p>
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
            <span class="history-status">
              <i aria-hidden="true"></i>
              {{ statusText(thread.lastStatus) }}
            </span>
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
  border: 1px solid rgba(114, 85, 66, .16);
  border-radius: 1rem;
  padding: 1rem;
  background: rgba(255, 253, 247, .96);
  box-shadow: 0 16px 36px rgba(61, 52, 40, .12);
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
  display: block;
  color: #5f4837;
  font-size: 1.08rem;
  font-weight: 900;
}

.history-head p {
  margin: .2rem 0 0;
  color: #8f725e;
  font-size: .78rem;
  line-height: 1.35;
}

.history-new {
  flex: none;
  min-height: 2.5rem;
  border: 1px solid rgba(114, 85, 66, .16);
  border-radius: .875rem;
  padding: 0 .9rem;
  background: rgba(255, 255, 255, .72);
  color: #725542;
  font-size: .82rem;
  font-weight: 900;
  box-shadow: 0 3px 0 rgba(201, 186, 164, .38);
}

.history-new:hover,
.history-new:focus-visible {
  background: rgba(255, 255, 255, .9);
}

.history-empty {
  border: 1px solid rgba(114, 85, 66, .12);
  border-radius: .875rem;
  padding: 1rem;
  background: rgba(255,255,255,.72);
  color: #5f4837;
}

.history-empty strong {
  display: block;
  font-size: .96rem;
}

.history-empty p {
  margin: .5rem 0 0;
  color: #8f725e;
  font-size: .8125rem;
  line-height: 1.55;
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
  border: 1px solid rgba(114, 85, 66, .14);
  border-radius: .875rem;
  padding: .875rem 2.85rem .875rem .875rem;
  background: rgba(255, 255, 255, .76);
  text-align: left;
  box-shadow: 0 8px 18px rgba(61, 52, 40, .06);
  transition: transform .18s ease, box-shadow .18s ease, background-color .18s ease, border-color .18s ease;
  display: grid;
  align-content: start;
  gap: .45rem;
}

.history-row.active .history-item {
  border-color: #bdaea0;
  background: linear-gradient(180deg, #fffef8 0%, #f7ead4 100%);
  box-shadow: 0 10px 20px rgba(61, 52, 40, .08);
  transform: translateY(-1px);
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
  font-size: .98rem;
  line-height: 1.35;
  font-weight: 900;
}

.history-title-row span {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: .28rem;
  color: #725542;
  font-size: .72rem;
  line-height: 1.2;
  font-weight: 800;
  max-width: min(5.25rem, 28%);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-status i {
  width: .45rem;
  height: .45rem;
  border-radius: 999px;
  background: #95cf88;
  box-shadow: 0 0 0 3px rgba(149, 207, 136, .16);
}

.history-item p {
  color: #806450;
  font-size: .8125rem;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-item small {
  display: block;
  margin-top: .125rem;
  color: #9d7b68;
  font-size: .75rem;
  font-weight: 700;
}

.history-more {
  position: absolute;
  top: .75rem;
  right: .75rem;
  width: 2rem;
  height: 2rem;
  border-radius: .7rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: .1875rem;
  border: 1px solid rgba(114, 85, 66, .14);
  background: rgba(255,255,255,.88);
  opacity: .2;
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
  background: #725542;
}

.history-menu {
  position: absolute;
  top: 2.8rem;
  right: .75rem;
  min-width: 7.25rem;
  display: grid;
  gap: .25rem;
  border-radius: .875rem;
  padding: .375rem;
  border: 1px solid rgba(114, 85, 66, .16);
  background: rgba(255,253,247,.98);
  box-shadow: 0 18px 28px rgba(61, 52, 40, .14);
}

.history-menu button {
  min-height: 2rem;
  border: 0;
  border-radius: .65rem;
  padding: 0 .625rem;
  background: transparent;
  color: #725542;
  text-align: left;
  font-size: .8125rem;
  font-weight: 800;
}

.history-menu button:hover,
.history-menu button:focus-visible {
  background: #f7ead4;
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
