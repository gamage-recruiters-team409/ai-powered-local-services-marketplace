/**
 * WP08 – Review API (real backend, PR #17).
 * Customer: POST /reviews, PUT /reviews/{id}, GET /reviews/me
 * Provider: GET /providers/me/reviews, PUT /reviews/{id}/reply, GET /providers/{providerId}/rating-summary
 * Response shape: ReviewResponse { id, bookingId, listingId, providerId, rating, comment, status,
 *                                  providerReply, edited, editedAt, createdAt }
 */
import axios from 'axios'

// Temporary: the dev token helper lives in the admin feature until a shared auth client exists (WP01).
import { getAccessToken } from '../../disputes-admin/services/authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

const http = axios.create({ baseURL: API_BASE_URL, timeout: 15000 })

http.interceptors.request.use((config) => {
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** S09 "My reviews" – the customer's own reviews, newest first (one page of up to 50). */
export async function getMyReviews() {
  const { data } = await http.get('/reviews/me', { params: { page: 0, size: 50 } })
  return data?.content ?? []
}

/** S07 – body: { bookingId, rating (1–5), comment (optional, max 1000) }. */
export async function createReview({ bookingId, rating, comment }) {
  const { data } = await http.post('/reviews', { bookingId, rating, comment })
  return data
}

/** S08 – body: { rating, comment }. Allowed within 48 hours and before the provider replies. */
export async function updateReview(reviewId, { rating, comment }) {
  const { data } = await http.put(`/reviews/${encodeURIComponent(reviewId)}`, { rating, comment })
  return data
}

/**
 * S09 "To review" needs the customer's completed bookings that have no review yet.
 * That list comes from the booking module (WP05 My Bookings), which is not available yet,
 * so the real API reports it as unsupported and the page shows a short notice instead.
 */
export const supportsToReview = false

export async function getBookingsToReview() {
  return []
}

/**
 * Booking title, provider name and completion date shown on the review cards.
 * Also comes from WP05; until then the cards show the booking reference only.
 */
export async function getBookingSummary() {
  return null
}

// ---------------------------------------------------------------- provider (S11–S12)

/** S11 – all reviews of the signed-in provider (any status), newest first. */
export async function getProviderReviews() {
  const { data } = await http.get('/providers/me/reviews', { params: { page: 0, size: 50 } })
  return data?.content ?? []
}

/** S11 – average, count and per-star counts from PUBLISHED reviews only. */
export async function getRatingSummary(providerId) {
  const { data } = await http.get(`/providers/${encodeURIComponent(providerId)}/rating-summary`)
  return data
}

/** S12 – body: { text } (max 1000). Only for PUBLISHED reviews of this provider. */
export async function replyToReview(reviewId, { text }) {
  const { data } = await http.put(`/reviews/${encodeURIComponent(reviewId)}/reply`, { text })
  return data
}
