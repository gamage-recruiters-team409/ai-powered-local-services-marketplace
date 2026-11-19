import axios from 'axios'

import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Admin announcements HTTP client. Same rules as the other admin clients: the token is read through the auth bridge
 * and attached only when the request goes to the API origin.
 * TEMPORARY: swap for the shared Axios instance once every admin client moves to it.
 */
const announcementHttp = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

announcementHttp.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const BASE = '/admin/announcements'

function itemPath(id) {
  return `${BASE}/${encodeURIComponent(id)}`
}

/** Drops empty filters so the URL stays clean. */
function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

/**
 * GET /admin/announcements -> { content, page, size, totalElements, totalPages }
 * @param {{status?: string, audience?: string, q?: string, page?: number, size?: number}} filters  page is 0-based
 */
export async function listAnnouncements(filters = {}, signal) {
  const { data } = await announcementHttp.get(BASE, { params: cleanParams(filters), signal })
  return data
}

/** GET /admin/announcements/summary -> { active, scheduled, total, tabs: { all, published, scheduled, drafts } } */
export async function getAnnouncementSummary(signal) {
  const { data } = await announcementHttp.get(`${BASE}/summary`, { signal })
  return data
}

/** GET /admin/announcements/{id} -> AnnouncementResponse */
export async function getAnnouncement(id, signal) {
  const { data } = await announcementHttp.get(itemPath(id), { signal })
  return data
}

/** POST /admin/announcements (intent DRAFT | PUBLISH) -> AnnouncementResponse */
export async function createAnnouncement(body) {
  const { data } = await announcementHttp.post(BASE, body)
  return data
}

/**
 * PUT /admin/announcements/{id} (intent DRAFT | PUBLISH) -> AnnouncementResponse.
 * body.version must be the version the form was loaded with: a newer one on the server gives 409
 * ANNOUNCEMENT_UPDATE_CONFLICT instead of overwriting someone else's change.
 */
export async function updateAnnouncement(id, body) {
  const { data } = await announcementHttp.put(itemPath(id), body)
  return data
}

/**
 * DELETE /admin/announcements/{id}?version= -> 204 (DRAFT or SCHEDULED only). With the version that is on screen, a
 * delete of an announcement someone changed meanwhile gives 409 instead of removing the newer change.
 */
export async function deleteAnnouncement(id, version) {
  await announcementHttp.delete(itemPath(id), { params: cleanParams({ version }) })
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
