// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ToastProvider } from '../../../shared/components/feedback'
import { AuthContext, ROLES } from '../../auth'
import { ProviderListingGuard } from '../components/ProviderListingShell'
import { listingRoutes } from '../routes/listingRoutes'
import {
  FALLBACK_CATEGORIES,
  listingHttp,
  SRI_LANKA_PROVINCES_FALLBACK,
} from '../services/listingService'
import { CreateEditListingWizardPage } from './CreateEditListingWizardPage'
import { ProviderListingsDashboardPage } from './ProviderListingsDashboardPage'

const MOCK_BACKEND_LISTINGS = [
  {
    id: 'lst-1001',
    providerId: 'prov-1',
    categoryId: 'cat-leak-repair',
    categoryName: 'Emergency Leak Repair',
    parentCategoryId: 'cat-plumbing',
    title: 'Emergency Pipe Leak Repair & Waterline Diagnostic',
    description:
      'Comprehensive on-site emergency water pipe leakage diagnostic and immediate temporary sealing with full pressure testing and warranty.',
    pricingModel: 'FIXED',
    basePrice: 2800,
    durationMin: 90,
    executionModel: 'ON_SITE',
    district: 'Colombo',
    baseCity: 'Colombo 05 (Havelock Town)',
    location: { longitude: 79.8612, latitude: 6.9271 },
    coverageRadiusKm: 15,
    serviceAreas: ['Colombo 01-15', 'Dehiwala'],
    images: ['https://cdn.localserve.lk/listings/lst-1001-cover.jpg'],
    coverImageUrl: 'https://cdn.localserve.lk/listings/lst-1001-cover.jpg',
    addOns: [],
    status: 'ACTIVE',
    availableForBooking: true,
    categoryActive: true,
    activeAddOnCount: 2,
    moderationFlags: [],
    ratingAvg: 4.9,
    ratingCount: 34,
    createdAt: '2026-11-16T08:15:00Z',
    updatedAt: '2026-11-18T09:10:00Z',
  },
  {
    id: 'lst-1003',
    providerId: 'prov-1',
    categoryId: 'cat-ac-wash',
    categoryName: 'Split AC Chemical Wash',
    parentCategoryId: 'cat-hvac',
    title: 'Inverter Split AC Deep Chemical Wash & Coil Flush',
    description:
      'Complete indoor evaporator and outdoor condenser high-pressure chemical wash using waterproof containment and drain line vacuum flush.',
    pricingModel: 'HOURLY',
    basePrice: 2400,
    durationMin: 60,
    executionModel: 'ON_SITE',
    district: 'Gampaha',
    baseCity: 'Wattala',
    location: { longitude: 79.8919, latitude: 6.9892 },
    coverageRadiusKm: 18,
    serviceAreas: ['Wattala'],
    images: ['https://cdn.localserve.lk/listings/lst-1003-cover.jpg'],
    coverImageUrl: 'https://cdn.localserve.lk/listings/lst-1003-cover.jpg',
    addOns: [],
    status: 'PAUSED',
    availableForBooking: false,
    categoryActive: true,
    activeAddOnCount: 1,
    moderationFlags: [],
    ratingAvg: 4.8,
    ratingCount: 18,
    createdAt: '2026-11-14T14:00:00Z',
    updatedAt: '2026-11-17T16:40:00Z',
  },
  {
    id: 'lst-1005',
    providerId: 'prov-1',
    categoryId: 'cat-pipe-drain',
    categoryName: 'Drainage & Sewer Cleaning',
    parentCategoryId: 'cat-plumbing',
    title: 'Main Sewer Line Hydro-Jetting & Drain Cleaning',
    description:
      'Motorized auger and hydro-jet clearance for kitchen grease traps, bathroom floor gullies, and exterior inspection manholes.',
    pricingModel: 'FIXED',
    basePrice: 6500,
    durationMin: 120,
    executionModel: 'ON_SITE',
    district: 'Colombo',
    baseCity: 'Colombo 07',
    location: { longitude: 79.8653, latitude: 6.9125 },
    coverageRadiusKm: 15,
    serviceAreas: ['Colombo 07'],
    images: ['https://cdn.localserve.lk/listings/lst-1005-cover.jpg'],
    coverImageUrl: 'https://cdn.localserve.lk/listings/lst-1005-cover.jpg',
    addOns: [],
    status: 'ACTIVE',
    availableForBooking: false,
    categoryActive: false,
    activeAddOnCount: 0,
    moderationFlags: [],
    ratingAvg: 4.7,
    ratingCount: 15,
    createdAt: '2026-11-11T15:00:00Z',
    updatedAt: '2026-11-16T10:05:00Z',
  },
]

function buildSummary(items) {
  return items.reduce(
    (acc, item) => {
      acc.totalListings += 1
      if (item.status === 'ACTIVE') acc.activeCount += 1
      if (item.status === 'PAUSED') acc.pausedCount += 1
      if (item.status === 'DRAFT') acc.draftCount += 1
      if (item.status === 'ARCHIVED') acc.archivedCount += 1
      if (item.status === 'FLAGGED') acc.flaggedCount += 1
      if (item.status === 'REJECTED') acc.rejectedCount += 1
      if (item.categoryActive === false) acc.categoryInactiveCount += 1
      return acc
    },
    {
      totalListings: 0,
      activeCount: 0,
      pausedCount: 0,
      draftCount: 0,
      archivedCount: 0,
      flaggedCount: 0,
      rejectedCount: 0,
      categoryInactiveCount: 0,
    },
  )
}

function renderWithRouter(initialEntries = ['/provider/listings']) {
  return render(
    <ToastProvider>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/provider/listings" element={<ProviderListingsDashboardPage />} />
          <Route path="/provider/listings/new" element={<CreateEditListingWizardPage />} />
          <Route path="/provider/listings/:listingId/edit" element={<CreateEditListingWizardPage />} />
          <Route path="/provider/verification" element={<div>Provider Verification Page</div>} />
        </Routes>
      </MemoryRouter>
    </ToastProvider>,
  )
}

describe('WP03 Provider Listings Dashboard & 4-Step Wizard UI', () => {
  let dbListings

  beforeEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
    dbListings = MOCK_BACKEND_LISTINGS.map((item) => ({ ...item }))

    vi.spyOn(listingHttp, 'get').mockImplementation(async (url, config = {}) => {
      if (url === '/categories') {
        return { data: FALLBACK_CATEGORIES }
      }
      if (url === '/listings/locations/districts') {
        return { data: SRI_LANKA_PROVINCES_FALLBACK }
      }
      if (url === '/listings/me/summary') {
        return { data: buildSummary(dbListings) }
      }
      if (url === '/listings/me') {
        const params = config.params || {}
        let filtered = [...dbListings]
        if (params.status) {
          filtered = filtered.filter((item) => item.status === params.status)
        }
        if (params.search) {
          const q = params.search.toLowerCase()
          filtered = filtered.filter((item) => item.title.toLowerCase().includes(q))
        }
        return {
          data: {
            content: filtered,
            totalElements: filtered.length,
            totalPages: Math.max(1, Math.ceil(filtered.length / 8)),
            number: 0,
            size: 8,
          },
        }
      }
      if (url.startsWith('/listings/')) {
        const id = url.replace('/listings/', '')
        const found = dbListings.find((item) => item.id === id)
        if (!found) {
          const err = new Error('Listing not found')
          err.status = 404
          err.code = 'LISTING_NOT_FOUND'
          throw err
        }
        return { data: found }
      }
      throw new Error(`Unexpected GET ${url}`)
    })

    vi.spyOn(listingHttp, 'post').mockImplementation(async (url, payload) => {
      const created = {
        id: `lst-${Date.now()}`,
        providerId: 'prov-1',
        ...payload,
        categoryName: 'Plumbing & Water Systems',
        status: payload.saveAsDraft ? 'DRAFT' : 'ACTIVE',
        availableForBooking: !payload.saveAsDraft,
        categoryActive: true,
        activeAddOnCount: (payload.addOns || []).length,
        moderationFlags: [],
        ratingAvg: 0,
        ratingCount: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      dbListings = [created, ...dbListings]
      return { data: created }
    })

    vi.spyOn(listingHttp, 'put').mockImplementation(async (url, payload) => {
      const id = url.replace('/listings/', '')
      const idx = dbListings.findIndex((item) => item.id === id)
      const prevStatus = dbListings[idx]?.status
      const updated = {
        ...dbListings[idx],
        ...payload,
        status: payload.saveAsDraft
          ? 'DRAFT'
          : prevStatus === 'FLAGGED' || prevStatus === 'REJECTED'
            ? 'FLAGGED'
            : 'ACTIVE',
        moderationFlags: [],
        updatedAt: new Date().toISOString(),
      }
      dbListings[idx] = updated
      return { data: updated }
    })

    vi.spyOn(listingHttp, 'patch').mockImplementation(async (url, payload) => {
      const id = url.replace('/listings/', '').replace('/status', '')
      const idx = dbListings.findIndex((item) => item.id === id)
      const updated = {
        ...dbListings[idx],
        status: payload.status,
        availableForBooking: payload.status === 'ACTIVE',
      }
      dbListings[idx] = updated
      return { data: updated }
    })
  })

  afterEach(() => {
    cleanup()
  })

  it('renders KPI metrics, category-inactive policy callout with Edit enabled, Grid/List switch, and Pause/Archive modals', async () => {
    renderWithRouter(['/provider/listings'])

    expect(await screen.findByText('My Service Listings')).toBeTruthy()
    expect(screen.queryByText(/^\d+ Listings$/)).toBeNull()
    expect(await screen.findByText('Emergency Pipe Leak Repair & Waterline Diagnostic')).toBeTruthy()
    expect(screen.getByText(/Public bookings temporarily disabled by admin policy/i)).toBeTruthy()
    expect(screen.getByText('MODERATION & POLICY')).toBeTruthy()

    // Switch to List View and verify real Location & Delivery column
    fireEvent.click(screen.getByRole('button', { name: /☰ List/i }))
    expect(await screen.findByText('PRICE & BILLING MODEL')).toBeTruthy()
    expect(screen.getByText('LOCATION & DELIVERY')).toBeTruthy()

    // Switch back to Cards and open Pause modal
    fireEvent.click(screen.getByRole('button', { name: /⊞ Cards/i }))
    const pauseButtons = await screen.findAllByRole('button', { name: /^Pause$/i })
    const enabledPauseBtn = pauseButtons.find((btn) => !btn.disabled)
    expect(enabledPauseBtn).toBeTruthy()
    fireEvent.click(enabledPauseBtn)

    expect(await screen.findByText('Pause Service Listing?')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Pause Service' }))
    await waitFor(() => expect(screen.queryByText('Pause Service Listing?')).toBeNull())

    // Open Archive modal and confirm
    const archiveButtons = await screen.findAllByRole('button', { name: /^Archive$/i })
    fireEvent.click(archiveButtons[0])
    expect(await screen.findByText(/Service Action Confirmation/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Archive Listing' }))
    await waitFor(() => expect(screen.queryByText(/Service Action Confirmation/i)).toBeNull())

    // Empty state on unmatched search + reset filter
    const searchBox = screen.getByLabelText('Search services')
    fireEvent.change(searchBox, { target: { value: 'zzzz-unmatched-query' } })
    expect(await screen.findByText('No service listings found')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Reset All Filters/i }))
    expect(await screen.findByText('Inverter Split AC Deep Chemical Wash & Coil Flush')).toBeTruthy()
  })

  it('walks through the 4-Step Wizard with FIXED/HOURLY pricing only, 50-char validation, policy warning, AI suggest, and publishes', async () => {
    renderWithRouter(['/provider/listings/new'])

    expect(await screen.findByText('Create Service Listing')).toBeTruthy()

    // Verify Leaflet.js map updates reactively on District & Coverage Radius changes
    const mapBox = screen.getByTestId('leaflet-coverage-map')
    expect(mapBox.getAttribute('data-radius-km')).toBe('15')
    fireEvent.change(screen.getByLabelText(/Base City \/ District/i), {
      target: { value: 'Kandy' },
    })
    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '25' },
    })
    expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-lat')).toBe('7.2906')
    expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-radius-km')).toBe('25')

    // Enter title & test < 50 char description validation + anti-circumvention Policy Warning banner
    fireEvent.change(screen.getByLabelText(/Service Title/i), {
      target: { value: 'Solar Water Heater Annual Service & Pipe Seal' },
    })
    fireEvent.change(screen.getByLabelText(/Detailed Service Scope/i), {
      target: { value: 'Call 0771234567 fast repair.' },
    })
    expect(await screen.findByText(/Platform Policy:/i)).toBeTruthy()

    // Attempting to advance with < 50 chars is blocked cleanly
    fireEvent.click(screen.getByRole('button', { name: /Continue To Pricing & Add-Ons/i }))
    expect(await screen.findByText(/must be at least 50 characters to publish/i)).toBeTruthy()

    // Verify AI Suggest Description button slot remains visible but disabled pending WP01 AI-003, then type clean description (>= 50 chars)
    expect(screen.getByRole('button', { name: /AI Suggest Description/i }).disabled).toBe(true)
    fireEvent.change(screen.getByLabelText(/Detailed Service Scope/i), {
      target: {
        value:
          'Comprehensive on-site inspection, pressure leak testing, valve seal replacement, and tidy post-job cleanup included.',
      },
    })
    expect(screen.queryByText(/Platform Policy:/i)).toBeNull()

    // Advance to Step 2
    fireEvent.click(screen.getByRole('button', { name: /Continue To Pricing & Add-Ons/i }))
    expect(await screen.findByText('Pricing Model & Base Rates')).toBeTruthy()

    // Verify strictly FIXED & HOURLY only (Custom Quote must not exist)
    expect(screen.getByRole('button', { name: /Fixed Price/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Hourly Rate/i })).toBeTruthy()
    expect(screen.queryByText(/Custom Quote/i)).toBeNull()

    // Switch to HOURLY and add an add-on
    fireEvent.click(screen.getByRole('button', { name: /Hourly Rate/i }))
    fireEvent.click(screen.getByRole('button', { name: /Add Another Extra/i }))
    fireEvent.change(screen.getByLabelText(/Add-On Title/i), {
      target: { value: 'Roof Collector Valve Replacement' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save Add-On' }))
    expect(await screen.findByText('Roof Collector Valve Replacement')).toBeTruthy()

    // Advance to Step 3: Portfolio Media
    fireEvent.click(screen.getByRole('button', { name: /Continue To Portfolio Media/i }))
    expect(await screen.findByRole('heading', { name: 'Media Dropzone' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('HTTPS image URL'), {
      target: { value: 'https://cdn.localserve.lk/listings/solar-2.jpg' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add URL' }))
    expect(await screen.findByText(/2 \/ 8/)).toBeTruthy()

    // Advance to Step 4: Review & Publish
    fireEvent.click(screen.getByRole('button', { name: /Continue To Review & Publish/i }))
    expect(await screen.findByText('Basic Details & Geography')).toBeTruthy()
    expect(screen.getByText('Solar Water Heater Annual Service & Pipe Seal')).toBeTruthy()
    expect(screen.getByText('Hourly Rate (LKR)')).toBeTruthy()

    // Publish listing -> returns to dashboard showing newly published item
    fireEvent.click(screen.getByRole('button', { name: /Publish Service Listing/i }))
    expect(await screen.findByText('My Service Listings')).toBeTruthy()
    expect(await screen.findByText('Solar Water Heater Annual Service & Pipe Seal')).toBeTruthy()
  })

  it('shows actionable WP02 PROVIDER_NOT_VERIFIED banner when unverified provider tries to publish and allows saving as draft', async () => {
    const unverifiedErr = new Error('Provider verification must be APPROVED before publishing listings')
    unverifiedErr.status = 403
    unverifiedErr.code = 'PROVIDER_NOT_VERIFIED'
    vi.spyOn(listingHttp, 'post')
      .mockRejectedValueOnce(unverifiedErr)
      .mockImplementationOnce(async (_url, payload) => {
        const draft = {
          id: 'lst-draft-unverified',
          ...payload,
          status: 'DRAFT',
          categoryName: 'Plumbing & Water Systems',
          categoryActive: true,
        }
        dbListings = [draft, ...dbListings]
        return { data: draft }
      })

    renderWithRouter(['/provider/listings/new'])
    expect(await screen.findByText('Create Service Listing')).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/Service Title/i), {
      target: { value: 'Unverified Provider Domestic Pipe Repair' },
    })
    fireEvent.change(screen.getByLabelText(/Detailed Service Scope/i), {
      target: {
        value:
          'Comprehensive on-site inspection, pressure leak testing, valve seal replacement, and tidy post-job cleanup included.',
      },
    })
    fireEvent.click(screen.getByRole('button', { name: /Continue To Pricing & Add-Ons/i }))
    fireEvent.click(screen.getByRole('button', { name: /Continue To Portfolio Media/i }))
    fireEvent.click(screen.getByRole('button', { name: /Continue To Review & Publish/i }))

    fireEvent.click(screen.getByRole('button', { name: /Publish Service Listing/i }))
    expect(await screen.findByText(/Account Verification Required:/i)).toBeTruthy()

    // Clicking "Save as Draft Instead" saves draft with saveAsDraft=true and returns to dashboard
    fireEvent.click(screen.getByRole('button', { name: /Save as Draft Instead/i }))
    expect(await screen.findByText('My Service Listings')).toBeTruthy()
  })

  it('preloads existing listing data in edit mode and enforces WP01 ProviderListingGuard on listingRoutes', async () => {
    renderWithRouter(['/provider/listings/lst-1001/edit'])
    expect(await screen.findByText('Edit Service Listing')).toBeTruthy()
    expect(screen.getByDisplayValue('Emergency Pipe Leak Repair & Waterline Diagnostic')).toBeTruthy()

    const mountedPaths = listingRoutes.map((route) => route.path)
    expect(mountedPaths).toEqual([
      '/provider/listings',
      '/provider/listings/new',
      '/provider/listings/:listingId/edit',
    ])

    // Verify ProviderListingGuard redirects unauthenticated users to /login
    cleanup()
    render(
      <AuthContext.Provider
        value={{
          user: null,
          isAuthenticated: false,
          isLoading: false,
          mustChangePassword: false,
          logout: vi.fn(),
        }}
      >
        <MemoryRouter initialEntries={['/provider/listings']}>
          <Routes>
            <Route
              path="/provider/listings"
              element={
                <ProviderListingGuard>
                  <div>Protected Provider Listings</div>
                </ProviderListingGuard>
              }
            />
            <Route path="/login" element={<div>Login Page Redirected</div>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    expect(await screen.findByText('Login Page Redirected')).toBeTruthy()

    // Verify ProviderListingGuard blocks CUSTOMER role and allows PROVIDER role
    cleanup()
    render(
      <AuthContext.Provider
        value={{
          user: { id: 'prov-1', name: 'Sahan Provider', email: 'provider@test.local', role: ROLES.PROVIDER },
          isAuthenticated: true,
          isLoading: false,
          mustChangePassword: false,
          hasRole: (roles) => roles.includes(ROLES.PROVIDER),
          logout: vi.fn(),
        }}
      >
        <MemoryRouter initialEntries={['/provider/listings']}>
          <Routes>
            <Route
              path="/provider/listings"
              element={
                <ProviderListingGuard>
                  <div>Protected Provider Listings</div>
                </ProviderListingGuard>
              }
            />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    expect(await screen.findByText('Protected Provider Listings')).toBeTruthy()
    expect(screen.getAllByText('Sahan Provider').length).toBeGreaterThan(0)
  })

  it('allows editing a REJECTED listing, shows moderator feedback, hides Save Draft, and resubmits cleanly for review', async () => {
    dbListings.push({
      id: 'lst-rejected-1',
      providerId: 'prov-1',
      categoryId: 'cat-leak-repair',
      categoryName: 'Emergency Leak Repair',
      parentCategoryId: 'cat-plumbing',
      title: 'Quick Home Plumbing Inspection',
      description: 'Call 0771234567 for plumbing inspection.',
      pricingModel: 'FIXED',
      basePrice: 2800,
      durationMin: 90,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      baseCity: 'Colombo 05',
      location: { longitude: 79.8612, latitude: 6.9271 },
      coverageRadiusKm: 15,
      serviceAreas: ['Colombo 05'],
      images: ['https://cdn.localserve.lk/listings/lst-rejected-1-cover.jpg'],
      coverImageUrl: 'https://cdn.localserve.lk/listings/lst-rejected-1-cover.jpg',
      addOns: [],
      status: 'REJECTED',
      availableForBooking: false,
      categoryActive: true,
      activeAddOnCount: 0,
      moderationReason: 'Contains phone number in description',
      moderationFlags: ['PHONE_NUMBER_DETECTED: Listing content appears to include a phone number'],
      ratingAvg: 0,
      ratingCount: 0,
      createdAt: '2026-11-18T10:00:00Z',
      updatedAt: '2026-11-18T11:00:00Z',
    })

    renderWithRouter(['/provider/listings/lst-rejected-1/edit'])

    expect(await screen.findByText('Edit Service Listing')).toBeTruthy()
    expect(screen.getByText(/Rejected by Moderator:/i)).toBeTruthy()
    expect(screen.getByText(/Contains phone number in description/i)).toBeTruthy()
    expect(screen.queryByText(/PHONE_NUMBER_DETECTED/i)).toBeNull()
    expect(screen.queryByText(/\(FLAGGED\)/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /Save Draft/i })).toBeNull()

    // Fix the violation via clean description (>= 50 chars) and advance to Step 4
    fireEvent.change(screen.getByLabelText(/Detailed Service Scope/i), {
      target: {
        value:
          'Comprehensive on-site inspection, pressure leak testing, valve seal replacement, and tidy post-job cleanup included.',
      },
    })
    fireEvent.click(screen.getByRole('button', { name: /Continue To Pricing & Add-Ons/i }))
    expect(await screen.findByText(/Updating your price or duration/i)).toBeTruthy()
    expect(screen.queryByText(/FR-LIST-005/i)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Continue To Portfolio Media/i }))
    fireEvent.click(screen.getByRole('button', { name: /Continue To Review & Publish/i }))

    const resubmitBtn = await screen.findByRole('button', { name: /Save Fixes & Resubmit for Review/i })
    fireEvent.click(resubmitBtn)

    await waitFor(() => {
      expect(listingHttp.put).toHaveBeenCalledWith(
        '/listings/lst-rejected-1',
        expect.objectContaining({ saveAsDraft: false }),
      )
    })
    expect(await screen.findByText('My Service Listings')).toBeTruthy()
  })

  it('auto-syncs Covered Cities & Zones with map radius, protects manual & excluded chips, and preserves saved edit zones', async () => {
    renderWithRouter(['/provider/listings/new'])
    expect(await screen.findByText('Create Service Listing')).toBeTruthy()

    // Switching District to Kandy auto-populates Kandy suburbs
    fireEvent.change(screen.getByLabelText(/Base City \/ District/i), {
      target: { value: 'Kandy' },
    })
    expect(screen.getByLabelText('Remove Kandy')).toBeTruthy()
    expect(screen.getByLabelText('Remove Peradeniya')).toBeTruthy()

    // Expanding radius to 30 km includes wider towns like Nawalapitiya
    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '30' },
    })
    expect(screen.getByLabelText('Remove Nawalapitiya')).toBeTruthy()

    // Add a manual zone and verify it survives shrinking & expanding radius
    fireEvent.click(screen.getByRole('button', { name: /\+ Add City/i }))
    fireEvent.change(screen.getByLabelText('New service area name'), {
      target: { value: 'Custom Estate Lane' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^Add$/i }))
    expect(screen.getByLabelText('Remove Custom Estate Lane')).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '2' },
    })
    expect(screen.getByLabelText('Remove Custom Estate Lane')).toBeTruthy()
    expect(screen.queryByLabelText('Remove Nawalapitiya')).toBeNull()

    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '20' },
    })
    expect(screen.getByLabelText('Remove Custom Estate Lane')).toBeTruthy()
    expect(screen.getByLabelText('Remove Peradeniya')).toBeTruthy()

    // Excluding an auto-matched zone prevents slider drags from re-adding it
    fireEvent.click(screen.getByLabelText('Remove Peradeniya'))
    expect(screen.queryByLabelText('Remove Peradeniya')).toBeNull()
    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '25' },
    })
    expect(screen.queryByLabelText('Remove Peradeniya')).toBeNull()

    // Resetting sync via ↺ Sync restores excluded auto zones while keeping manual zones
    fireEvent.click(screen.getByRole('button', { name: 'Sync zones from map' }))
    expect(screen.getByLabelText('Remove Peradeniya')).toBeTruthy()
    expect(screen.getByLabelText('Remove Custom Estate Lane')).toBeTruthy()

    // Unchecking Auto-sync with radius freezes zone list across slider changes
    const autoSyncCheckbox = screen.getByLabelText(/Auto-sync with radius/i)
    fireEvent.click(autoSyncCheckbox)
    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '0' },
    })
    expect(screen.getByLabelText('Remove Peradeniya')).toBeTruthy()
    expect(screen.getByLabelText('Remove Custom Estate Lane')).toBeTruthy()

    // Edit Mode preserves existing saved serviceAreas on initial load without overwriting
    cleanup()
    renderWithRouter(['/provider/listings/lst-1001/edit'])
    expect(await screen.findByText('Edit Service Listing')).toBeTruthy()
    expect(screen.getByLabelText('Remove Colombo 01-15')).toBeTruthy()
    expect(screen.getByLabelText('Remove Dehiwala')).toBeTruthy()
    expect(screen.queryByLabelText('Remove Nugegoda')).toBeNull()
  })

  it('blocks out-of-Sri-Lanka VPN/IP GPS coordinates from flipping to Jaffna and accepts in-country GPS', async () => {
    const originalGeoDescriptor = Object.getOwnPropertyDescriptor(window.navigator, 'geolocation')
    const mockGetCurrentPosition = vi
      .fn()
      .mockImplementationOnce((success) => {
        success({ coords: { latitude: 60.1713, longitude: 24.9298 } })
      })
      .mockImplementationOnce((success) => {
        success({ coords: { latitude: 6.0535, longitude: 80.221 } })
      })

    Object.defineProperty(window.navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: mockGetCurrentPosition },
    })

    try {
      renderWithRouter(['/provider/listings/new'])
      expect(await screen.findByText('Create Service Listing')).toBeTruthy()

      // First click returns Helsinki VPN coordinates -> keep Colombo and stay within Sri Lanka
      fireEvent.click(screen.getByRole('button', { name: /Detect Via GPS/i }))
      expect(await screen.findByText(/is outside Sri Lanka/i)).toBeTruthy()
      expect(screen.getByLabelText(/Base City \/ District/i).value).toBe('Colombo')
      expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-lat')).toBe('6.9271')

      // Second click returns valid Galle coordinates -> resolves Galle & syncs Galle zones
      fireEvent.click(screen.getByRole('button', { name: /Detect Via GPS/i }))
      expect(screen.getByLabelText(/Base City \/ District/i).value).toBe('Galle')
      expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-lat')).toBe('6.0535')
      expect(screen.getByLabelText('Remove Galle')).toBeTruthy()
    } finally {
      if (originalGeoDescriptor) {
        Object.defineProperty(window.navigator, 'geolocation', originalGeoDescriptor)
      } else {
        delete window.navigator.geolocation
      }
    }
  })
})
