// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import axios from 'axios'
import apiClient from '../../../shared/services/apiClient'
import tokenStore from '../../../shared/services/tokenStore'
import {
  catalogHttp,
  getAdminCategories,
  createCategory,
  updateCategory,
  deactivateCategory,
  getPublicCategoryTree,
  getCategoryBySlug,
  extractCatalogApiError,
} from './catalogService'

describe('catalogService', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    tokenStore.clear()
    window.sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    tokenStore.clear()
    window.sessionStorage.clear()
  })

  it('delegates catalogHttp to the shared WP01 apiClient', () => {
    expect(catalogHttp).toBe(apiClient)
  })

  it('fetches admin category hierarchy successfully', async () => {
    const mockData = [
      {
        id: 'cat-1',
        name: 'Home Improvement',
        slug: 'home-improvement',
        description: 'All home services',
        commissionRate: 0.1,
        active: true,
        subcategories: [
          {
            id: 'cat-2',
            name: 'Plumbing',
            slug: 'plumbing',
            parentId: 'cat-1',
            commissionRate: 0.12,
            active: true,
          },
        ],
      },
    ]

    const getSpy = vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockData })

    const result = await getAdminCategories()
    expect(getSpy).toHaveBeenCalledWith('/admin/categories', {})
    expect(result).toEqual(mockData)
    expect(result).toHaveLength(1)
    expect(result[0].subcategories).toHaveLength(1)
  })

  it('creates a new category with full payload including description', async () => {
    const payload = {
      name: 'Electrical Repairs',
      slug: 'electrical-repairs',
      description: 'Certified electrical repair services',
      icon: 'electrician',
      parentId: null,
      commissionRate: 0.1,
    }

    const createdCategory = { id: 'cat-created', ...payload, active: true }
    const postSpy = vi.spyOn(catalogHttp, 'post').mockResolvedValueOnce({ data: createdCategory })

    const result = await createCategory(payload)
    expect(postSpy).toHaveBeenCalledWith('/admin/categories', payload, {})
    expect(result.id).toBe('cat-created')
    expect(result.description).toBe('Certified electrical repair services')
  })

  it('updates an existing category by encoded id', async () => {
    const updatePayload = {
      name: 'Electrical Repairs (Updated)',
      slug: 'electrical-repairs',
      description: 'Updated description',
      icon: 'electrician',
      commissionRate: 0.15,
      active: true,
    }

    const putSpy = vi.spyOn(catalogHttp, 'put').mockResolvedValueOnce({
      data: { id: 'cat/1', ...updatePayload },
    })

    const result = await updateCategory('cat/1', updatePayload)
    expect(putSpy).toHaveBeenCalledWith('/admin/categories/cat%2F1', updatePayload, {})
    expect(result.name).toBe('Electrical Repairs (Updated)')
    expect(result.commissionRate).toBe(0.15)
  })

  it('deactivates a category by encoded id', async () => {
    const deleteSpy = vi.spyOn(catalogHttp, 'delete').mockResolvedValueOnce({ data: null })

    const result = await deactivateCategory('cat to delete')
    expect(deleteSpy).toHaveBeenCalledWith('/admin/categories/cat%20to%20delete', {})
    expect(result).toBeNull()
  })

  it('fetches public category tree for guests with _skipAuth', async () => {
    const mockTree = [{ id: 'pub-1', name: 'Cleaning', slug: 'cleaning', subcategories: [] }]
    const getSpy = vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockTree })

    const result = await getPublicCategoryTree()
    expect(getSpy).toHaveBeenCalledWith('/categories', { signal: undefined, _skipAuth: true })
    expect(result).toEqual(mockTree)
  })

  it('fetches single category details by encoded slug with _skipAuth', async () => {
    const mockCat = { id: 'cat-1', name: 'Plumbing', slug: 'plumbing' }
    const getSpy = vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockCat })

    const result = await getCategoryBySlug('plumbing')
    expect(getSpy).toHaveBeenCalledWith('/categories/plumbing', { signal: undefined, _skipAuth: true })
    expect(result.slug).toBe('plumbing')
  })

  it('parses normalized apiClient validation fieldErrors cleanly', () => {
    const normalizedErr = Object.assign(new Error('Validation failed'), {
      status: 400,
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        { field: 'slug', message: 'Slug must be lowercase' },
        { field: 'commissionRate', message: 'Commission rate cannot exceed 50%' },
      ],
    })

    const parsed = extractCatalogApiError(normalizedErr)
    expect(parsed).toEqual({
      cancelled: false,
      isOffline: false,
      status: 400,
      message: 'slug: Slug must be lowercase • commissionRate: Commission rate cannot exceed 50%',
    })
  })

  it('parses normalized apiClient 409 conflict messages', () => {
    const conflictErr = Object.assign(
      new Error('Cannot deactivate category while it has active subcategories'),
      {
        status: 409,
        code: 'RESOURCE_CONFLICT',
      }
    )

    const parsed = extractCatalogApiError(conflictErr)
    expect(parsed.status).toBe(409)
    expect(parsed.message).toBe('Cannot deactivate category while it has active subcategories')
  })

  it('treats cancelled AbortController errors (including normalized ones) as cancelled', () => {
    const cancelErr = new axios.CanceledError('canceled')
    const wrappedErr = Object.assign(new Error('canceled'), {
      status: null,
      code: 'NETWORK_ERROR',
      originalError: cancelErr,
    })

    expect(extractCatalogApiError(cancelErr).cancelled).toBe(true)
    expect(extractCatalogApiError(wrappedErr).cancelled).toBe(true)
  })

  it('marks network errors with status 0 as offline', () => {
    const offlineErr = Object.assign(new Error('Network Error'), {
      status: 0,
      code: 'NETWORK_ERROR',
    })

    const parsed = extractCatalogApiError(offlineErr)
    expect(parsed.isOffline).toBe(true)
    expect(parsed.status).toBe(0)
  })
})
