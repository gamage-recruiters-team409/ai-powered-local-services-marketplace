import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  catalogHttp,
  getAdminCategories,
  createCategory,
  updateCategory,
  deactivateCategory,
  getPublicCategoryTree,
  getCategoryBySlug,
} from './catalogService'

describe('catalogService', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fetches admin category hierarchy successfully', async () => {
    const mockData = [
      {
        id: 'cat-1',
        name: 'Home Improvement',
        slug: 'home-improvement',
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

    vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockData })

    const result = await getAdminCategories()
    expect(result).toEqual(mockData)
    expect(result).toHaveLength(1)
    expect(result[0].subcategories).toHaveLength(1)
  })

  it('creates a new category with payload', async () => {
    const payload = {
      name: 'Electrical Repairs',
      slug: 'electrical-repairs',
      description: 'Certified electrical repair services',
      icon: '⚡',
      parentId: null,
      commissionRate: 0.1,
    }

    const createdCategory = { id: 'cat-created', ...payload, active: true }
    vi.spyOn(catalogHttp, 'post').mockResolvedValueOnce({ data: createdCategory })

    const result = await createCategory(payload)
    expect(result.id).toBe('cat-created')
    expect(result.name).toBe('Electrical Repairs')
  })

  it('updates an existing category by id', async () => {
    const updatePayload = {
      name: 'Electrical Repairs (Updated)',
      slug: 'electrical-repairs',
      description: 'Updated description',
      icon: '⚡',
      commissionRate: 0.15,
      active: true,
    }

    vi.spyOn(catalogHttp, 'put').mockResolvedValueOnce({
      data: { id: 'cat-created', ...updatePayload },
    })

    const result = await updateCategory('cat-created', updatePayload)
    expect(result.name).toBe('Electrical Repairs (Updated)')
    expect(result.commissionRate).toBe(0.15)
  })

  it('deactivates a category by id', async () => {
    vi.spyOn(catalogHttp, 'delete').mockResolvedValueOnce({ data: null })

    const result = await deactivateCategory('cat-to-delete')
    expect(result).toBeNull()
  })

  it('fetches public category tree for guests', async () => {
    const mockTree = [{ id: 'pub-1', name: 'Cleaning', slug: 'cleaning', subcategories: [] }]
    vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockTree })

    const result = await getPublicCategoryTree()
    expect(result).toEqual(mockTree)
  })

  it('fetches single category details by slug', async () => {
    const mockCat = { id: 'cat-1', name: 'Plumbing', slug: 'plumbing' }
    vi.spyOn(catalogHttp, 'get').mockResolvedValueOnce({ data: mockCat })

    const result = await getCategoryBySlug('plumbing')
    expect(result.slug).toBe('plumbing')
  })
})
