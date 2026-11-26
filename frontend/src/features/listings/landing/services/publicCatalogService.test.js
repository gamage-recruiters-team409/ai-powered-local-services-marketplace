// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import apiClient from '../../../../shared/services/apiClient'
import tokenStore from '../../../../shared/services/tokenStore'
import {
  getFeaturedPublicServices,
  getLiveCustomerReviews,
} from './publicCatalogService'

vi.mock('../../../../shared/services/apiClient', () => ({
  default: {
    get: vi.fn(),
  },
}))

vi.mock('../../../../shared/services/tokenStore', () => ({
  default: {
    hasAccessToken: vi.fn(),
  },
}))

describe('publicCatalogService (PR #58 WP03/WP04/WP08 contract tests)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    tokenStore.hasAccessToken.mockReturnValue(false)
  })

  describe('getFeaturedPublicServices (F1 & F2)', () => {
    it('enforces verified=true on city-specific searches and excludes unverified listings (F1)', async () => {
      apiClient.get.mockResolvedValueOnce({
        data: {
          totalCount: 2,
          results: [
            {
              listingId: 'list-verified',
              providerId: 'prov-verified',
              profileId: 'prof-verified',
              name: 'Kamal Perera',
              category: 'Electrical & Solar Systems',
              description: 'Certified residential electrical wiring and breaker inspection.',
              location: 'Colombo',
              coverageRadiusKm: 15,
              pricing: { amount: 4500, model: 'FIXED' },
              rating: 4.9,
              reviewCount: 12,
              verified: true,
              supported: true,
            },
            {
              listingId: 'list-unverified',
              providerId: 'prov-unverified',
              profileId: 'prof-unverified',
              name: 'Unverified Pro',
              category: 'Plumbing & Water Systems',
              description: 'Pending admin verification.',
              location: 'Colombo',
              pricing: { amount: 2500, model: 'FIXED' },
              rating: null,
              reviewCount: 0,
              verified: false,
              supported: true,
            },
          ],
        },
      })

      const result = await getFeaturedPublicServices({ location: 'Colombo', size: 6 })

      expect(apiClient.get).toHaveBeenCalledWith(
        '/search',
        expect.objectContaining({
          _skipAuth: true,
          params: {
            verified: true,
            sort: 'recommended',
            page: 0,
            size: 6,
            location: 'Colombo',
          },
        })
      )
      expect(result.results).toHaveLength(1)
      expect(result.results[0].listingId).toBe('list-verified')
      expect(result.results[0].verified).toBe(true)
      expect(result.totalCount).toBe(1)
    })

    it('enforces verified=true and omits location for islandwide All Districts searches (F1)', async () => {
      apiClient.get.mockResolvedValueOnce({
        data: { totalCount: 0, results: [] },
      })

      await getFeaturedPublicServices({ location: 'All Districts', size: 6 })

      expect(apiClient.get).toHaveBeenCalledWith(
        '/search',
        expect.objectContaining({
          _skipAuth: true,
          params: {
            verified: true,
            sort: 'recommended',
            page: 0,
            size: 6,
          },
        })
      )
      const calledParams = apiClient.get.mock.calls[0][1].params
      expect(calledParams.location).toBeUndefined()
    })

    it('maps real WP04 ProviderSearchResult fields accurately without inventing a title property (F2)', async () => {
      apiClient.get.mockResolvedValueOnce({
        data: {
          totalCount: 1,
          results: [
            {
              listingId: 'list-101',
              providerId: 'prov-202',
              profileId: 'prof-303',
              name: 'Kamal Perera',
              image: 'https://cdn.localserve.lk/kamal.jpg',
              verified: true,
              rating: 4.8,
              reviewCount: 24,
              category: 'Electrical & Solar Systems',
              description: 'Domestic electrical installation and solar inverter maintenance.',
              location: 'Matara',
              pricing: { amount: 6500, model: 'FIXED' },
              coverageRadiusKm: 20,
              supported: true,
            },
          ],
        },
      })

      const { results } = await getFeaturedPublicServices({ location: 'Matara', size: 6 })
      const card = results[0]

      expect(card).toEqual({
        id: 'list-101',
        listingId: 'list-101',
        providerId: 'prov-202',
        profileId: 'prof-303',
        providerName: 'Kamal Perera',
        categoryName: 'Electrical & Solar Systems',
        description: 'Domestic electrical installation and solar inverter maintenance.',
        location: 'Matara',
        coverageRadiusKm: 20,
        imageUrl: 'https://cdn.localserve.lk/kamal.jpg',
        priceAmount: 6500,
        pricingModel: 'FIXED',
        rating: 4.8,
        reviewCount: 24,
        verified: true,
        badge: 'Admin-Verified Pro',
      })
      expect('title' in card).toBe(false)
    })
  })

  describe('getLiveCustomerReviews (F4)', () => {
    const sampleServices = [
      {
        providerId: 'prov-1',
        providerName: 'SunPower Lanka',
        categoryName: 'Electrical & Solar Systems',
        location: 'Colombo',
      },
    ]

    it('returns requiresAuth=true and makes zero HTTP calls for anonymous guests', async () => {
      tokenStore.hasAccessToken.mockReturnValue(false)

      const res = await getLiveCustomerReviews({ services: sampleServices })

      expect(res).toEqual({ reviews: [], requiresAuth: true, failed: false })
      expect(apiClient.get).not.toHaveBeenCalled()
    })

    it('returns requiresAuth=false and failed=false when authenticated and no published reviews exist', async () => {
      tokenStore.hasAccessToken.mockReturnValue(true)
      apiClient.get.mockResolvedValueOnce({ data: { content: [] } })

      const res = await getLiveCustomerReviews({ services: sampleServices })

      expect(apiClient.get).toHaveBeenCalledWith(
        '/providers/prov-1/reviews',
        expect.objectContaining({ params: { page: 0, size: 3 } })
      )
      expect(res).toEqual({ reviews: [], requiresAuth: false, failed: false })
    })

    it('returns published reviews when authenticated and review API succeeds', async () => {
      tokenStore.hasAccessToken.mockReturnValue(true)
      apiClient.get.mockResolvedValueOnce({
        data: {
          content: [
            {
              id: 'rev-1',
              customerName: 'Nadeesha Silva',
              rating: 5,
              comment: 'Prompt and clean inverter installation.',
              status: 'PUBLISHED',
              createdAt: '2026-11-20T10:00:00Z',
            },
            {
              id: 'rev-hidden',
              customerName: 'Hidden Review',
              rating: 1,
              comment: 'Moderated out',
              status: 'HIDDEN',
              createdAt: '2026-11-21T10:00:00Z',
            },
          ],
        },
      })

      const res = await getLiveCustomerReviews({ services: sampleServices })

      expect(res.requiresAuth).toBe(false)
      expect(res.failed).toBe(false)
      expect(res.reviews).toHaveLength(1)
      expect(res.reviews[0]).toMatchObject({
        id: 'rev-1',
        initials: 'NS',
        customerName: 'Nadeesha Silva',
        providerName: 'SunPower Lanka',
        serviceTag: 'Electrical & Solar Systems',
        rating: 5,
        quote: 'Prompt and clean inverter installation.',
      })
    })

    it('returns failed=true instead of disguising review API errors as empty reviews', async () => {
      tokenStore.hasAccessToken.mockReturnValue(true)
      apiClient.get.mockRejectedValueOnce(new Error('Network error'))

      const res = await getLiveCustomerReviews({ services: sampleServices })

      expect(res).toEqual({ reviews: [], requiresAuth: false, failed: true })
    })
  })
})
