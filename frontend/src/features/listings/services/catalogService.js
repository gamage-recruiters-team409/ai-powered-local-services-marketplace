import axios from 'axios'
import apiClient from '../../../shared/services/apiClient'
import tokenStore from '../../../shared/services/tokenStore'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'

/**
 * Shared authenticated HTTP client for WP03 catalog endpoints.
 * Delegates to WP01 shared `apiClient` so real `/login` in-memory access tokens
 * and 15-minute single-flight `/auth/refresh` retries work automatically.
 */
export const catalogHttp = apiClient

function buildAdminRequestConfig(signal) {
  const config = {}
  if (signal) {
    config.signal = signal
  }
  if (import.meta.env.DEV && !tokenStore.getAccessToken()) {
    try {
      const devToken = window.sessionStorage?.getItem('dev.accessToken')
      if (devToken) {
        config.headers = { Authorization: `Bearer ${devToken}` }
      }
    } catch {
      // Storage access unavailable
    }
  }
  return config
}

function isCancelledRequest(error) {
  if (!error) return false
  return (
    axios.isCancel(error) ||
    axios.isCancel(error.originalError) ||
    error.name === 'AbortError' ||
    error.name === 'CanceledError' ||
    error.code === 'ERR_CANCELED' ||
    error.originalError?.name === 'AbortError' ||
    error.originalError?.name === 'CanceledError' ||
    error.originalError?.code === 'ERR_CANCELED'
  )
}

/**
 * Normalizes both `apiClient` normalized errors and raw Axios / Spring Boot
 * GlobalExceptionHandler ErrorResponse payloads for clean UI display.
 */
export function extractCatalogApiError(error, fallbackMessage = 'Operation failed') {
  if (isCancelledRequest(error)) {
    return { cancelled: true, isOffline: false, status: null, message: '' }
  }

  const response = error?.response || error?.originalError?.response
  const status =
    typeof error?.status === 'number'
      ? error.status
      : typeof response?.status === 'number'
        ? response.status
        : error?.request || error?.originalError?.request
          ? 0
          : null

  if (status === 0 || (!response && error?.code === 'NETWORK_ERROR')) {
    return {
      cancelled: false,
      isOffline: true,
      status: 0,
      message: `Cannot reach backend server (${API_BASE_URL}). Verify the server is running and try again.`,
    }
  }

  const fieldErrors = Array.isArray(error?.fieldErrors)
    ? error.fieldErrors
    : Array.isArray(response?.data?.fieldErrors)
      ? response.data.fieldErrors
      : null

  if (fieldErrors && fieldErrors.length > 0) {
    const details = fieldErrors
      .map((item) => (item.field ? `${item.field}: ${item.message}` : item.message))
      .filter(Boolean)
      .join(' • ')
    if (details) {
      return { cancelled: false, isOffline: false, status, message: details }
    }
  }

  return {
    cancelled: false,
    isOffline: false,
    status,
    message: response?.data?.message || error?.message || fallbackMessage,
  }
}

/**
 * Fetches the entire category hierarchy for administrative management,
 * including root categories and subcategories with their status and commission rates.
 */
export async function getAdminCategories(signal) {
  const response = await catalogHttp.get('/admin/categories', buildAdminRequestConfig(signal))
  return response.data
}

/**
 * Creates a new root category or subcategory.
 *
 * @param {object} payload
 * @param {string} payload.name
 * @param {string} payload.slug
 * @param {string|null} [payload.description]
 * @param {string|null} [payload.icon]
 * @param {string|null} [payload.parentId] - null for root category, string id for subcategory
 * @param {number|string} payload.commissionRate - e.g. 0.10 for 10%
 */
export async function createCategory(payload) {
  const response = await catalogHttp.post('/admin/categories', payload, buildAdminRequestConfig())
  return response.data
}

/**
 * Updates an existing category's metadata, description, icon, commission rate, and active status.
 *
 * @param {string} id
 * @param {object} payload
 */
export async function updateCategory(id, payload) {
  const response = await catalogHttp.put(
    `/admin/categories/${encodeURIComponent(id)}`,
    payload,
    buildAdminRequestConfig()
  )
  return response.data
}

/**
 * Soft-deactivates / archives a category.
 *
 * @param {string} id
 */
export async function deactivateCategory(id) {
  const response = await catalogHttp.delete(
    `/admin/categories/${encodeURIComponent(id)}`,
    buildAdminRequestConfig()
  )
  return response.data
}

/**
 * Public category tree for customer / guest browsing.
 */
export async function getPublicCategoryTree(signal) {
  const response = await catalogHttp.get('/categories', { signal, _skipAuth: true })
  return response.data
}

/**
 * Public single category lookup by slug.
 *
 * @param {string} slug
 */
export async function getCategoryBySlug(slug, signal) {
  const response = await catalogHttp.get(`/categories/${encodeURIComponent(slug)}`, {
    signal,
    _skipAuth: true,
  })
  return response.data
}
