import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Admin users HTTP client. Same rules as the other admin clients: the token is read through the auth bridge and
 * attached only when the request goes to the API origin.
 * TEMPORARY: swap for the shared Axios instance once every admin client moves to it.
 */
const usersHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

usersHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const BASE = '/admin/users'

const userPath = (id) => `${BASE}/${encodeURIComponent(id)}`

/** Drops empty filters so the URL stays clean. */
function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

/** GET /admin/users -> { content, page, size, totalElements, totalPages }. page is 0-based. */
export async function listUsers({ q, role, status, page = 0, size } = {}, signal) {
  const { data } = await usersHttp.get(BASE, { params: cleanParams({ q, role, status, page, size }), signal })
  return data
}

/** GET /admin/users/summary -> { customers, providers, suspended }, each a section with `available`. */
export async function getUsersSummary(signal) {
  const { data } = await usersHttp.get(`${BASE}/summary`, { signal })
  return data
}

/** GET /admin/users/registrations?period=WEEKS|MONTHS -> { period, available, reason?, buckets? } */
export async function getRegistrations(period, signal) {
  const { data } = await usersHttp.get(`${BASE}/registrations`, { params: { period }, signal })
  return data
}

/** "users-2026-11-19.csv" (UTC date, same as the server) when the browser cannot read Content-Disposition. */
export function fallbackExportName(now = new Date()) {
  return `users-${now.toISOString().slice(0, 10)}.csv`
}

export function filenameFromDisposition(header) {
  if (!header) return null
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * GET /admin/users/export.csv with the list filters -> { blob, filename }. The server's file name is used when the
 * Content-Disposition header is readable (CORS only exposes some headers), otherwise the same dated name.
 */
export async function exportUsersCsv({ q, role, status } = {}) {
  const response = await usersHttp.get(`${BASE}/export.csv`, {
    params: cleanParams({ q, role, status }),
    responseType: 'blob',
  })
  const filename = filenameFromDisposition(response.headers?.['content-disposition']) ?? fallbackExportName()
  return { blob: response.data, filename }
}

/** GET /admin/users/{id} -> account details (profile, sections with `available`, actions). */
export async function getUser(id, signal) {
  const { data } = await usersHttp.get(userPath(id), { signal })
  return data
}

/**
 * POST /admin/users/{id}/suspend { reasonCode, note } -> { userId, status, changed, auditLogId?, listingVisibilitySynced? }
 * listingVisibilitySynced is present for a provider: false when the account changed but its listings did not.
 */
export async function suspendUser(id, { reasonCode, note }) {
  const { data } = await usersHttp.post(`${userPath(id)}/suspend`, { reasonCode, note: note || null })
  return data
}

/** POST /admin/users/{id}/reactivate { note } */
export async function reactivateUser(id, { note }) {
  const { data } = await usersHttp.post(`${userPath(id)}/reactivate`, { note })
  return data
}

/** GET /admin/users/{id}/listings/{listingId} -> section { available, reason?, data? } */
export async function getUserListing(id, listingId, signal) {
  const { data } = await usersHttp.get(`${userPath(id)}/listings/${encodeURIComponent(listingId)}`, { signal })
  return data
}

/** POST /admin/users/{id}/listing-visibility/sync -> { userId, status, listingVisibilitySynced: true } (idempotent) */
export async function syncListingVisibility(id) {
  const { data } = await usersHttp.post(`${userPath(id)}/listing-visibility/sync`)
  return data
}

/** GET /admin/users/{id}/listings?page&size -> section { available, reason?, data?: page of listing rows } */
export async function getUserListings(id, { page = 0, size } = {}, signal) {
  const { data } = await usersHttp.get(`${userPath(id)}/listings`, { params: cleanParams({ page, size }), signal })
  return data
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
