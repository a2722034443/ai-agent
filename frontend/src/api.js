const BASE_URL = import.meta.env.VITE_API_URL || ''

async function request(path, options = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(localStorage.getItem('lla_token') ? { 'X-Session-Token': localStorage.getItem('lla_token') } : {}),
      ...(options.headers || {})
    },
    ...options
  })
  const data = await res.json().catch(() => ({}))
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
    body: JSON.stringify({ nickname })
  })
  localStorage.setItem('lla_token', data.token)
  return data
}

export function createPlan(payload) {
  const body = typeof payload === 'string' ? { message: payload } : payload
  return request('/api/plans', {
    method: 'POST',
    body: JSON.stringify(body)
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
