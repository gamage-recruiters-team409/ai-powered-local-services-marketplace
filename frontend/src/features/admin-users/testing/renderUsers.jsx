import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

import { AreaAccessContext } from '../../disputes-admin/access/accessContexts'
import UsersSection from '../pages/UsersSection'

export const BASE = '/admin/admin/users'

/** Renders the users section at `path` in a data router, with the ADMIN area's access (view-only for SUPER_ADMIN). */
export function renderUsers(path, { canEdit = true } = {}) {
  const access = { area: 'ADMIN', mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
  const router = createMemoryRouter(
    [
      {
        path: `${BASE}/*`,
        element: (
          <AreaAccessContext.Provider value={access}>
            <UsersSection />
          </AreaAccessContext.Provider>
        ),
      },
    ],
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return router
}

export const PROVIDER_ID = '65f1c0aa11bb22cc33a10002'
export const CUSTOMER_ID = '65f1c0aa11bb22cc33a10001'

export function row(overrides = {}) {
  return {
    id: PROVIDER_ID,
    displayName: 'Sarah Smith',
    email: 'sarah.smith@example.test',
    role: 'PROVIDER',
    joinedAt: '2026-01-14T00:00:00Z',
    status: 'ACTIVE',
    ...overrides,
  }
}

export function page(content, totalElements = content.length, number = 0, size = 8) {
  return { content, page: number, size, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)) }
}

const available = (data) => ({ available: true, data })
export const notConnected = () => ({ available: false, reason: 'NOT_CONNECTED' })
const notApplicable = () => ({ available: false, reason: 'NOT_APPLICABLE' })

export function summary({ weekly = false, available: on = true } = {}) {
  const count = (total) =>
    on ? available({ total, joinedLast7Days: weekly ? available(42) : notConnected() }) : notConnected()
  return { customers: count(1240), providers: count(310), suspended: count(9) }
}

/** A provider account with every section available. */
export function providerDetail(overrides = {}) {
  return {
    profile: {
      id: PROVIDER_ID,
      displayName: 'Sarah Smith',
      role: 'PROVIDER',
      status: 'ACTIVE',
      email: 'sarah.smith@example.test',
      phone: '+94 77 123 4567',
      memberSince: '2024-01-14T00:00:00Z',
      lastActive: available('2026-11-19T05:12:00Z'),
      emailVerified: true,
      providerVerified: available(true),
      serviceArea: available({ districts: ['Colombo', 'Gampaha'] }),
      pro: notConnected(),
    },
    bookingStats: available({ total: 128, completed: 123, cancelled: 3, disputed: 2 }),
    bookingsPerMonth: available([
      { month: '2026-06', start: '2026-06-01T00:00:00Z', bookings: 14 },
      { month: '2026-07', start: '2026-07-01T00:00:00Z', bookings: 19 },
      { month: '2026-08', start: '2026-08-01T00:00:00Z', bookings: 17 },
      { month: '2026-09', start: '2026-09-01T00:00:00Z', bookings: 22 },
      { month: '2026-10', start: '2026-10-01T00:00:00Z', bookings: 27 },
      { month: '2026-11', start: '2026-11-01T00:00:00Z', bookings: 29 },
    ]),
    bookingOutcomes: available({ completed: 123, cancelledByCustomer: 2, cancelledByProvider: 1, disputed: 2, otherClosed: 0 }),
    rating: available({ averageRating: 4.7, reviewCount: 112 }),
    cancellations: available({ total: 3, last30Days: 1, cancelledByThisUser: 1 }),
    disputes: available({
      lifetime: 2,
      open: 1,
      recent: [
        { id: 'dsp00000001042', bookingId: 'bk000000008902', status: 'UNDER_REVIEW', category: 'POOR_QUALITY', side: 'PROVIDER', createdAt: '2026-04-12T00:00:00Z' },
      ],
    }),
    listings: available({ total: 7, active: 2, paused: 2, draft: 1, flagged: 1, archived: 1, rejected: 0 }),
    queuedBookings: available([
      { bookingId: 'bk000000008902', serviceTitle: 'Deep Cleaning (4 hrs)', status: 'PENDING_PROVIDER', scheduledStart: '2026-11-20T04:30:00Z', counterpartyId: 'c1', counterpartyName: 'Nimal Perera', amount: 8500, currency: 'LKR' },
    ]),
    adminActions: available([
      { auditLogId: 'a1', actorId: 'admin-9', actorName: 'Alex Dawson', action: 'SUSPENDED', fromStatus: 'ACTIVE', toStatus: 'SUSPENDED', reasonCode: 'OFF_PLATFORM_CONTACT', note: 'Shared a phone number in a listing', outcome: 'SUCCESS', createdAt: '2026-04-14T00:00:00Z', listingVisibilitySync: null },
    ]),
    actions: { canSuspend: true, canReactivate: false, canSyncListingVisibility: true },
    ...overrides,
  }
}

/** Every section stubbed: nothing to show but placeholders. */
export function stubbedDetail(overrides = {}) {
  const d = providerDetail()
  return {
    ...d,
    profile: { ...d.profile, lastActive: notConnected(), serviceArea: notConnected(), providerVerified: notConnected() },
    bookingStats: notConnected(),
    bookingsPerMonth: notConnected(),
    bookingOutcomes: notConnected(),
    rating: notConnected(),
    cancellations: notConnected(),
    disputes: notConnected(),
    listings: notConnected(),
    queuedBookings: notConnected(),
    adminActions: notConnected(),
    ...overrides,
  }
}

export function customerDetail(overrides = {}) {
  const d = providerDetail()
  return {
    ...d,
    profile: { ...d.profile, id: CUSTOMER_ID, displayName: 'Nimal Perera', role: 'CUSTOMER', providerVerified: notApplicable(), serviceArea: notApplicable(), pro: notApplicable() },
    rating: notApplicable(),
    listings: notApplicable(),
    actions: { canSuspend: true, canReactivate: false, canSyncListingVisibility: false },
    ...overrides,
  }
}

/** One page of the provider's listings (GET /admin/users/{id}/listings), as a section. */
export function listingsPage(content, totalElements = content.length, number = 0, size = 5) {
  return available({ content, page: number, size, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)) })
}

export function apiError(status, error, message = error) {
  return Object.assign(new Error(error), { response: { status, data: { status, error, message } } })
}
