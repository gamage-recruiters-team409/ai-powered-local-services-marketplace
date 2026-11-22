// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import PublicLandingPage from './PublicLandingPage'

const mockGetPublicCategoryTree = vi.fn()

vi.mock('../services/publicCatalogService', () => ({
  getPublicCategoryTree: (...args) => mockGetPublicCategoryTree(...args),
}))

function renderLanding(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/" element={<PublicLandingPage />} />
        <Route
          path="/search"
          element={<div data-testid="search-destination">Search Destination</div>}
        />
      </Routes>
    </MemoryRouter>
  )
}

describe('PublicLandingPage (LocalServe Scroll-Animated Public UI)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetPublicCategoryTree.mockResolvedValue([])
  })

  it('renders hero headline, trust metrics, 5 Sri Lankan categories, 6 verified reviews, and footer', async () => {
    renderLanding()

    expect(
      screen.getByRole('heading', { name: /Hire verified pros with absolute certainty\./i })
    ).toBeTruthy()
    expect(screen.getByText(/42 vetted pros available in/i)).toBeTruthy()

    // Trust Metrics Strip (4 metrics)
    expect(screen.getByText('Middleman Fees (Direct Booking)')).toBeTruthy()
    expect(screen.getByText('NIC & NVQ Vetted Pros')).toBeTruthy()
    expect(screen.getByText('Platform Escrow Cover')).toBeTruthy()
    expect(screen.getByText('Pre-Payment Scams')).toBeTruthy()

    // Service Directory count badge (5 Primary Categories · 20 Subcategories)
    expect(screen.getByText('5 Primary Categories')).toBeTruthy()
    expect(screen.getByText('20 Subcategories')).toBeTruthy()
    expect(screen.getByText('Plumbing & Water Systems')).toBeTruthy()
    expect(screen.getByText('Electrical & Solar Systems')).toBeTruthy()
    expect(screen.getByText('AC & Cooling Services')).toBeTruthy()
    expect(screen.getByText('Home Deep Cleaning & Pest Control')).toBeTruthy()
    expect(screen.getByText('Carpentry, Masonry & Painting')).toBeTruthy()

    // Verified Reviews & Footer
    expect(screen.getAllByText('Duminda P.').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Sanduni R.').length).toBeGreaterThan(0)
    expect(screen.getByText('© 2026 LocalServe - Sri Lanka')).toBeTruthy()
  })

  it('syncs city selection between hero pills, availability indicator, navbar, and directory', async () => {
    const user = userEvent.setup()
    renderLanding()

    const cityGroup = screen.getByRole('group', { name: /select service city/i })
    await user.click(within(cityGroup).getByRole('button', { name: 'Kandy' }))

    expect(screen.getByText(/28 vetted pros available in/i)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Selected city: Kandy/i })).toBeTruthy()
    expect(screen.getByText(/Available in Kandy & Suburbs/i)).toBeTruthy()
  })

  it('submits the hero search bar to /search', async () => {
    const user = userEvent.setup()
    renderLanding()

    const searchInput = screen.getByLabelText(/service, trade, or issue/i)
    await user.type(searchInput, 'Solar Inverter')
    await user.click(screen.getByRole('button', { name: /find service/i }))

    expect(await screen.findByTestId('search-destination')).toBeTruthy()
  })

  it('steps through the interactive Rs. 25,000 Escrow Protection Simulator', async () => {
    const user = userEvent.setup()
    renderLanding()

    const simulator = screen.getByRole('region', {
      name: /interactive escrow protection simulator/i,
    })
    expect(within(simulator).getByText('Locked Safely in Escrow')).toBeTruthy()
    expect(within(simulator).getAllByText('Rs. 25,000').length).toBeGreaterThanOrEqual(2)

    await user.click(within(simulator).getByRole('tab', { name: /3\. Customer Signs Off/i }))
    expect(within(simulator).getByText('Escrow Released to Pro')).toBeTruthy()
    expect(within(simulator).getByText('Rs. 25,000 Released')).toBeTruthy()
  })

  it('filters the Service Directory in real time across categories and subcategories', async () => {
    const user = userEvent.setup()
    renderLanding()

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

  it('renders live backend categories cleanly without stock photos including 0-subcategory direct chips', async () => {
    mockGetPublicCategoryTree.mockResolvedValueOnce([
      {
        id: 'db-cat-1',
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
    expect(screen.getByText('Residential camera and smart security setups.')).toBeTruthy()
    expect(screen.getByText('Direct Booking')).toBeTruthy()
    expect(
      screen.getByRole('button', { name: /Verified pros available for direct booking/i })
    ).toBeTruthy()
    expect(screen.getByRole('button', { name: /Explore CCTV/i })).toBeTruthy()
  })
})
