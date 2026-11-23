import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Admin disputes HTTP client. Same rules as the other admin clients: the token is read through the auth bridge and
 * attached only when the request goes to the API origin.
 * TEMPORARY: swap for the shared Axios instance once every admin client moves to it.
 */
const adminDisputeHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

adminDisputeHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const BASE = '/admin/disputes'

function itemPath(id) {
  return `${BASE}/${encodeURIComponent(id)}`
}

function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

/**
 * GET /admin/disputes -> { content, page, size, totalElements, totalPages }
 * @param {{status?, category?, q?, from?, to?, sort?, page?, size?}} filters  dates are yyyy-mm-dd; page is 0-based
 */
export async function listAdminDisputes(filters = {}, signal) {
  const { data } = await adminDisputeHttp.get(BASE, { params: cleanParams(filters), signal })
  return data
}

/** GET /admin/disputes/summary -> { underReview, awaitingResponse, appealed, resolvedLast30Days, manualReview, ... } */
export async function getAdminDisputeSummary(signal) {
  const { data } = await adminDisputeHttp.get(`${BASE}/summary`, { signal })
  return data
}

/** GET /admin/disputes/{id} -> AdminDisputeDetailResponse (with `actions` for the signed-in admin). */
export async function getAdminDispute(id, signal) {
  const { data } = await adminDisputeHttp.get(itemPath(id), { signal })
  return data
}

/** POST /admin/disputes/{id}/resolve with { action, customerRefundPercent?, applyProviderPenalty?, note }. */
export async function resolveAdminDispute(id, body) {
  const { data } = await adminDisputeHttp.post(`${itemPath(id)}/resolve`, body)
  return data
}

/** POST /admin/disputes/{id}/appeal/decision with { verdict, action?, customerRefundPercent?, applyProviderPenalty?, note }. */
export async function decideAdminAppeal(id, body) {
  const { data } = await adminDisputeHttp.post(`${itemPath(id)}/appeal/decision`, body)
  return data
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
