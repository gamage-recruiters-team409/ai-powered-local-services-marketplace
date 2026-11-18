import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Mock mode for the dispute screens: explicit, opt-in and DEV-only. Production builds never use it.
 * Enable locally with VITE_DISPUTE_FIXTURE_MODE=true in frontend/.env.local, then restart `npm run dev`.
 * The sample data is loaded with a dynamic import inside this DEV-only branch, so it is not in production builds.
 */
export function isDisputeFixtureMode() {
  return import.meta.env.DEV === true
    && String(import.meta.env.VITE_DISPUTE_FIXTURE_MODE ?? '').toLowerCase() === 'true'
}

/**
 * Dispute HTTP client. Same rules as the admin client: the token is read through the auth bridge and attached
 * only when the request goes to the API origin.
 * TEMPORARY: swap for the shared Axios instance once WP01 provides it.
 */
const disputeHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

disputeHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function disputePath(disputeId) {
  return `/disputes/${encodeURIComponent(disputeId)}`
}

/** GET /disputes/{id} -> DisputeDetailResponse */
export async function fetchDispute(disputeId, signal) {
  if (import.meta.env.DEV && isDisputeFixtureMode()) {
    const { getMockDispute } = await import('../fixtures/mockDisputes')
    return getMockDispute(disputeId)
  }
  const { data } = await disputeHttp.get(disputePath(disputeId), { signal })
  return data
}

/** POST /disputes/{id}/response with { text }. Evidence is not sent: there is no upload endpoint yet. */
export async function submitDisputeResponse(disputeId, text) {
  if (import.meta.env.DEV && isDisputeFixtureMode()) {
    const { submitMockResponse } = await import('../fixtures/mockDisputes')
    return submitMockResponse(disputeId, text)
  }
  const { data } = await disputeHttp.post(`${disputePath(disputeId)}/response`, { text })
  return data
}

/** GET /users/me -> { id, name, role, ... }; used for the role check on the dispute routes. */
export async function fetchCurrentUser(signal) {
  const { data } = await disputeHttp.get('/users/me', { signal })
  return data
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
