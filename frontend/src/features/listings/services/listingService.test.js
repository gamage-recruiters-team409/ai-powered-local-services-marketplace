// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createListing,
  findNearestSriLankaDistrict,
  generateAiDescriptionSuggestion,
  getMyListings,
  getMyListingsSummary,
  listingHttp,
  resetLocalListingsDemoStore,
  screenListingContent,
  updateListingStatus,
} from './listingService'

describe('listingService utilities & local fallback CRUD', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
    resetLocalListingsDemoStore()
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

  it('generates advisory AI-003 descriptions tailored to delivery mode', () => {
    const workshopText = generateAiDescriptionSuggestion({
      title: 'Inverter Board Soldering',
      executionModel: 'WORKSHOP',
      district: 'Gampaha',
    })
    expect(workshopText).toContain('Gampaha workshop bay')
  })

  it('falls back to seeded local listings on 404 and supports create/pause/archive lifecycle', async () => {
    const notFoundErr = new Error('Not Found')
    notFoundErr.response = { status: 404, data: { message: 'Not found' } }
    vi.spyOn(listingHttp, 'get').mockRejectedValue(notFoundErr)
    vi.spyOn(listingHttp, 'post').mockRejectedValue(notFoundErr)
    vi.spyOn(listingHttp, 'patch').mockRejectedValue(notFoundErr)

    const summaryRes = await getMyListingsSummary()
    expect(summaryRes.isFallback).toBe(true)
    expect(summaryRes.summary.totalListings).toBe(5)
    expect(summaryRes.summary.categoryInactiveCount).toBe(1)

    const listRes = await getMyListings({ status: 'ACTIVE' })
    expect(listRes.pageData.content.length).toBeGreaterThan(0)

    const createdRes = await createListing({
      categoryId: 'cat-leak-repair',
      title: 'Solar Water Heater Leak Fix & Seal',
      description: 'Complete roof collector inspection and brass union sealing.',
      pricingModel: 'FIXED',
      basePrice: 3200,
      durationMin: 90,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      coverageRadiusKm: 15,
      saveAsDraft: false,
    })
    expect(createdRes.listing.status).toBe('ACTIVE')

    const pausedRes = await updateListingStatus(createdRes.listing.id, { status: 'PAUSED' })
    expect(pausedRes.listing.status).toBe('PAUSED')

    const archivedRes = await updateListingStatus(createdRes.listing.id, { status: 'ARCHIVED' })
    expect(archivedRes.listing.status).toBe('ARCHIVED')
  })

  it('propagates 403 authorization errors without swallowing them into local fallback', async () => {
    const forbiddenErr = new Error('Provider not verified')
    forbiddenErr.response = {
      status: 403,
      data: { message: 'Provider verification must be APPROVED before publishing' },
    }
    vi.spyOn(listingHttp, 'post').mockRejectedValue(forbiddenErr)

    await expect(
      createListing({
        categoryId: 'cat-leak-repair',
        title: 'Unverified Provider Publish Attempt',
        saveAsDraft: false,
      }),
    ).rejects.toMatchObject({ response: { status: 403 } })
  })
})
