// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({ current: null }))

vi.mock('../../auth/hooks/useAuth', () => ({
  useAuth: () => auth.current,
  default: () => auth.current,
}))

vi.mock('../../customer-account/services/customerAccountApi', () => ({
  getProfile: vi.fn().mockResolvedValue({
    fullName: 'Nimal Perera',
    email: 'nimal@example.com',
    emailVerified: true,
    phoneNumber: '+94 77 123 4567',
  }),
  updateProfile: vi.fn().mockResolvedValue({}),
  getAddresses: vi.fn().mockResolvedValue([]),
  getDistricts: vi.fn().mockResolvedValue([]),
  getFavorites: vi.fn().mockResolvedValue([]),
}))

vi.mock('../services/disputesApi', () => ({
  fetchDispute: vi.fn(),
  fetchMyDisputes: vi.fn(),
  raiseDispute: vi.fn(),
  submitAppeal: vi.fn(),
  uploadEvidence: vi.fn(),
  fetchEvidenceUrl: vi.fn(),
  submitDisputeResponse: vi.fn(),
  fetchCurrentUser: vi.fn(),
  isDisputeFixtureMode: () => false,
  isRequestCancelled: () => false,
}))

vi.mock('../../disputes-admin/services/authBridge', () => ({
  LOGIN_PATH: '/login',
  hasSession: () => true,
  endSession: vi.fn(),
  getAccessToken: () => 'test-session',
}))

import { CUSTOMER_NAV_SECTIONS } from '../../../shared/layouts/customer/customerNavConfig'
import { ToastProvider } from '../../../shared/components/feedback'
import { customerRoutes } from '../../../routes/customer/customerRoutes'
import { fetchCurrentUser, fetchDispute, fetchMyDisputes } from '../services/disputesApi'
import { disputeRoutes } from './disputeRoutes'

const HOUR = 60 * 60 * 1000
const DISPUTE_ID = '6a0f00c0ffee00a000001045'

function dispute(overrides = {}) {
  const createdAt = new Date(Date.now() - 10 * HOUR).toISOString()
  return {
    id: DISPUTE_ID,
    bookingId: '6a0f00c0ffee00b000048195',
    customerId: 'u-1',
    providerId: 'p-1',
    raisedBy: 'u-1',
    raisedByRole: 'CUSTOMER',
    category: 'NO_SHOW',
    description: 'The provider did not arrive',
    evidence: [],
    status: 'AWAITING_RESPONSE',
    response: null,
    responseDueAt: new Date(Date.now() + 62 * HOUR).toISOString(),
    createdAt,
    updatedAt: createdAt,
    statusHistory: [{
      fromStatus: null, toStatus: 'AWAITING_RESPONSE', actorId: 'u-1', actorRole: 'CUSTOMER', reason: null,
      timestamp: createdAt,
    }],
    ...overrides,
  }
}

function signedIn(role, user) {
  return {
    user: { ...user, role },
    isLoading: false,
    isAuthenticated: true,
    mustChangePassword: false,
    hasRole: (roles) => (Array.isArray(roles) ? roles.includes(role) : roles === role),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function signedInCustomer() {
  return signedIn('CUSTOMER', { id: 'u-1', name: 'Nimal Perera', email: 'nimal@example.com' })
}

function signedInProvider() {
  return signedIn('PROVIDER', { id: 'p-1', name: 'Kamal Silva', email: 'kamal@example.com' })
}

/** The app's route table for these areas, plus a catch-all that shows where a link went. */
function renderApp(initialEntries) {
  const router = createMemoryRouter(
    [
      ...disputeRoutes,
      customerRoutes,
      { path: '/login', element: <p>Login page</p> },
      { path: '*', element: <p>Other page</p> },
    ],
    { initialEntries, initialIndex: initialEntries.length - 1 },
  )
  render(
    <ToastProvider>
      <RouterProvider router={router} />
    </ToastProvider>,
  )
  return router
}

const customerDisputePath = `/customer/disputes/${DISPUTE_ID}`

beforeEach(() => {
  auth.current = signedInCustomer()
  fetchDispute.mockReset()
  fetchCurrentUser.mockReset()
  fetchMyDisputes.mockReset()
  fetchMyDisputes.mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
})

/** The sidebar / nav link that is marked as the current page. */
function activeLink(container, name) {
  return within(container).getByRole('link', { name }).getAttribute('aria-current')
}

afterEach(() => {
  cleanup()
})

describe('customer dispute screen inside the Customer Common Layout', () => {
  it('renders the dispute page inside the layout with the account sidebar (no temporary frame)', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderApp([customerDisputePath])

    expect(await screen.findByRole('heading', { level: 1 })).toBeTruthy()
    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    expect(within(sidebar).getByText('Nimal Perera')).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Customer mobile navigation' })).toBeTruthy()
    expect(screen.getByRole('main').querySelector('h1')).toBeTruthy()
    expect(fetchDispute).toHaveBeenCalledWith(DISPUTE_ID, expect.anything())
  })

  it('is guarded like every customer page: a signed-out visitor goes to /login, nothing is fetched', () => {
    auth.current = { ...signedInCustomer(), user: null, isAuthenticated: false, hasRole: () => false }
    renderApp([customerDisputePath])

    expect(screen.getByText('Login page')).toBeTruthy()
    expect(fetchDispute).not.toHaveBeenCalled()
  })

  it.each([
    ['Profile', '/customer/account/profile'],
    ['Saved Addresses', '/customer/account/addresses'],
    ['Favorites', '/customer/account/favorites'],
    ['Payments', '/customer/account/payments'],
    ['My Bookings', '/customer/bookings'],
  ])('sidebar tab "%s" navigates from the dispute screen to %s', async (label, path) => {
    fetchDispute.mockResolvedValue(dispute())
    const router = renderApp([customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    fireEvent.click(within(sidebar).getByRole('link', { name: label }))

    expect(router.state.location.pathname).toBe(path)
  })

  it('mobile bottom navigation works from the dispute screen (Account tab)', async () => {
    fetchDispute.mockResolvedValue(dispute())
    const router = renderApp([customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    const mobileNav = screen.getByRole('navigation', { name: 'Customer mobile navigation' })
    fireEvent.click(within(mobileNav).getByRole('link', { name: /account/i }))

    expect(router.state.location.pathname).toBe('/customer/account/profile')
  })

  it('Back returns to the page the customer came from', async () => {
    fetchDispute.mockResolvedValue(dispute())
    const router = renderApp(['/customer/account/favorites', customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    await router.navigate(-1)

    expect(router.state.location.pathname).toBe('/customer/account/favorites')
  })

  it('the breadcrumb links back to My Disputes', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderApp([customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    const breadcrumb = screen.getAllByRole('navigation', { name: 'Breadcrumb' })
      .find((nav) => within(nav).queryByRole('link', { name: 'My Disputes' }))
    expect(within(breadcrumb).getByRole('link', { name: 'My Disputes' }).getAttribute('href')).toBe('/customer/disputes')
  })

  it('"My Disputes" is in the customer sidebar and is the active item on the dispute detail', async () => {
    const labels = CUSTOMER_NAV_SECTIONS.flatMap((section) => section.items.map((item) => item.label))
    expect(labels).toContain('My Disputes')
    fetchDispute.mockResolvedValue(dispute())
    renderApp([customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    expect(activeLink(sidebar, 'My Disputes')).toBe('page')
    expect(activeLink(sidebar, 'Profile')).toBeNull()
  })

  it('/customer/disputes (My Disputes list) renders in the layout with "My Disputes" active; View opens the detail',
    async () => {
      fetchMyDisputes.mockResolvedValue({ content: [{ id: DISPUTE_ID, bookingId: 'b-1', raisedByRole: 'CUSTOMER',
        category: 'NO_SHOW', status: 'UNDER_REVIEW', createdAt: '2026-11-18T10:00:00Z' }], page: 0, size: 10,
      totalElements: 1, totalPages: 1 })
      fetchDispute.mockResolvedValue(dispute())
      const router = renderApp(['/customer/disputes'])

      expect(await screen.findByRole('heading', { level: 1, name: 'My Disputes' })).toBeTruthy()
      const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
      expect(activeLink(sidebar, 'My Disputes')).toBe('page')

      fireEvent.click(await screen.findByRole('link', { name: /view dispute/i }))
      expect(router.state.location.pathname).toBe(customerDisputePath)
    })
})

describe('provider dispute screen inside the shared ProviderLayout', () => {
  const providerDisputePath = `/provider/disputes/${DISPUTE_ID}`

  beforeEach(() => {
    auth.current = signedInProvider()
    fetchDispute.mockResolvedValue(dispute({ raisedBy: 'u-1', raisedByRole: 'CUSTOMER' }))
  })

  it('after a page reload it waits for the session instead of sending the provider to login', async () => {
    auth.current = { ...signedInProvider(), isLoading: true, isAuthenticated: false }
    const router = renderApp(['/provider/disputes'])

    expect(router.state.location.pathname).toBe('/provider/disputes')
    expect(fetchMyDisputes).not.toHaveBeenCalled()
    cleanup()

    auth.current = signedInProvider()
    const restored = renderApp(['/provider/disputes'])
    expect(await screen.findByRole('heading', { level: 1, name: 'My Disputes' })).toBeTruthy()
    expect(restored.state.location.pathname).toBe('/provider/disputes')
    expect(fetchCurrentUser).not.toHaveBeenCalled()
  })

  it('a signed-out visitor goes to /login (and comes back after signing in)', () => {
    auth.current = { ...signedInProvider(), user: null, isAuthenticated: false, hasRole: () => false }
    const router = renderApp(['/provider/disputes'])

    expect(screen.getByText('Login page')).toBeTruthy()
    expect(router.state.location.state).toEqual({ from: '/provider/disputes' })
  })

  it('a signed-in customer on a provider dispute page sees "Access denied", not a missing page', async () => {
    auth.current = signedInCustomer()
    renderApp([providerDisputePath])

    expect(await screen.findByText(/do not have access/i)).toBeTruthy()
    expect(screen.queryByText('Other page')).toBeNull()
    expect(fetchDispute).not.toHaveBeenCalled()
  })

  it('renders inside the provider layout with "My Disputes" active (the WP08 "View dispute" link lands here)', async () => {
    renderApp([providerDisputePath])

    expect(await screen.findByRole('heading', { level: 1 })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Provider mobile navigation' })).toBeTruthy()
    expect(screen.queryByRole('complementary', { name: 'Account sidebar' })).toBeNull()
    const disputesLinks = screen.getAllByRole('link', { name: 'My Disputes' })
    expect(disputesLinks.some((link) => link.getAttribute('aria-current') === 'page')).toBe(true)
  })

  it('/provider/disputes (My Disputes) and /provider/disputes/new render inside the provider layout', async () => {
    const router = renderApp(['/provider/disputes'])
    expect(await screen.findByRole('heading', { level: 1, name: 'My Disputes' })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Provider mobile navigation' })).toBeTruthy()

    fireEvent.click(screen.getByRole('link', { name: 'Raise a dispute' }))
    expect(router.state.location.pathname).toBe('/provider/disputes/new')
    expect(await screen.findByRole('heading', { level: 1, name: 'Raise a dispute' })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Provider mobile navigation' })).toBeTruthy()
  })

  it('Back from the provider dispute returns to the provider page the user came from', async () => {
    const router = renderApp(['/provider/disputes', providerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    await router.navigate(-1)

    expect(router.state.location.pathname).toBe('/provider/disputes')
  })

  it.each([
    ['My Listings', '/provider/listings'],
    ['Bookings', '/provider/bookings'],
    ['Earnings & Payouts', '/provider/earnings'],
  ])('provider tab "%s" navigates to %s', async (label, path) => {
    const router = renderApp([providerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    fireEvent.click(screen.getAllByRole('link', { name: label })[0])

    expect(router.state.location.pathname).toBe(path)
  })
})
