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
  const structuredMessages = [...(detail?.messages || [])].reverse()
  const latestPlanResult = structuredMessages.find(message =>
    message.kind === 'ASSISTANT_PLAN_RESULT'
      && (Array.isArray(message.payload?.options) || Array.isArray(message.payload?.executionSteps))
  )
  const latestClarification = structuredMessages.find(message => message.kind === 'ASSISTANT_CLARIFICATION')
  const latestError = structuredMessages.find(message => message.kind === 'ASSISTANT_ERROR')
  const latestClarificationOrError = [latestClarification, latestError]
    .filter(Boolean)
    .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))[0]
  const effectiveStructured = latestPlanResult || latestClarificationOrError || null
  const payload = effectiveStructured?.payload || {}
  return {
    messages,
    currentPlanId: payload.planId || effectiveStructured?.planSessionId || '',
    shownPlans: normalizePlans(Array.isArray(payload.options) ? payload.options : []),
    clarification: payload.clarification || {},
    currentStep: payload.currentStep || (messages.length ? 'need' : 'need'),
    activeView: payload.currentView || 'chat',
    mapOrigin: payload.mapOrigin || {},
    execution: payload.execution || {},
    executionSteps: Array.isArray(payload.executionSteps) ? payload.executionSteps : [],
    selectedRank: Number.isFinite(Number(payload.selectedRank)) ? Number(payload.selectedRank) : null,
    threadTitle: detail?.title || ''
  }
}
