// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom'
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

function SearchDestination() {
  const loc = useLocation()
  return (
    <div data-testid="search-destination" data-search={loc.search}>
      Search Destination
    </div>
  )
}

function ProviderDestination() {
  const { providerId } = useParams()
  return (
    <div data-testid="provider-destination" data-provider-id={providerId}>
      Provider {providerId}
    </div>
  )
}

function renderLanding(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/" element={<PublicLandingPage />} />
        <Route path="/search" element={<SearchDestination />} />
        <Route path="/providers/:providerId" element={<ProviderDestination />} />
        <Route
          path="/login"
          element={<div data-testid="login-destination">Login Destination</div>}
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
    mockGetLiveCustomerReviews.mockResolvedValue({
      reviews: [],
      requiresAuth: true,
      failed: false,
    })
  })

  it('renders SRS-accurate hero, trust metrics, guest review sign-in state, footer, and zero fabricated demo claims', async () => {
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

    // F4: Guest auth-required state must NOT claim zero reviews exist
    expect(
      screen.getByRole('heading', { name: /Verified Customer Reviews/i })
    ).toBeTruthy()
    expect(
      await screen.findByRole('heading', {
        name: /Sign In to View Verified Customer Reviews/i,
      })
    ).toBeTruthy()
    expect(screen.queryByText(/No Customer Reviews Published Yet/i)).toBeNull()
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

  it('filters live backend categories in real time and uses neutral browsing wording for 0-subcategory categories (F5)', async () => {
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

    const directoryHeading = screen.getByRole('heading', {
      name: /explore service directory by category/i,
    })
    const directorySection = directoryHeading.closest('section')
    expect(within(directorySection).getByText('Service Category')).toBeTruthy()
    expect(
      within(directorySection).getByRole('button', { name: /Browse services in this category/i })
    ).toBeTruthy()
    expect(within(directorySection).queryByText('Direct Booking')).toBeNull()
    expect(within(directorySection).queryByText(/Verified pros available for booking/i)).toBeNull()
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

  it('renders live Featured Services truthfully from ProviderSearchResult DTO without duplicating provider name (F2)', async () => {
    const user = userEvent.setup()
    mockGetFeaturedPublicServices.mockResolvedValue({
      totalCount: 2,
      results: [
        {
          id: 'list-1',
          listingId: 'list-1',
          providerId: 'prov-kamal',
          profileId: 'prof-kamal',
          categoryName: 'Electrical & Solar Systems',
          providerName: 'Kamal Perera',
          description: 'Full residential electrical installation and solar inverter servicing.',
          location: 'Colombo',
          coverageRadiusKm: 15,
          priceAmount: 5500,
          pricingModel: 'FIXED',
          rating: 4.9,
          reviewCount: 18,
          verified: true,
          badge: 'Admin-Verified Pro',
        },
        {
          id: 'list-2',
          listingId: 'list-2',
          providerId: 'prov-hydro',
          profileId: null,
          categoryName: 'Plumbing & Water Systems',
          providerName: 'Colombo Hydro Tech',
          description: 'Main pipe leak diagnostics and overhead tank plumbing.',
          location: 'Colombo',
          coverageRadiusKm: 10,
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
    // Provider display name must appear ONCE per card (never duplicated as both title and providerName)
    expect(screen.getAllByText('Kamal Perera')).toHaveLength(1)
    expect(
      screen.getByText('Full residential electrical installation and solar inverter servicing.')
    ).toBeTruthy()
    expect(screen.getByText('15 km radius')).toBeTruthy()
    expect(screen.getAllByText('Colombo Hydro Tech')).toHaveLength(1)
    expect(screen.getByText('New Listing')).toBeTruthy()

    const tabList = screen.getByRole('tablist', { name: /filter featured services by trade/i })
    const elecTab = within(tabList).getByRole('tab', { name: 'Electrical & Solar Systems' })
    await user.click(elecTab)
    expect(elecTab.getAttribute('aria-selected')).toBe('true')
    expect(screen.getAllByText('Kamal Perera')).toHaveLength(1)
    expect(screen.queryByText('Colombo Hydro Tech')).toBeNull()

    // Clicking View Provider routes to /providers/:profileId
    await user.click(screen.getByRole('button', { name: /View Provider/i }))
    const dest = await screen.findByTestId('provider-destination')
    expect(dest.getAttribute('data-provider-id')).toBe('prof-kamal')
  })

  it('falls back to providerId route when profileId is null on a featured card (F2)', async () => {
    const user = userEvent.setup()
    mockGetFeaturedPublicServices.mockResolvedValue({
      totalCount: 1,
      results: [
        {
          id: 'list-99',
          listingId: 'list-99',
          providerId: 'prov-only-99',
          profileId: null,
          categoryName: 'Plumbing & Water Systems',
          providerName: 'Nimal Plumbing Works',
          description: 'Emergency leak repair.',
          location: 'Colombo',
          coverageRadiusKm: null,
          priceAmount: 2500,
          pricingModel: 'FIXED',
          rating: null,
          reviewCount: 0,
          verified: true,
          badge: 'Admin-Verified Pro',
        },
      ],
    })

    renderLanding()

    const ctaBtn = await screen.findByRole('button', { name: /View Provider/i })
    await user.click(ctaBtn)
    const dest = await screen.findByTestId('provider-destination')
    expect(dest.getAttribute('data-provider-id')).toBe('prov-only-99')
  })

  it('preserves districts outside the 5 preset hero cities (e.g. Matara) and All Districts islandwide selection without falling back to Colombo (F3)', async () => {
    const user = userEvent.setup()
    renderLanding()

    // Wait for canonical live districts (including Matara) to load
    await screen.findByText('2 Primary Categories')

    // Open coverage-map district popover and select Matara
    const districtTrigger = screen.getByRole('button', { name: /Select district: Colombo/i })
    await user.click(districtTrigger)

    const districtListbox = screen.getByRole('listbox', {
      name: /Available Sri Lankan districts/i,
    })
    await user.click(within(districtListbox).getByRole('option', { name: /Matara/i }))

    // Hero eyebrow, Hero active city pill, Navbar, Map, Directory, and search call must all show Matara (never Colombo)
    const heroSection = screen.getByRole('region', {
      name: /LocalServe Verified Home Services Showcase/i,
    })
    expect(within(heroSection).getByText(/Southern Province/i)).toBeTruthy()
    expect(within(heroSection).queryByText(/Western Province/i)).toBeNull()

    const cityGroup = within(heroSection).getByRole('group', { name: /select service city/i })
    const mataraPill = within(cityGroup).getByRole('button', { name: 'Matara' })
    expect(mataraPill.getAttribute('aria-pressed')).toBe('true')

    expect(screen.getByRole('button', { name: /Selected city: Matara/i })).toBeTruthy()
    expect(screen.getByText(/Available in Matara & Suburbs/i)).toBeTruthy()
    expect(screen.getByTestId('landing-leaflet-map').getAttribute('data-city')).toBe('Matara')
    expect(mockGetFeaturedPublicServices).toHaveBeenCalledWith(
      expect.objectContaining({ location: 'Matara' })
    )

    // Switch to All Districts islandwide selection
    await user.click(within(cityGroup).getByRole('button', { name: 'All Districts' }))

    expect(within(heroSection).getByText(/Islandwide/i)).toBeTruthy()
    expect(within(heroSection).queryByText(/Western Province/i)).toBeNull()
    expect(screen.getByRole('button', { name: /Selected city: All Districts/i })).toBeTruthy()
    expect(screen.getByText(/Available Islandwide \(25 Districts\)/i)).toBeTruthy()
    expect(screen.getByTestId('landing-leaflet-map').getAttribute('data-city')).toBe(
      'All Districts'
    )
    expect(screen.getByTestId('landing-leaflet-map').getAttribute('data-view-mode')).toBe('island')
    expect(mockGetFeaturedPublicServices).toHaveBeenCalledWith(
      expect.objectContaining({ location: 'All Districts' })
    )
  })

  it('renders genuine empty state for authenticated users when zero reviews exist (F4)', async () => {
    mockGetLiveCustomerReviews.mockResolvedValue({
      reviews: [],
      requiresAuth: false,
      failed: false,
    })

    renderLanding()

    expect(
      await screen.findByRole('heading', { name: /No Customer Reviews Published Yet/i })
    ).toBeTruthy()
    expect(screen.queryByRole('link', { name: /Sign In to View Reviews/i })).toBeNull()
    expect(screen.queryByText(/Unable to Load Customer Reviews Right Now/i)).toBeNull()
  })

  it('renders dedicated error state with working retry when authenticated review fetch fails (F4)', async () => {
    const user = userEvent.setup()
    mockGetLiveCustomerReviews
      .mockResolvedValueOnce({
        reviews: [],
        requiresAuth: false,
        failed: true,
      })
      .mockResolvedValueOnce({
        reviews: [
          {
            id: 'rev-retry-1',
            initials: 'NS',
            customerName: 'Nadeesha Silva',
            providerName: 'SunPower Lanka',
            location: 'Colombo',
            serviceTag: 'Electrical & Solar Systems',
            rating: 5,
            quote: 'Recovered review after retry.',
            meta: 'Verified Booking · Nov 25, 2026',
          },
        ],
        requiresAuth: false,
        failed: false,
      })

    renderLanding()

    expect(
      await screen.findByRole('heading', { name: /Unable to Load Customer Reviews Right Now/i })
    ).toBeTruthy()
    expect(screen.queryByText(/No Customer Reviews Published Yet/i)).toBeNull()

    await user.click(screen.getByRole('button', { name: /Retry Loading Reviews/i }))

    expect(await screen.findByText(/Recovered review after retry\./i)).toBeTruthy()
  })

  it('falls back to islandwide verified search when the selected district has zero verified listings (F1)', async () => {
    mockGetFeaturedPublicServices
      .mockResolvedValueOnce({ results: [], totalCount: 0 })
      .mockResolvedValueOnce({
        totalCount: 1,
        results: [
          {
            id: 'list-island-1',
            listingId: 'list-island-1',
            providerId: 'prov-island-1',
            profileId: 'prof-island-1',
            categoryName: 'Electrical & Solar Systems',
            providerName: 'Islandwide Solar Pro',
            description: 'Islandwide certified inverter maintenance.',
            location: 'Kandy',
            coverageRadiusKm: 25,
            priceAmount: 7000,
            pricingModel: 'FIXED',
            rating: 5,
            reviewCount: 4,
            verified: true,
            badge: 'Admin-Verified Pro',
          },
        ],
      })

    renderLanding()

    expect(await screen.findByText('Islandwide Live Listings')).toBeTruthy()
    expect(screen.getByText('Islandwide Solar Pro')).toBeTruthy()
    expect(mockGetFeaturedPublicServices).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ location: 'Colombo', size: 6 })
    )
    expect(mockGetFeaturedPublicServices).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ location: 'All Districts', size: 6 })
    )
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
