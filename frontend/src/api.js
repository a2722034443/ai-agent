const BASE_URL = import.meta.env.VITE_API_URL || ''
const TOKEN_KEY = 'lla_token'
const CLIENT_KEY = 'lla_client_id'
const DEFAULT_NICKNAME = '立刻游用户'

export function getSessionToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getClientId() {
  const existing = localStorage.getItem(CLIENT_KEY)
  if (existing) return existing
  const created = crypto.randomUUID()
  localStorage.setItem(CLIENT_KEY, created)
  return created
}

export function clearSessionToken() {
  localStorage.removeItem(TOKEN_KEY)
}

async function request(path, options = {}) {
  const { skipAuthRecovery = false, headers = {}, ...fetchOptions } = options
  const sessionToken = getSessionToken()
  const res = await fetch(`${BASE_URL}${path}`, {
    ...fetchOptions,
    headers: {
      'Content-Type': 'application/json',
      ...(sessionToken ? { 'X-Session-Token': sessionToken } : {}),
      'X-Client-Id': getClientId(),
      ...headers
    }
  })
  const data = await res.json().catch(() => ({}))
  if (res.status === 401 && sessionToken && path !== '/api/sessions' && !skipAuthRecovery) {
    clearSessionToken()
    await createSession(DEFAULT_NICKNAME)
    return request(path, { ...options, skipAuthRecovery: true })
  }
  if (!res.ok) {
    const error = new Error(data.error || `HTTP ${res.status}`)
    error.status = res.status
    error.payload = data
    throw error
  }
  return data
}

export async function createSession(nickname) {
  const data = await request('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({ nickname }),
    skipAuthRecovery: true
  })
  localStorage.setItem(TOKEN_KEY, data.token)
  return data
}

export function createPlan(payload) {
  const body = typeof payload === 'string' ? { message: payload } : payload
  return request('/api/plans', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function nearbyPois(payload) {
  return request('/api/nearby-pois', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function confirmPlan(planId, rank) {
  return request(`/api/plans/${planId}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ rank })
  })
}

export function sendFeedback(planId, message) {
  return request(`/api/plans/${planId}/feedback`, {
    method: 'POST',
    body: JSON.stringify({ message })
  })
}

export function createShare(payload) {
  return request('/api/collab/shares', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function voteShare(shareId, payload) {
  return request(`/api/collab/shares/${shareId}/votes`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function submitCollabComment(shareId, payload) {
  return request(`/api/collab/shares/${shareId}/comments`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function getMemory() {
  return request('/api/memory', { method: 'GET' })
}

export function getGuardStatus() {
  return request('/api/guard/status', { method: 'GET' })
}

export function getHistoryThreads() {
  return request('/api/history/threads', { method: 'GET' })
}

export function getHistoryThread(threadId) {
  return request(`/api/history/threads/${threadId}`, { method: 'GET' })
}

export function renameHistoryThread(threadId, title) {
  return request(`/api/history/threads/${threadId}`, {
    method: 'PATCH',
    body: JSON.stringify({ title })
  })
}

export function deleteHistoryThread(threadId) {
  return request(`/api/history/threads/${threadId}`, { method: 'DELETE' })
}
