import axios from 'axios'

import { shouldAttachToken } from './apiTokenGuard'
import { getAccessToken } from './authBridge'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL

/**
 * Admin feature HTTP client. Base URL comes from VITE_API_BASE_URL (e.g. http://localhost:8080/api/v1).
 * TEMPORARY: swap for the shared Axios instance (with token refresh) once WP01 provides it.
 */
const adminHttp = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
})

adminHttp.interceptors.request.use((config) => {
  // Without a base URL, requests would silently go to the frontend server; fail with a clear error instead.
  if (!API_BASE_URL) {
    throw new Error('VITE_API_BASE_URL is not configured')
  }
  const token = getAccessToken()
  // Attach the token only when the request resolves to the API origin, so it can never reach another host.
  if (token && shouldAttachToken(config.url, config.baseURL, API_BASE_URL)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** GET /admin/access -> { role, canEdit[], canView[], canAccessSuperAdmin } */
export async function fetchAdminAccess(signal) {
  const { data } = await adminHttp.get('/admin/access', { signal })
  return data
}

/** GET /users/me -> { id, name, email, role, ... } (used for the header name) */
export async function fetchCurrentUser(signal) {
  const { data } = await adminHttp.get('/users/me', { signal })
  return data
}

export function isRequestCancelled(error) {
  return axios.isCancel(error)
}
