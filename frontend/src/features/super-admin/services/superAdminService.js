import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Super Admin HTTP client (WP09). Same token-attach rules as the other admin clients.
 */
const superHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

superHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ---- Platform settings ----

/** GET /admin/settings -> grouped settings. */
export async function getSettings(signal) {
  const { data } = await superHttp.get('/admin/settings', { signal })
  return data
}

/** GET /admin/settings/{group} -> single group settings. */
export async function getGroupSettings(group, signal) {
  const { data } = await superHttp.get(`/admin/settings/${encodeURIComponent(group)}`, { signal })
  return data
}

/** PUT /admin/settings/{group} { version, values } -> updated group. */
export async function updateGroupSettings(group, body) {
  const { data } = await superHttp.put(`/admin/settings/${encodeURIComponent(group)}`, body)
  return data
}

/** GET /admin/settings/{group}/history -> audit history entries. */
export async function getGroupHistory(group, signal) {
  const { data } = await superHttp.get(`/admin/settings/${encodeURIComponent(group)}/history`, { signal })
  return data
}

// ---- Analytics ----

/** GET /admin/analytics/summary -> AdminAnalyticsSummaryResponse. */
export async function getAnalyticsSummary(signal) {
  const { data } = await superHttp.get('/admin/analytics/summary', { signal })
  return data
}

// ---- Reports ----

/** GET /admin/reports/{type}?from=&to=&page=&size= -> AdminReportResponse. */
export async function getReport(type, { from, to, page = 0, size = 20 } = {}, signal) {
  const params = Object.fromEntries(
    Object.entries({ from, to, page, size }).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  )
  const { data } = await superHttp.get(`/admin/reports/${encodeURIComponent(type)}`, { params, signal })
  return data
}

/** GET /admin/reports/{type}?from=&to=&format=csv -> Blob download. */
export async function exportReportCsv(type, { from, to } = {}) {
  const params = Object.fromEntries(
    Object.entries({ from, to, format: 'csv' }).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  )
  const response = await superHttp.get(`/admin/reports/${encodeURIComponent(type)}`, {
    params,
    responseType: 'blob',
  })
  const headers = response.headers ?? {}
  const getHeader = (name) => headers[name.toLowerCase()] ?? headers[name]
  const disposition = getHeader('content-disposition')
  let filename = `${type}-report-${new Date().toISOString().slice(0, 10)}.csv`
  if (disposition) {
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
    if (match) filename = decodeURIComponent(match[1])
  }
  return { blob: response.data, filename }
}

// ---- Admin accounts (PR #20) ----

/** GET /admin/accounts?role=&status=&q=&page=&size=&sort= -> AdminPageResponse<AdminAccountResponse>. */
export async function listAdminAccounts({ role, status, q, page = 0, size = 20, sort = 'createdAt,desc' } = {}, signal) {
  const params = Object.fromEntries(
    Object.entries({ role, status, q, page, size, sort }).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  )
  const { data } = await superHttp.get('/admin/accounts', { params, signal })
  return data
}

/** GET /admin/accounts/summary -> AdminAccountSummaryResponse { total, byRole, byStatus }. */
export async function getAdminAccountsSummary(signal) {
  const { data } = await superHttp.get('/admin/accounts/summary', { signal })
  return data
}

/** POST /admin/accounts { fullName, email, temporaryPassword, role } -> AdminAccountResponse. */
export async function createAdminAccount(body) {
  const { data } = await superHttp.post('/admin/accounts', body)
  return data
}

/** PATCH /admin/accounts/{userId}/status { status, reason } -> AdminStatusChangeResponse. */
export async function changeAdminStatus(userId, { status, reason }) {
  const { data } = await superHttp.patch(`/admin/accounts/${encodeURIComponent(userId)}/status`, { status, reason })
  return data
}

/** PATCH /admin/accounts/{userId}/role { role, reason } -> change role endpoint. */
export async function changeAdminRole(userId, { role, reason }) {
  const { data } = await superHttp.patch(`/admin/accounts/${encodeURIComponent(userId)}/role`, { role, reason })
  return data
}

// ---- Audit logs (PR #20, FR-ADM-009) ----

/** GET /admin/audit-logs?actorId=&targetUserId=&action=&from=&to=&page=&size= -> AdminPageResponse<AdminAuditLogResponse>. */
export async function getAdminAuditLogs({ actorId, targetUserId, action, from, to, page = 0, size = 20 } = {}, signal) {
  const params = Object.fromEntries(
    Object.entries({ actorId, targetUserId, action, from, to, page, size }).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  )
  const { data } = await superHttp.get('/admin/audit-logs', { params, signal })
  return data
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
