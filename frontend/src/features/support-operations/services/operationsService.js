import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Support operations HTTP client (WP09). Same rules as the other admin clients: the token is read through the auth
 * bridge and attached only when the request goes to the API origin.
 */
const opsHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

opsHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const BASE = '/admin/operations'

/** Drops empty filters so the URL stays clean. */
function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

// ---- Overdue bookings ----

/** GET /admin/operations/overdue-bookings -> OpsSection<{ rows, summary, distribution }>. page is 0-based. */
export async function getOverdueBookings({ search, delayBucket, page = 0, size } = {}, signal) {
  const { data } = await opsHttp.get(`${BASE}/overdue-bookings`, {
    params: cleanParams({ search, delayBucket, page, size }),
    signal,
  })
  return data
}

/** POST /admin/operations/overdue-bookings/{bookingId}/actions { action, note } -> OpsActionResult. */
export async function recordOverdueAction(bookingId, { action, note }) {
  const { data } = await opsHttp.post(`${BASE}/overdue-bookings/${encodeURIComponent(bookingId)}/actions`, {
    action,
    note,
  })
  return data
}

export function exportOverdueCsv({ search, delayBucket } = {}) {
  return downloadCsv(`${BASE}/overdue-bookings/export.csv`, cleanParams({ search, delayBucket }), 'overdue-bookings')
}

// ---- Late cases ----

/** GET /admin/operations/late-cases -> { lateStarts: OpsSection, noShowReports: OpsSection }. */
export async function getLateCases({ search, urgency, page = 0, size } = {}, signal) {
  const { data } = await opsHttp.get(`${BASE}/late-cases`, {
    params: cleanParams({ search, urgency, page, size }),
    signal,
  })
  return data
}

/** POST /admin/operations/late-cases/{caseId}/actions { action, note } -> OpsActionResult. */
export async function recordLateCaseAction(caseId, { action, note }) {
  const { data } = await opsHttp.post(`${BASE}/late-cases/${encodeURIComponent(caseId)}/actions`, { action, note })
  return data
}

export function exportLateCasesCsv({ search } = {}) {
  return downloadCsv(`${BASE}/late-cases/export.csv`, cleanParams({ search }), 'late-cases')
}

// ---- Repeat cancellations ----

/** GET /admin/operations/repeat-cancellations -> OpsSection<{ rows, summary, top5, suspensionRecommendations, ... }>. */
export async function getRepeatCancellations({ search, role, page = 0, size } = {}, signal) {
  const { data } = await opsHttp.get(`${BASE}/repeat-cancellations`, {
    params: cleanParams({ search, role, page, size }),
    signal,
  })
  return data
}

/** POST /admin/operations/repeat-cancellations/{userId}/actions { action, note } -> OpsActionResult. */
export async function recordRepeatAction(userId, { action, note }) {
  const { data } = await opsHttp.post(`${BASE}/repeat-cancellations/${encodeURIComponent(userId)}/actions`, {
    action,
    note,
  })
  return data
}

export function exportRepeatCsv({ search, role } = {}) {
  return downloadCsv(`${BASE}/repeat-cancellations/export.csv`, cleanParams({ search, role }), 'repeat-cancellations')
}

// ---- Dashboard ----

/** GET /admin/operations/dashboard -> { overdueCount, lateCount, flaggedUsersCount, overdueDistribution, latePerDay, mostOverdue }. */
export async function getDashboard(signal) {
  const { data } = await opsHttp.get(`${BASE}/dashboard`, { signal })
  return data
}

// ---- Shared helpers ----

export function filenameFromDisposition(header) {
  if (!header) return null
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return match ? decodeURIComponent(match[1]) : null
}

export function fallbackExportName(prefix, now = new Date()) {
  return `${prefix}-${now.toISOString().slice(0, 10)}.csv`
}

async function downloadCsv(path, params, prefix) {
  const response = await opsHttp.get(path, { params, responseType: 'blob' })
  const filename = filenameFromDisposition(response.headers?.['content-disposition']) ?? fallbackExportName(prefix)
  return { blob: response.data, filename }
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
