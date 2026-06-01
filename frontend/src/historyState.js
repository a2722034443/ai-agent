function timeText(value) {
  if (!value) return ''
  const date = new Date(value)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

export function clarificationSummary(answers, fields = []) {
  const labels = Object.fromEntries(fields.map(field => [field.key, field.label || field.key]))
  const summary = Object.entries(answers || {})
    .filter(([, value]) => value)
    .map(([key, value]) => `${labels[key] || key}：${value}`)
    .join('；')
  return summary || '已补充规划信息'
}

export function normalizeHistoryMessages(historyMessages = []) {
  return historyMessages.map(message => ({
    id: message.id,
    role: message.role,
    text: message.text || '',
    time: timeText(message.createdAt),
    clarification: message.kind === 'ASSISTANT_CLARIFICATION'
      ? (message.payload?.clarification || message.payload || {})
      : undefined,
    kind: message.kind,
    payload: message.payload || {},
    planSessionId: message.planSessionId || ''
  }))
}

export function restoreThreadState(detail, normalizePlans) {
  const messages = normalizeHistoryMessages(detail?.messages || [])
  const lastStructured = [...(detail?.messages || [])].reverse().find(message =>
    ['ASSISTANT_PLAN_RESULT', 'ASSISTANT_CLARIFICATION', 'ASSISTANT_ERROR'].includes(message.kind)
  )
  const payload = lastStructured?.payload || {}
  return {
    messages,
    currentPlanId: payload.planId || lastStructured?.planSessionId || '',
    shownPlans: normalizePlans(Array.isArray(payload.options) ? payload.options : []),
    clarification: payload.clarification || {},
    currentStep: payload.currentStep || (messages.length ? 'need' : 'need'),
    activeView: payload.currentView || 'chat',
    mapOrigin: payload.mapOrigin || {},
    executionSteps: Array.isArray(payload.executionSteps) ? payload.executionSteps : [],
    threadTitle: detail?.title || ''
  }
}
