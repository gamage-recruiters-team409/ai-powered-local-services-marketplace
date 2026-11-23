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
import { fetchCurrentUser, fetchDispute } from '../services/disputesApi'
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

function signedInCustomer() {
  return {
    user: { id: 'u-1', name: 'Nimal Perera', email: 'nimal@example.com', role: 'CUSTOMER' },
    isLoading: false,
    isAuthenticated: true,
    mustChangePassword: false,
    hasRole: (roles) => (Array.isArray(roles) ? roles.includes('CUSTOMER') : roles === 'CUSTOMER'),
    logout: vi.fn().mockResolvedValue(undefined),
  }
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
})

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

  it('the breadcrumb links back to My Bookings', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderApp([customerDisputePath])
    await screen.findByRole('heading', { level: 1 })

    const breadcrumb = screen.getAllByRole('navigation', { name: 'Breadcrumb' })
      .find((nav) => within(nav).queryByRole('link', { name: 'My Bookings' }))
    expect(within(breadcrumb).getByRole('link', { name: 'My Bookings' }).getAttribute('href')).toBe('/customer/bookings')
  })

  it('"My Disputes" is NOT in the customer sidebar yet (the list page comes in a later PR)', () => {
    const labels = CUSTOMER_NAV_SECTIONS.flatMap((section) => section.items.map((item) => item.label))
    expect(labels).not.toContain('My Disputes')
    expect(labels.some((label) => /dispute/i.test(label))).toBe(false)
  })
})

describe('provider dispute screen inside the shared ProviderLayout', () => {
  const providerDisputePath = `/provider/disputes/${DISPUTE_ID}`

  beforeEach(() => {
    fetchCurrentUser.mockResolvedValue({ id: 'p-1', role: 'PROVIDER' })
    fetchDispute.mockResolvedValue(dispute({ raisedBy: 'u-1', raisedByRole: 'CUSTOMER' }))
  })

  it('renders inside the provider layout', async () => {
    renderApp([providerDisputePath])

    expect(await screen.findByRole('heading', { level: 1 })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Provider mobile navigation' })).toBeTruthy()
    expect(screen.queryByRole('complementary', { name: 'Account sidebar' })).toBeNull()
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
