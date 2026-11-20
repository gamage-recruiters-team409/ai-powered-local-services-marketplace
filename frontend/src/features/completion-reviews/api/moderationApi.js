/**
 * WP08 – Review moderation API (real backend, PR #41). MODERATOR decides, SUPER_ADMIN views only.
 *   GET  /admin/reviews?filter=ALL|FLAGGED|REPORTED&page=&size=   → ModerationPageResponse
 *   GET  /admin/reviews/{id}                                       → { review, openReports, history }
 *   POST /admin/reviews/{id}/publish  { note?, revision }          → ModerationReviewResponse
 *   POST /admin/reviews/{id}/remove   { reasonCode, note?, revision } → ModerationReviewResponse
 * `revision` is the review's moderationRevision the moderator saw; an outdated one returns 409.
 */
import axios from 'axios'

// Same rules as the other admin clients (token through the auth bridge, only sent to the API origin).
import { shouldAttachToken } from '../../disputes-admin/services/apiTokenGuard'
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

const http = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

http.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const BASE = '/admin/reviews'
const item = (reviewId) => `${BASE}/${encodeURIComponent(reviewId)}`

/** S14 – one page of the queue. page is 0-based. */
export async function getQueue({ filter = 'ALL', page = 0, size = 10 } = {}) {
  const { data } = await http.get(BASE, { params: { filter, page, size } })
  return data
}

/** S15 – the review, its open reports and its moderation history. */
export async function getReview(reviewId) {
  const { data } = await http.get(item(reviewId))
  return data
}

/** Publishes a held review, or keeps a reported one (dismisses its reports). */
export async function publishReview(reviewId, { note, revision }) {
  const { data } = await http.post(`${item(reviewId)}/publish`, { note: note || null, revision })
  return data
}

/** Removes a review with a reason (shown to the customer). */
export async function removeReview(reviewId, { reasonCode, note, revision }) {
  const { data } = await http.post(`${item(reviewId)}/remove`, { reasonCode, note: note || null, revision })
  return data
}
