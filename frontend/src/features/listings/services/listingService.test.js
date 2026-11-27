// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  computeListingPayoutBreakdown,
  createListing,
  deleteListingMedia,
  extractListingApiError,
  extractListingErrorCode,
  findNearestSriLankaDistrict,
  formatModerationFeedback,
  getMyListings,
  getMyListingsSummary,
  getPublicCategories,
  listingHttp,
  normalizeCategoryTree,
  resolveCategoryIconAsset,
  screenListingContent,
  updateListing,
  updateListingStatus,
  uploadListingPhoto,
} from './listingService'
import {
  computeHaversineKm,
  findNearestSriLankaZone,
  getZonesWithinRadius,
  isWithinSriLankaBounds,
  MAX_SERVICE_AREAS,
  mergeRadiusZones,
} from './sriLankaZones'

describe('listingService utilities & live shared apiClient integration', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
  })

  it('detects Sri Lankan phone numbers, emails, and WhatsApp links in content screening', () => {
    expect(screenListingContent({ description: 'Standard bathroom leak diagnosis and valve seal.' }).flagged).toBe(false)

    const phoneHit = screenListingContent({ description: 'Call me directly on 0 7 7 - 1 2 3 - 4 5 6 7 anytime.' })
    expect(phoneHit.flagged).toBe(true)
    expect(phoneHit.reasons[0]).toContain('phone number')

    const emailHit = screenListingContent({ description: 'Email plumber [at] example [dot] com for discount.' })
    expect(emailHit.flagged).toBe(true)

    const linkHit = screenListingContent({ title: 'Message wa.me/94771234567 fast repair' })
    expect(linkHit.flagged).toBe(true)
  })

  it('resolves nearest Sri Lankan district from coordinates', () => {
    expect(findNearestSriLankaDistrict(6.9271, 79.8612).name).toBe('Colombo')
    expect(findNearestSriLankaDistrict(7.2906, 80.6337).name).toBe('Kandy')
  })

  it('calls live backend endpoints via shared apiClient and sorts page items accurately', async () => {
    vi.spyOn(listingHttp, 'get').mockImplementation(async (url) => {
      if (url === '/listings/me/summary') {
        return {
          data: {
            totalListings: 2,
            activeCount: 1,
            pausedCount: 1,
            draftCount: 0,
            archivedCount: 0,
            flaggedCount: 0,
            rejectedCount: 0,
            categoryInactiveCount: 0,
          },
        }
      }
      if (url === '/listings/me') {
        return {
          data: {
            content: [
              { id: 'lst-1', title: 'High Rate Plumbing', basePrice: 4500, status: 'ACTIVE', categoryActive: true },
              { id: 'lst-2', title: 'Low Rate Leak Fix', basePrice: 2200, status: 'PAUSED', categoryActive: true },
            ],
            totalElements: 2,
            totalPages: 1,
            number: 0,
            size: 8,
          },
        }
      }
      throw new Error(`Unexpected GET ${url}`)
    })

    const summaryRes = await getMyListingsSummary()
    expect(summaryRes.isFallback).toBe(false)
    expect(summaryRes.summary.totalListings).toBe(2)

    const listRes = await getMyListings({ status: 'ALL', sortBy: 'price_asc' })
    expect(listRes.isFallback).toBe(false)
    expect(listRes.pageData.content.map((item) => item.id)).toEqual(['lst-2', 'lst-1'])
  })

  it('executes create, update, and status transitions against live apiClient without silent mock fallback', async () => {
    vi.spyOn(listingHttp, 'post').mockResolvedValue({
      data: { id: 'lst-900', title: 'Solar Water Heater Leak Fix', status: 'ACTIVE' },
    })
    vi.spyOn(listingHttp, 'put').mockResolvedValue({
      data: { id: 'lst-900', title: 'Solar Water Heater Leak Fix Updated', status: 'ACTIVE' },
    })
    vi.spyOn(listingHttp, 'patch').mockResolvedValue({
      data: { id: 'lst-900', title: 'Solar Water Heater Leak Fix Updated', status: 'PAUSED' },
    })

    const created = await createListing({
      categoryId: 'cat-1',
      title: 'Solar Water Heater Leak Fix',
      saveAsDraft: false,
    })
    expect(created.isFallback).toBe(false)
    expect(created.listing.status).toBe('ACTIVE')

    const updated = await updateListing('lst-900', {
      categoryId: 'cat-1',
      title: 'Solar Water Heater Leak Fix Updated',
      saveAsDraft: false,
    })
    expect(updated.listing.title).toContain('Updated')

    const paused = await updateListingStatus('lst-900', { status: 'PAUSED' })
    expect(paused.listing.status).toBe('PAUSED')
  })

  it('propagates 401/403/404/503 backend errors honestly and extracts error codes and fieldErrors', async () => {
    const normalizedErr = new Error('Provider verification must be APPROVED before publishing listings')
    normalizedErr.status = 403
    normalizedErr.code = 'PROVIDER_NOT_VERIFIED'
    vi.spyOn(listingHttp, 'post').mockRejectedValue(normalizedErr)

    await expect(
      createListing({
        categoryId: 'cat-leak-repair',
        title: 'Unverified Provider Publish Attempt',
        saveAsDraft: false,
      }),
    ).rejects.toMatchObject({ status: 403, code: 'PROVIDER_NOT_VERIFIED' })

    expect(extractListingErrorCode(normalizedErr)).toBe('PROVIDER_NOT_VERIFIED')
    expect(extractListingApiError(normalizedErr)).toBe(
      'Provider verification must be APPROVED before publishing listings',
    )

    const validationErr = {
      fieldErrors: [{ field: 'description', message: 'Description must be at least 50 characters' }],
    }
    expect(extractListingApiError(validationErr)).toBe('Description must be at least 50 characters')

    expect(
      formatModerationFeedback(
        'Off-platform phone number detected in description',
        ['PHONE_NUMBER_DETECTED: Listing content appears to include a phone number'],
      ),
    ).toBe('Off-platform phone number detected in description.')

    expect(
      formatModerationFeedback(null, [
        'PHONE_NUMBER_DETECTED: Listing content appears to include a phone number',
        'LINK_DETECTED: External URLs are not allowed',
      ]),
    ).toBe('Listing content appears to include a phone number. External URLs are not allowed.')
  })

  it('normalizes backend CategoryTreeResponse subcategories, resolves 3D icon assets, computes payouts, and uploads media', async () => {
    vi.spyOn(listingHttp, 'get').mockResolvedValueOnce({
      data: [
        {
          id: 'root-solar',
          name: 'Solar & Renewable Energy',
          slug: 'solar-energy',
          icon: 'solar',
          commissionRate: 0.08,
          active: true,
          subcategories: [
            {
              id: 'sub-inverter',
              name: 'Inverter Servicing',
              slug: 'inverter-servicing',
              parentId: 'root-solar',
              commissionRate: 0.125,
              active: true,
            },
          ],
        },
      ],
    })

    const catRes = await getPublicCategories()
    expect(catRes.categories).toHaveLength(1)
    expect(catRes.categories[0].subcategories).toHaveLength(1)
    expect(catRes.categories[0].children).toHaveLength(1)
    expect(catRes.categories[0].subcategories[0].id).toBe('sub-inverter')
    expect(catRes.categories[0].subcategories[0].commissionRate).toBe(0.125)
    expect(resolveCategoryIconAsset(catRes.categories[0])).toBeTruthy()
    expect(normalizeCategoryTree(null)).toEqual([])

    const breakdown = computeListingPayoutBreakdown({
      basePrice: 4000,
      addOns: [
        { addOnId: 'ao-1', extraPrice: 1000, extraDurationMin: 30, active: true },
        { addOnId: 'ao-2', extraPrice: 500, extraDurationMin: 15, active: true },
      ],
      selectedAddOnIds: ['ao-1'],
      commissionRate: 0.125,
    })
    expect(breakdown.customerTotal).toBe(5000)
    expect(breakdown.platformFee).toBe(625)
    expect(breakdown.providerNetPayout).toBe(4375)
    expect(breakdown.commissionPctLabel).toBe('12.5%')
    expect(breakdown.addOnsDurationMin).toBe(30)

    const progressValues = []
    vi.spyOn(listingHttp, 'post').mockImplementationOnce(async (_url, _body, config) => {
      config?.onUploadProgress?.({ loaded: 50, total: 100 })
      return {
        data: {
          publicId: 'localserve/service-listings/prov-1/photo-1',
          secureUrl: 'https://res.cloudinary.com/demo/image/upload/v1/photo-1.jpg',
          fileName: 'photo-1.jpg',
          contentType: 'image/jpeg',
          sizeBytes: 1024,
        },
      }
    })

    const sampleFile = new File(['abc'], 'photo-1.jpg', { type: 'image/jpeg' })
    const uploadRes = await uploadListingPhoto(sampleFile, {
      onProgress: (pct) => progressValues.push(pct),
    })
    expect(uploadRes.isFallback).toBe(false)
    expect(uploadRes.uploaded.secureUrl).toContain('res.cloudinary.com')
    expect(progressValues).toEqual([50, 100])

    const deleteSpy = vi.spyOn(listingHttp, 'delete').mockResolvedValueOnce({ status: 204 })
    const delRes = await deleteListingMedia('localserve/service-listings/prov-1/photo-1')
    expect(delRes).toEqual({ deleted: true, isFallback: false })
    expect(deleteSpy).toHaveBeenCalledWith('/listings/media', {
      params: { publicId: 'localserve/service-listings/prov-1/photo-1' },
      signal: undefined,
    })
  })
})

describe('sriLankaZones Haversine geo-catalog & hybrid merge utilities', () => {
  it('computes accurate Haversine distances and expands zones monotonically within cap', () => {
    expect(computeHaversineKm(6.9271, 79.8612, 6.9271, 79.8612)).toBe(0)
    const colomboKandyKm = computeHaversineKm(6.9271, 79.8612, 7.2906, 80.6337)
    expect(colomboKandyKm).toBeGreaterThan(85)
    expect(colomboKandyKm).toBeLessThan(110)

    const kandyZero = getZonesWithinRadius(7.2906, 80.6337, 0, { fallbackLabel: 'Kandy City' })
    expect(kandyZero).toEqual(['Kandy City'])

    const kandySmall = getZonesWithinRadius(7.2906, 80.6337, 8)
    const kandyLarge = getZonesWithinRadius(7.2906, 80.6337, 30)
    expect(kandySmall[0]).toBe('Kandy')
    expect(kandySmall).toContain('Peradeniya')
    expect(kandyLarge.length).toBeGreaterThan(kandySmall.length)
    expect(kandyLarge).toContain('Nawalapitiya')

    const colomboWide = getZonesWithinRadius(6.9271, 79.8612, 50)
    expect(colomboWide.length).toBe(MAX_SERVICE_AREAS)
  })

  it('preserves manual zones under the max-20 cap, filters excluded auto zones, and deduplicates cleanly', () => {
    const mockAuto = Array.from({ length: 24 }, (_, idx) => `AutoTown-${idx + 1}`)
    mockAuto.splice(2, 0, 'Peradeniya')
    mockAuto.splice(5, 0, 'peradeniya')

    const merged = mergeRadiusZones({
      autoZones: mockAuto,
      manualZones: ['Custom Estate Lane', 'custom estate lane', 'Peradeniya'],
      excludedAutoZones: ['AutoTown-1', 'autotown-2'],
      maxZones: MAX_SERVICE_AREAS,
    })

    expect(merged.serviceAreas.length).toBe(MAX_SERVICE_AREAS)
    expect(merged.manualCount).toBe(2)
    expect(merged.includedAutoCount).toBe(18)
    expect(merged.serviceAreas).toContain('Custom Estate Lane')
    expect(merged.serviceAreas).toContain('Peradeniya')
    expect(merged.serviceAreas).not.toContain('AutoTown-1')
    expect(merged.serviceAreas).not.toContain('AutoTown-2')
    expect(merged.truncatedCount).toBe(4)
  })

  it('validates Sri Lanka bounds (rejecting VPN/desktop Helsinki coords) and resolves nearest Sri Lankan town', () => {
    expect(isWithinSriLankaBounds(6.9271, 79.8612)).toBe(true)
    expect(isWithinSriLankaBounds(9.6615, 80.0255)).toBe(true)
    expect(isWithinSriLankaBounds(60.1713, 24.9298)).toBe(false)
    expect(isWithinSriLankaBounds(Number.NaN, 79.8612)).toBe(false)

    const nearestNugegoda = findNearestSriLankaZone(6.865, 79.9)
    expect(nearestNugegoda?.name).toBe('Nugegoda')
    expect(nearestNugegoda?.district).toBe('Colombo')
    expect(findNearestSriLankaZone(undefined, 79.9)).toBeNull()
  })
})
