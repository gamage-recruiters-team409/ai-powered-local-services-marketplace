import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'

export const catalogHttp = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

catalogHttp.interceptors.request.use((config) => {
  try {
    const token =
      window.sessionStorage?.getItem('dev.accessToken') ||
      window.localStorage?.getItem('accessToken') ||
      window.localStorage?.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  } catch {
    // Storage access not available
  }
  return config
})

/**
 * Fetches the entire category hierarchy for administrative management,
 * including root categories and subcategories with their status and commission rates.
 */
export async function getAdminCategories(signal) {
  const response = await catalogHttp.get('/admin/categories', { signal })
  return response.data
}

/**
 * Creates a new root category or subcategory.
 *
 * @param {object} payload
 * @param {string} payload.name
 * @param {string} payload.slug
 * @param {string} [payload.description]
 * @param {string} [payload.icon]
 * @param {string} [payload.parentId] - null for root category, string id for subcategory
 * @param {number|string} payload.commissionRate - e.g. 0.10 for 10%
 */
export async function createCategory(payload) {
  const response = await catalogHttp.post('/admin/categories', payload)
  return response.data
}

/**
 * Updates an existing category's metadata, commission rate, and active status.
 *
 * @param {string} id
 * @param {object} payload
 */
export async function updateCategory(id, payload) {
  const response = await catalogHttp.put(`/admin/categories/${id}`, payload)
  return response.data
}

/**
 * Soft-deactivates / archives a category.
 *
 * @param {string} id
 */
export async function deactivateCategory(id) {
  const response = await catalogHttp.delete(`/admin/categories/${id}`)
  return response.data
}

/**
 * Public category tree for customer / guest browsing.
 */
export async function getPublicCategoryTree(signal) {
  const response = await catalogHttp.get('/categories', { signal })
  return response.data
}

/**
 * Public single category lookup by slug.
 *
 * @param {string} slug
 */
export async function getCategoryBySlug(slug, signal) {
  const response = await catalogHttp.get(`/categories/${slug}`, { signal })
  return response.data
}
