/**
 * WP08 – Service Completion API (real backend).
 * Endpoints: /api/v1/bookings/{bookingId}/completion[...]
 * Response shape: ServiceCompletionResponse from the backend completion module.
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

const base = (bookingId) => `/bookings/${encodeURIComponent(bookingId)}/completion`

export async function getCompletion(bookingId) {
  const { data } = await http.get(base(bookingId))
  return data
}

export async function startService(bookingId) {
  const { data } = await http.post(`${base(bookingId)}/start`)
  return data
}

export async function markCompleted(bookingId, { completionNote, photoUrls }) {
  const { data } = await http.post(`${base(bookingId)}/mark-completed`, { completionNote, photoUrls })
  return data
}

export async function confirmCompletion(bookingId) {
  const { data } = await http.post(`${base(bookingId)}/confirm`)
  return data
}

/**
 * Photo upload is not available yet: the media upload endpoint is still to be agreed.
 * Until then the mock API is used for photos.
 */
export async function uploadCompletionPhoto() {
  throw new Error('Photo upload is not available yet')
}
