import axios from 'axios'

import { getAccessToken } from './authBridge'

/**
 * Admin feature HTTP client. Base URL comes from VITE_API_BASE_URL (e.g. http://localhost:8080/api/v1).
 * TEMPORARY: swap for the shared Axios instance (with token refresh) once WP01 provides it.
 */
const adminHttp = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15000,
})

const ABSOLUTE_URL = /^[a-z][a-z\d+\-.]*:\/\//i

adminHttp.interceptors.request.use((config) => {
  const token = getAccessToken()
  // Attach the token only to relative API paths, so it can never be sent to another host.
  if (token && !ABSOLUTE_URL.test(config.url ?? '')) {
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
