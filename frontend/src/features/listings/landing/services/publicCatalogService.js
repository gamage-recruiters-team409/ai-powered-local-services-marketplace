import apiClient from '../../../../shared/services/apiClient'

/**
 * Fetches the active public category tree (`GET /api/v1/categories`) for guest/public landing display.
 * Uses `_skipAuth: true` so anonymous visitors never send stale tokens or trigger session-expired flows.
 */
export async function getPublicCategoryTree(signal) {
  const response = await apiClient.get('/categories', {
    signal,
    _skipAuth: true,
  })
  return response.data
}
