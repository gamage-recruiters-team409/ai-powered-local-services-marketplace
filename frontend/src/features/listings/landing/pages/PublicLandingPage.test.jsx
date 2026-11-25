// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import PublicLandingPage from './PublicLandingPage'

const mockGetPublicCategoryTree = vi.fn()
const mockGetFeaturedPublicServices = vi.fn()
const mockGetPublicDistrictLocations = vi.fn()
const mockGetLiveCustomerReviews = vi.fn()

vi.mock('../services/publicCatalogService', () => ({
  getPublicCategoryTree: (...args) => mockGetPublicCategoryTree(...args),
  getFeaturedPublicServices: (...args) => mockGetFeaturedPublicServices(...args),
  getPublicDistrictLocations: (...args) => mockGetPublicDistrictLocations(...args),
  getLiveCustomerReviews: (...args) => mockGetLiveCustomerReviews(...args),
}))

const SAMPLE_LIVE_CATEGORIES = [
  {
    id: 'cat-plumbing',
    name: 'Plumbing & Water Systems',
    slug: 'plumbing-and-water-systems',
    description: 'Pipe leak repair, water tanks, and drainage services.',
    icon: 'water',
    active: true,
    subcategories: [
      { id: 'sub-leak', name: 'Pipe Leak Repair', slug: 'pipe-leak-repair' },
    ],
  },
  {
    id: 'cat-electrical',
    name: 'Electrical & Solar Systems',
    slug: 'electrical-and-solar-systems',
    description: 'Wiring, breaker repair, and solar inverter servicing.',
    icon: 'sun',
    active: true,
    subcategories: [
      { id: 'sub-solar', name: 'Solar Inverter Maintenance', slug: 'solar-inverter-maintenance' },
    ],
  },
]

const SAMPLE_LIVE_DISTRICTS = [
  { id: 'colombo', city: 'Colombo', province: 'Western Province', lat: 6.9271, lng: 79.8612, zoom: 11 },
  { id: 'gampaha', city: 'Gampaha', province: 'Western Province', lat: 7.0873, lng: 80.0144, zoom: 11 },
  { id: 'kandy', city: 'Kandy', province: 'Central Province', lat: 7.2906, lng: 80.6337, zoom: 11 },
  { id: 'galle', city: 'Galle', province: 'Southern Province', lat: 6.0535, lng: 80.221, zoom: 11 },
  { id: 'jaffna', city: 'Jaffna', province: 'Northern Province', lat: 9.6615, lng: 80.0255, zoom: 11 },
  { id: 'matara', city: 'Matara', province: 'Southern Province', lat: 5.9549, lng: 80.555, zoom: 11 },
]

function renderLanding(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/" element={<PublicLandingPage />} />
        <Route
          path="/search"
          element={<div data-testid="search-destination">Search Destination</div>}
        />
        <Route
          path="/register"
          element={<div data-testid="register-destination">Register Destination</div>}
        />
      </Routes>
    </MemoryRouter>
  )
}

describe('PublicLandingPage (100% SRS-Accurate, Live-API Public UI)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetPublicCategoryTree.mockResolvedValue(SAMPLE_LIVE_CATEGORIES)
    mockGetPublicDistrictLocations.mockResolvedValue(SAMPLE_LIVE_DISTRICTS)
    mockGetFeaturedPublicServices.mockResolvedValue({ results: [], totalCount: 0 })
    mockGetLiveCustomerReviews.mockResolvedValue({ reviews: [], requiresAuth: true })
  })

  it('renders SRS-accurate hero, trust metrics, customer reviews empty state, footer, and zero fabricated demo claims', async () => {
    renderLanding()

    expect(
      screen.getByRole('heading', { name: /Hire verified pros with absolute certainty\./i })
    ).toBeTruthy()

    // SRS-grounded Trust Metrics (100%, 48 hrs, 72 hrs, 25)
    expect(screen.getAllByText('Admin-Verified Providers').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('Completion Review Window')).toBeTruthy()
    expect(screen.getByText('Escrow Dispute Protection')).toBeTruthy()
    expect(screen.getByText('Sri Lankan Districts Supported')).toBeTruthy()

    // Live backend categories rendered
    expect(await screen.findByText('2 Primary Categories')).toBeTruthy()
    expect(screen.getByText('2 Subcategories')).toBeTruthy()

    // Honest Customer-to-Provider Reviews empty state when no live reviews exist yet
    expect(
      screen.getByRole('heading', { name: /Verified Customer Reviews/i })
    ).toBeTruthy()
    expect(
      await screen.findByText(/No Customer Reviews Published Yet/i)
    ).toBeTruthy()
    expect(screen.getByRole('link', { name: /Sign In to View Reviews/i })).toBeTruthy()

    // Footer with Gamage Recruiters (PVT) Ltd. operating company
    const footer = screen.getByRole('contentinfo', { name: /site footer/i })
    expect(within(footer).getByText('Services')).toBeTruthy()
    expect(within(footer).getByText('Top Cities')).toBeTruthy()
    expect(within(footer).getByText(/Platform & Contact/i)).toBeTruthy()
    expect(within(footer).getByText('support@localserve.lk')).toBeTruthy()
    expect(within(footer).getByText('© 2026 LocalServe · Gamage Recruiters (PVT) Ltd.')).toBeTruthy()

    // Strictly verify zero fake demo data / SRS contradictions remain
    expect(screen.queryByText(/Rs\. 1,000,000/i)).toBeNull()
    expect(screen.queryByText(/0% Middleman/i)).toBeNull()
    expect(screen.queryByText(/42 vetted pros/i)).toBeNull()
    expect(screen.queryByText(/Duminda P\./i)).toBeNull()
    expect(screen.queryByText(/CoolTech NVQ/i)).toBeNull()
    expect(screen.queryByText(/Direct WhatsApp/i)).toBeNull()
  })

  it('renders real live customer-to-provider reviews inside the left-gliding marquee when available', async () => {
    mockGetLiveCustomerReviews.mockResolvedValue({
      requiresAuth: false,
      reviews: [
        {
          id: 'rev-live-1',
          initials: 'VC',
          customerName: 'Verified Customer',
          providerName: 'SunPower Lanka',
          location: 'Colombo',
          serviceTag: 'Electrical & Solar Systems',
          rating: 5,
          quote: 'Arrived on time and completed the inverter inspection neatly.',
          meta: 'Verified Booking · Nov 20, 2026',
        },
        {
          id: 'rev-live-2',
          initials: 'VC',
          customerName: 'Verified Customer',
          providerName: 'AquaFix Tech',
          location: 'Kandy',
          serviceTag: 'Plumbing & Water Systems',
          rating: 4,
          quote: 'Fixed the overhead tank valve cleanly at the locked quote.',
          meta: 'Verified Booking · Nov 21, 2026',
        },
        {
          id: 'rev-live-3',
          initials: 'VC',
          customerName: 'Verified Customer',
          providerName: 'CoolAir Pros',
          location: 'Galle',
          serviceTag: 'AC & Cooling',
          rating: 5,
          quote: 'Very thorough AC servicing and transparent billing.',
          meta: 'Verified Booking · Nov 22, 2026',
        },
      ],
    })

    renderLanding()

    const marquee = await screen.findByRole('region', {
      name: /verified customer reviews marquee/i,
    })
    expect(within(marquee).getAllByText(/SunPower Lanka/i).length).toBeGreaterThan(0)
    expect(within(marquee).getAllByText(/AquaFix Tech/i).length).toBeGreaterThan(0)
    expect(within(marquee).getAllByText(/CoolAir Pros/i).length).toBeGreaterThan(0)
  })

  it('syncs district selection across hero pills, navbar, directory, and live search requests', async () => {
    const user = userEvent.setup()
    renderLanding()

    const cityGroup = screen.getByRole('group', { name: /select service city/i })
    await user.click(within(cityGroup).getByRole('button', { name: 'Kandy' }))

    expect(screen.getByRole('button', { name: /Selected city: Kandy/i })).toBeTruthy()
    expect(screen.getByText(/Available in Kandy & Suburbs/i)).toBeTruthy()
    expect(mockGetFeaturedPublicServices).toHaveBeenCalledWith(
      expect.objectContaining({ location: 'Kandy' })
    )
  })

  it('submits the hero search bar to /search', async () => {
    const user = userEvent.setup()
    renderLanding()

    const searchInput = screen.getByLabelText(/service, trade, or issue/i)
    await user.type(searchInput, 'Solar Inverter')
    await user.click(screen.getByRole('button', { name: /find service/i }))

    expect(await screen.findByTestId('search-destination')).toBeTruthy()
  })

  it('steps through the SRS Escrow & Dispute Lifecycle walkthrough (BR-004 – BR-009)', async () => {
    const user = userEvent.setup()
    renderLanding()

    const simulator = screen.getByRole('region', {
      name: /interactive escrow protection simulator/i,
    })
    expect(within(simulator).getByText('Payment Held in Escrow')).toBeTruthy()
    expect(within(simulator).getByText('100% Held in Escrow')).toBeTruthy()

    await user.click(within(simulator).getByRole('tab', { name: /3\. 72h Cleared & Payout/i }))
    expect(within(simulator).getByText('Released to Provider Earnings')).toBeTruthy()
    expect(within(simulator).getByText('Net Payout (After Commission)')).toBeTruthy()
  })

  it('filters live backend categories in real time and supports 0-subcategory direct booking chips', async () => {
    const user = userEvent.setup()
    mockGetPublicCategoryTree.mockResolvedValueOnce([
      ...SAMPLE_LIVE_CATEGORIES,
      {
        id: 'db-cat-cctv',
        name: 'CCTV & Smart Locks',
        slug: 'cctv-smart-locks',
        description: 'Residential camera and smart security setups.',
        icon: 'camera',
        active: true,
        subcategories: [],
      },
    ])

    renderLanding()

    expect(await screen.findByText('CCTV & Smart Locks')).toBeTruthy()
    expect(screen.getByText('Direct Booking')).toBeTruthy()

    const directoryHeading = screen.getByRole('heading', {
      name: /explore service directory by category/i,
    })
    const directorySection = directoryHeading.closest('section')
    const filterInput = within(directorySection).getByLabelText(
      /filter categories or subcategories/i
    )
    await user.type(filterInput, 'Solar Inverter')

    expect(within(directorySection).getByText('Electrical & Solar Systems')).toBeTruthy()
    expect(within(directorySection).getByText('Solar Inverter Maintenance')).toBeTruthy()
    expect(within(directorySection).queryByText('Plumbing & Water Systems')).toBeNull()

    await user.clear(filterInput)
    await user.type(filterInput, 'NonExistentTradeXYZ')
    expect(within(directorySection).getByRole('button', { name: /clear filter/i })).toBeTruthy()
  })

  it('renders honest empty states when live categories and live services return empty (Q2 Option A)', async () => {
    mockGetPublicCategoryTree.mockResolvedValueOnce([])
    mockGetFeaturedPublicServices.mockResolvedValue({ results: [], totalCount: 0 })

    renderLanding()

    expect(
      await screen.findByText(/No active service categories are published in the catalogue yet\./i)
    ).toBeTruthy()
    expect(
      screen.getByText(/No active verified listings are published in Colombo yet\./i)
    ).toBeTruthy()
  })

  it('syncs selectedCity on the real Leaflet Sri Lanka map using official backend districts and navigates on search', async () => {
    const user = userEvent.setup()
    renderLanding()

    const leafletCanvas = screen.getByTestId('landing-leaflet-map')
    expect(leafletCanvas.getAttribute('data-city')).toBe('Colombo')
    expect(leafletCanvas.getAttribute('data-view-mode')).toBe('island')

    const mapGroup = screen.getByRole('group', {
      name: /interactive sri lanka district coverage map/i,
    })
    await user.click(within(mapGroup).getByRole('button', { name: /Galle \(Southern Province\)/i }))

    expect(leafletCanvas.getAttribute('data-city')).toBe('Galle')
    expect(leafletCanvas.getAttribute('data-view-mode')).toBe('city')
    expect(screen.getByRole('button', { name: /Selected city: Galle/i })).toBeTruthy()
    expect(screen.getByText(/Available in Galle & Suburbs/i)).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Island View' }))
    expect(leafletCanvas.getAttribute('data-view-mode')).toBe('island')

    const quickFinder = screen.getByRole('form', {
      name: /quick district and service finder/i,
    })
    await user.click(within(quickFinder).getByRole('button', { name: /Search .* in Galle/i }))

    expect(await screen.findByTestId('search-destination')).toBeTruthy()
  })

  it('renders live Featured Services from GET /api/v1/search with dynamic category tabs', async () => {
    const user = userEvent.setup()
    mockGetFeaturedPublicServices.mockResolvedValue({
      totalCount: 2,
      results: [
        {
          id: 'live-1',
          profileId: null,
          title: 'Inverter Battery Replacement Service',
          categoryName: 'Electrical & Solar Systems',
          providerName: 'SunPower Lanka',
          location: 'Colombo',
          priceAmount: 5500,
          pricingModel: 'FIXED',
          rating: 4.9,
          reviewCount: 18,
          verified: true,
          badge: 'Admin-Verified Pro',
        },
        {
          id: 'live-2',
          profileId: null,
          title: 'Main Pipe Leak Inspection',
          categoryName: 'Plumbing & Water Systems',
          providerName: 'Colombo Hydro Tech',
          location: 'Colombo',
          priceAmount: 3000,
          pricingModel: 'HOURLY',
          rating: null,
          reviewCount: 0,
          verified: true,
          badge: 'Admin-Verified Pro',
        },
      ],
    })

    renderLanding()

    expect(await screen.findByText('Live Marketplace Listings')).toBeTruthy()
    expect(screen.getByText('Inverter Battery Replacement Service')).toBeTruthy()
    expect(screen.getByText('Main Pipe Leak Inspection')).toBeTruthy()
    expect(screen.getByText('New Listing')).toBeTruthy()

    const tabList = screen.getByRole('tablist', { name: /filter featured services by trade/i })
    const elecTab = within(tabList).getByRole('tab', { name: 'Electrical & Solar Systems' })
    await user.click(elecTab)
    expect(elecTab.getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('Inverter Battery Replacement Service')).toBeTruthy()
    expect(screen.queryByText('Main Pipe Leak Inspection')).toBeNull()
  })

  it('renders Provider Community Banner CTA linking to /register?role=provider', async () => {
    renderLanding()

    expect(
      screen.getByRole('heading', { name: /Skilled Trade Professionals Are Growing Here\./i })
    ).toBeTruthy()

    const ctaLink = screen.getByRole('link', { name: /Join as a Verified Pro/i })
    expect(ctaLink.getAttribute('href')).toBe('/register?role=provider')
  })

  it('renders Option B Split FAQ accordion with Contact Support link (no input box) and SRS-accurate answers', async () => {
    const user = userEvent.setup()
    renderLanding()

    const faqSection = screen
      .getByRole('heading', { name: /Got A Question For LocalServe\?/i })
      .closest('section')

    const supportLink = within(faqSection).getByRole('link', { name: /Contact Support/i })
    expect(supportLink.getAttribute('href')).toContain('mailto:support@localserve.lk')
    expect(within(faqSection).queryByRole('textbox')).toBeNull()

    const pricingFaqBtn = within(faqSection).getByRole('button', {
      name: /How do pricing, platform commissions, and cancellations work\?/i,
    })
    expect(pricingFaqBtn.getAttribute('aria-expanded')).toBe('false')

    await user.click(pricingFaqBtn)
    expect(pricingFaqBtn.getAttribute('aria-expanded')).toBe('true')
    expect(within(faqSection).getByText(/default 10%/i)).toBeTruthy()
  })
})
