// @vitest-environment jsdom

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getUser,
  getUserListing,
  getUserListings,
  reactivateUser,
  suspendUser,
  syncListingVisibility,
} from '../services/adminUsersService'
import {
  apiError,
  BASE,
  CUSTOMER_ID,
  customerDetail,
  listingsPage,
  notConnected,
  PROVIDER_ID,
  providerDetail,
  renderUsers,
  stubbedDetail,
} from '../testing/renderUsers'

vi.mock('../services/adminUsersService', () => ({
  getUser: vi.fn(),
  suspendUser: vi.fn(),
  reactivateUser: vi.fn(),
  getUserListing: vi.fn(),
  getUserListings: vi.fn(),
  syncListingVisibility: vi.fn(),
  listUsers: vi.fn(),
  getUsersSummary: vi.fn(),
  getRegistrations: vi.fn(),
  exportUsersCsv: vi.fn(),
  isRequestCancelled: () => false,
}))

const LISTING = {
  id: '65f1c0aa11bb22cc33a1aaaa',
  providerId: PROVIDER_ID,
  title: 'Commercial Deep Cleaning',
  categoryName: 'Cleaning',
  basePrice: 12000,
  pricingModel: 'FIXED',
  status: 'FLAGGED',
  moderatedAt: '2026-10-24T00:00:00Z',
  lastUpdatedAt: '2026-10-24T00:00:00Z',
  moderationReason: 'Flagged for review: the description contains contact details.',
  moderationFlags: ['CONTACT_DETAILS'],
  description: 'Deep cleaning for offices. <img src=x onerror="alert(1)"> Contact +94 77 123 4567 or book@clean.lk today.',
  imageUrl: null,
}

const ROWS = [
  { id: LISTING.id, title: 'Commercial Deep Cleaning', categoryName: 'Cleaning', basePrice: 12000, pricingModel: 'FIXED', status: 'FLAGGED', moderationFlags: ['CONTACT_DETAILS'], updatedAt: '2026-10-24T00:00:00Z' },
  { id: '65f1c0aa11bb22cc33a1bbbb', title: 'Window Washing', categoryName: 'Cleaning', basePrice: 4000, pricingModel: 'FIXED', status: 'PAUSED', moderationFlags: [], updatedAt: '2026-10-20T00:00:00Z' },
]
const STALE = 'The account status was changed by someone else. Reload the account and try again'

const openPage = async (detail = providerDetail(), options) => {
  getUser.mockResolvedValue(detail)
  const router = renderUsers(`${BASE}/${detail.profile.id}`, options)
  await screen.findByRole('heading', { name: detail.profile.displayName })
  return router
}

/** getUser for the next account stays pending until resolve() is called. */
function pendingNextAccount() {
  let resolve
  getUser.mockImplementationOnce(() => new Promise((r) => {
    resolve = r
  }))
  return (data) => act(async () => resolve(data))
}

beforeEach(() => {
  getUser.mockReset()
  suspendUser.mockReset()
  reactivateUser.mockReset()
  getUserListing.mockReset().mockResolvedValue({ available: true, data: LISTING })
  getUserListings.mockReset().mockResolvedValue(listingsPage(ROWS))
  syncListingVisibility.mockReset()
})

describe('Account details', () => {
  it('renders the profile and every section from real data', async () => {
    await openPage()

    expect(screen.getByText('Detailed account overview, metrics, listings, and dispute history for Sarah Smith')).toBeTruthy()
    expect(screen.getByText('Verified')).toBeTruthy()
    expect(screen.getByText('sarah.smith@example.test')).toBeTruthy()
    expect(screen.getByText('#PRO-0002')).toBeTruthy()
    expect(screen.getByText('Colombo, Gampaha')).toBeTruthy()
    expect(screen.getByText('Last signed in')).toBeTruthy()
    // metrics, computed only from the returned numbers
    expect(screen.getByText('128')).toBeTruthy()
    // 123 / 128: completion rate card, the donut's success centre and the "Completed" outcome row
    expect(screen.getAllByText('96%').length).toBe(3)
    expect(screen.getByText('123 completed')).toBeTruthy()
    expect(screen.getByText('4.7')).toBeTruthy()
    expect(screen.getByText('112 reviews')).toBeTruthy()
    expect(screen.getByText('1 in the last 30 days')).toBeTruthy()
    expect(screen.getByText('1 open case')).toBeTruthy()
    // charts
    expect(screen.getByText('21.3 bookings')).toBeTruthy()
    expect(screen.getByRole('img', { name: /Completed 123, Cancelled 3, Disputed 2/ })).toBeTruthy()
    expect(screen.getByText('128 orders total')).toBeTruthy()
    // lists and tables
    expect(await screen.findByText('Commercial Deep Cleaning')).toBeTruthy()
    expect(screen.getByText(/Deep Cleaning \(4 hrs\)/)).toBeTruthy()
    expect(screen.getByText('Awaiting provider')).toBeTruthy()
    expect(screen.getByText(/8,500/)).toBeTruthy()
    expect(screen.getByText('Poor quality')).toBeTruthy()
    expect(screen.getByText('Under review')).toBeTruthy()
    expect(screen.getByText('Alex Dawson')).toBeTruthy()
    expect(screen.getByText('Off-platform contact')).toBeTruthy()
    expect(screen.getByText(/Shared a phone number in a listing/)).toBeTruthy()
    expect(screen.getByText('Immutable administrative audit log')).toBeTruthy()
    expect(screen.queryByText(/\+14%|0\.78% rate|Tier-1 SLA|Platform benchmark|upward trend/)).toBeNull()
  })

  it('each unavailable section shows its placeholder and no numbers', async () => {
    await openPage(stubbedDetail())

    expect(screen.getByText('Bookings per month will appear when the booking service is connected.')).toBeTruthy()
    expect(screen.getByText('Booking outcomes will appear when the booking service is connected.')).toBeTruthy()
    expect(screen.getByText('Queued bookings will appear when the booking service is connected.')).toBeTruthy()
    expect(screen.getByText('Listings will appear when the listing service is connected.')).toBeTruthy()
    expect(screen.getByText('Recent disputes will appear when the dispute service is connected.')).toBeTruthy()
    expect(screen.getByText('Admin actions will appear when the audit log is connected.')).toBeTruthy()
    expect(screen.getAllByText('Not available yet').length).toBe(5)
    expect(screen.queryByText('%')).toBeNull()
    expect(screen.queryByText(/completed$/)).toBeNull()
    expect(screen.queryByText('Verified')).toBeNull()
    expect(screen.queryByText('Service area')).toBeNull()
    expect(screen.queryByText('Last signed in')).toBeNull()
  })

  it('a section whose source failed says it could not be loaded', async () => {
    await openPage(providerDetail({ disputes: { available: false, reason: 'SOURCE_ERROR' } }))

    expect(screen.getByText('Recent disputes could not be loaded right now. Try again later.')).toBeTruthy()
  })

  it('customer: no rating card, no listings and no provider-only profile facts', async () => {
    await openPage(customerDetail())

    expect(screen.queryByText('Avg. rating')).toBeNull()
    expect(screen.queryByRole('heading', { name: /^Listings/ })).toBeNull()
    expect(getUserListings).not.toHaveBeenCalled()
    expect(screen.queryByText('Verified')).toBeNull()
    expect(screen.queryByText('Service area')).toBeNull()
    expect(screen.getByText('Total bookings')).toBeTruthy()
  })

  it('Suspend for an active account, Reactivate for a suspended one; none when the backend allows neither', async () => {
    await openPage()
    expect(screen.getByRole('button', { name: 'Suspend account' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Reactivate account' })).toBeNull()
  })

  it('Reactivate is shown when allowed', async () => {
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canSyncListingVisibility: true } }))
    expect(screen.getByRole('button', { name: 'Reactivate account' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
  })

  it('SUPER_ADMIN, an admin target or your own account (actions all false): no account buttons', async () => {
    await openPage(providerDetail({ actions: { canSuspend: false, canReactivate: false, canSyncListingVisibility: false } }), { canEdit: false })
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reactivate account' })).toBeNull()
  })

  it('dispute "View" is disabled until the admin dispute case page exists', async () => {
    await openPage()
    const button = screen.getByRole('button', { name: 'View' })
    expect(button.disabled).toBe(true)
    expect(button.parentElement.getAttribute('title')).toBe('Admin dispute case page coming soon')
  })

  it('404: "User not found" with Back to users', async () => {
    getUser.mockRejectedValue(apiError(404, 'USER_NOT_FOUND'))
    renderUsers(`${BASE}/missing`)

    expect(await screen.findByText('User not found')).toBeTruthy()
    expect(screen.getByRole('link', { name: /Back to users/ }).getAttribute('href')).toBe(BASE)
  })
})

describe('Navigating between accounts', () => {
  it('while the next account loads, the previous account and its actions are gone; then the new one shows', async () => {
    const router = await openPage()
    expect(screen.getByRole('button', { name: 'Suspend account' })).toBeTruthy()
    const resolve = pendingNextAccount()

    await act(async () => router.navigate(`${BASE}/${CUSTOMER_ID}`))

    expect(screen.queryByRole('heading', { name: 'Sarah Smith' })).toBeNull()
    expect(screen.queryByText('sarah.smith@example.test')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reactivate account' })).toBeNull()
    expect(screen.queryByRole('button', { name: /View Commercial/ })).toBeNull()
    expect(screen.getByLabelText('Loading account')).toBeTruthy()
    expect(getUser).toHaveBeenLastCalledWith(CUSTOMER_ID, expect.anything())

    await resolve(customerDetail({ actions: { canSuspend: false, canReactivate: true, canSyncListingVisibility: false } }))
    expect(await screen.findByRole('heading', { name: 'Nimal Perera' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reactivate account' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
  })

  it('an open Suspend dialog for the previous account closes on navigation and nothing can be submitted', async () => {
    const router = await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    expect(screen.getByRole('dialog', { name: 'Suspend account' })).toBeTruthy()
    pendingNextAccount()

    await act(async () => router.navigate(`${BASE}/${CUSTOMER_ID}`))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
    expect(suspendUser).not.toHaveBeenCalled()
  })

  it('an open listing modal for the previous account closes on navigation', async () => {
    const router = await openPage()
    fireEvent.click(await screen.findByRole('button', { name: 'View Commercial Deep Cleaning' }))
    expect(screen.getByRole('dialog', { name: 'View listing details' })).toBeTruthy()
    pendingNextAccount()

    await act(async () => router.navigate(`${BASE}/${CUSTOMER_ID}`))

    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('Suspend account modal', () => {
  const open = async () => {
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    return screen.getByRole('dialog', { name: 'Suspend account' })
  }

  it('reason is required', async () => {
    const dialog = await open()
    expect(dialog.textContent).toContain("Suspend Sarah Smith? They won't be able to book or accept bookings.")

    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))
    expect(within(dialog).getByText('Choose a reason.')).toBeTruthy()
    expect(suspendUser).not.toHaveBeenCalled()
  })

  it('note is required (10+ characters) when the reason is Other', async () => {
    const dialog = await open()
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'OTHER' } })
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Administrator note/ }), { target: { value: 'short' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))

    expect(within(dialog).getByText('The note must be at least 10 characters.')).toBeTruthy()
    expect(within(dialog).getByText('5/500')).toBeTruthy()
    expect(suspendUser).not.toHaveBeenCalled()
  })

  it('success: sends reasonCode and note once, toasts and reloads the account', async () => {
    suspendUser.mockResolvedValue({ userId: PROVIDER_ID, status: 'SUSPENDED', changed: true, auditLogId: 'a9' })
    const dialog = await open()
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'REPEATED_CANCELLATIONS' } })
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Administrator note/ }), { target: { value: '  Cancelled five bookings  ' } })
    const submit = within(dialog).getByRole('button', { name: 'Suspend account' })
    fireEvent.click(submit)
    fireEvent.click(submit)

    await waitFor(() => expect(suspendUser).toHaveBeenCalledTimes(1))
    expect(suspendUser).toHaveBeenCalledWith(PROVIDER_ID, { reasonCode: 'REPEATED_CANCELLATIONS', note: 'Cancelled five bookings' })
    expect(await screen.findByText('Sarah Smith was suspended.')).toBeTruthy()
    await waitFor(() => expect(getUser).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('dialog', { name: 'Suspend account' })).toBeNull()
  })

  it('503: "Account actions are not available yet" and the modal stays open', async () => {
    suspendUser.mockRejectedValue(apiError(503, 'USER_ACCOUNT_ACTION_UNAVAILABLE'))
    const dialog = await open()
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'POLICY_VIOLATION' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))

    expect(await within(dialog).findByRole('alert')).toBeTruthy()
    expect(within(dialog).getByRole('alert').textContent).toContain(
      'Account actions are not available yet — the accounts service is not connected.',
    )
  })

  it('Esc closes the modal', async () => {
    const dialog = await open()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

describe('Reactivate account modal', () => {
  const open = async () => {
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canSyncListingVisibility: true } }))
    fireEvent.click(screen.getByRole('button', { name: 'Reactivate account' }))
    return screen.getByRole('dialog', { name: 'Reactivate account' })
  }

  it('note is required', async () => {
    const dialog = await open()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reactivate' }))
    expect(within(dialog).getByText('Enter a note.')).toBeTruthy()
    expect(reactivateUser).not.toHaveBeenCalled()
  })

  it('success: sends the trimmed note and reloads', async () => {
    reactivateUser.mockResolvedValue({ userId: PROVIDER_ID, status: 'ACTIVE', changed: true })
    const dialog = await open()
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Administrative note/ }), { target: { value: 'Appeal accepted after review' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reactivate' }))

    await waitFor(() => expect(reactivateUser).toHaveBeenCalledWith(PROVIDER_ID, { note: 'Appeal accepted after review' }))
    expect(await screen.findByText('Sarah Smith was reactivated.')).toBeTruthy()
    await waitFor(() => expect(getUser).toHaveBeenCalledTimes(2))
  })
})

describe('409 CONCURRENT_MODIFICATION', () => {
  it('suspend: shows the message with Reload; Reload closes the modal and loads the current account', async () => {
    suspendUser.mockRejectedValue(apiError(409, 'CONCURRENT_MODIFICATION', STALE))
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    const dialog = screen.getByRole('dialog', { name: 'Suspend account' })
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'POLICY_VIOLATION' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('The account status was changed by someone else. Reload the account and try again.')
    fireEvent.click(within(alert).getByRole('button', { name: 'Reload' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(getUser).toHaveBeenCalledTimes(2))
  })

  it('reactivate: same message and Reload action', async () => {
    reactivateUser.mockRejectedValue(apiError(409, 'CONCURRENT_MODIFICATION', STALE))
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canSyncListingVisibility: true } }))
    fireEvent.click(screen.getByRole('button', { name: 'Reactivate account' }))
    const dialog = screen.getByRole('dialog', { name: 'Reactivate account' })
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Administrative note/ }), { target: { value: 'Appeal accepted after review' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reactivate' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('Reload the account and try again')
    expect(within(alert).getByRole('button', { name: 'Reload' })).toBeTruthy()
  })

  it('other errors have no Reload action', async () => {
    suspendUser.mockRejectedValue(apiError(403, 'CANNOT_MODIFY_SELF', 'Self status modification is not allowed'))
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    const dialog = screen.getByRole('dialog', { name: 'Suspend account' })
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'POLICY_VIOLATION' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))

    const alert = await within(dialog).findByRole('alert')
    expect(within(alert).queryByRole('button', { name: 'Reload' })).toBeNull()
  })
})

describe('Listing visibility after suspend / reactivate', () => {
  const WARNING = 'Account updated, but listing visibility could not be updated.'

  it('listingVisibilitySynced=false: warning with Retry; Retry re-applies it, toasts and reloads', async () => {
    suspendUser.mockResolvedValue({ userId: PROVIDER_ID, status: 'SUSPENDED', changed: true, auditLogId: 'a9', listingVisibilitySynced: false })
    syncListingVisibility.mockResolvedValue({ userId: PROVIDER_ID, status: 'SUSPENDED', listingVisibilitySynced: true })
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    const dialog = screen.getByRole('dialog', { name: 'Suspend account' })
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'POLICY_VIOLATION' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))

    expect(await screen.findByText(WARNING)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(syncListingVisibility).toHaveBeenCalledWith(PROVIDER_ID))
    expect(await screen.findByText('Listing visibility was updated.')).toBeTruthy()
    await waitFor(() => expect(screen.queryByText(WARNING)).toBeNull())
    await waitFor(() => expect(getUser).toHaveBeenCalledTimes(3))
  })

  it('Retry still failing: the warning stays with the reason', async () => {
    syncListingVisibility.mockRejectedValue(apiError(503, 'LISTING_VISIBILITY_SYNC_FAILED'))
    const failed = providerDetail()
    failed.adminActions = { available: true, data: [{ ...failed.adminActions.data[0], listingVisibilitySync: 'FAILED' }] }
    await openPage(failed)

    expect(screen.getByText(WARNING)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText(/Listing visibility could not be updated\. Try again later\./)).toBeTruthy()
  })

  it('the latest change still marked FAILED in the audit log shows the warning on load; view-only admins get no Retry', async () => {
    const failed = providerDetail({ actions: { canSuspend: false, canReactivate: false, canSyncListingVisibility: false } })
    failed.adminActions = { available: true, data: [{ ...failed.adminActions.data[0], listingVisibilitySync: 'FAILED' }] }
    await openPage(failed, { canEdit: false })

    expect(screen.getByText(WARNING)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull()
  })

  it('synced (or a customer): no warning', async () => {
    suspendUser.mockResolvedValue({ userId: PROVIDER_ID, status: 'SUSPENDED', changed: true, listingVisibilitySynced: true })
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Suspend account' }))
    const dialog = screen.getByRole('dialog', { name: 'Suspend account' })
    fireEvent.change(within(dialog).getByRole('combobox', { name: /Reason/ }), { target: { value: 'POLICY_VIOLATION' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Suspend account' }))
    await waitFor(() => expect(getUser).toHaveBeenCalledTimes(2))
    await screen.findByRole('heading', { name: 'Sarah Smith' })

    expect(screen.queryByText(WARNING)).toBeNull()
  })
})

describe('Listings section', () => {
  it('count chips from the listing summary, rows with status chips (Paused for paused listings) and View', async () => {
    await openPage()

    const chips = screen.getByRole('list', { name: 'Listings by status' })
    expect(within(chips).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Active 2', 'Paused 2', 'Draft 1', 'Flagged 1', 'Archived 1',
    ])
    expect(await screen.findByText('Window Washing')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'View Window Washing' })).toBeTruthy()
    expect(getUserListings).toHaveBeenCalledWith(PROVIDER_ID, { page: 0, size: 5 }, expect.anything())
    expect(screen.queryByText('Listings are paused while this account is suspended.')).toBeNull()
  })

  it('suspended provider: one note above the list', async () => {
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canSyncListingVisibility: true } }))

    expect(screen.getByText('Listings are paused while this account is suspended.')).toBeTruthy()
    expect(screen.getAllByText('Listings are paused while this account is suspended.').length).toBe(1)
  })

  it('paginated: the next page is requested from the listing endpoint', async () => {
    getUserListings.mockResolvedValue(listingsPage(ROWS, 12))
    await openPage()
    await screen.findByText('Window Washing')

    fireEvent.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => expect(getUserListings).toHaveBeenLastCalledWith(PROVIDER_ID, { page: 1, size: 5 }, expect.anything()))
  })

  it('no listings: an empty line instead of rows', async () => {
    getUserListings.mockResolvedValue(listingsPage([]))
    await openPage()
    expect(await screen.findByText('No listings yet.')).toBeTruthy()
  })
})

describe('View listing details modal', () => {
  const open = async (detail, options) => {
    await openPage(detail, options)
    fireEvent.click(await screen.findByRole('button', { name: 'View Commercial Deep Cleaning' }))
    const dialog = screen.getByRole('dialog', { name: 'View listing details' })
    await within(dialog).findByText(/Deep cleaning for offices/)
    return dialog
  }

  it('shows the facts, the flag reason and the description with contact details highlighted as text only', async () => {
    const dialog = await open()

    expect(getUserListing).toHaveBeenCalledWith(PROVIDER_ID, LISTING.id, expect.anything())
    expect(within(dialog).getByText('Cleaning')).toBeTruthy()
    expect(within(dialog).getByText(/12,000/)).toBeTruthy()
    expect(within(dialog).getByText('Reason flagged')).toBeTruthy()
    expect(within(dialog).getByText(LISTING.moderationReason)).toBeTruthy()
    const marks = dialog.querySelectorAll('mark')
    expect([...marks].map((m) => m.textContent)).toEqual(['+94 77 123 4567', 'book@clean.lk'])
    expect(dialog.querySelector('img')).toBeNull()
    expect(within(dialog).getByText(/<img src=x onerror="alert\(1\)">/)).toBeTruthy()
  })

  it.each([
    ['ADMIN', true],
    ['SUPER_ADMIN', false],
  ])('read-only for %s: no note field and no decision buttons, only Close', async (_role, canEdit) => {
    const dialog = await open(providerDetail(), { canEdit })

    expect(within(dialog).queryByRole('textbox')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: /Keep hidden|Make visible|Approve|Reject/ })).toBeNull()
    expect(within(dialog).getByText(/View only\. Flagged listings are approved or rejected by moderators\./)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('a paused listing has no flag reason', async () => {
    getUserListing.mockResolvedValue({ available: true, data: { ...LISTING, status: 'PAUSED', moderationFlags: [], moderationReason: null } })
    const dialog = await open()

    expect(within(dialog).queryByText('Reason flagged')).toBeNull()
    expect(within(dialog).getByText('Paused')).toBeTruthy()
  })

  it('listing details not connected: placeholder instead of the description', async () => {
    getUserListing.mockResolvedValue(notConnected())
    await openPage()
    fireEvent.click(await screen.findByRole('button', { name: 'View Commercial Deep Cleaning' }))
    const dialog = screen.getByRole('dialog', { name: 'View listing details' })

    expect(await within(dialog).findByText('Listing details will appear when the listing service is connected.')).toBeTruthy()
  })
})

describe('Profile facts from WP01 / WP02 / WP08', () => {
  it('never signed in: "Last signed in" shows a dash; no service area districts: a dash', async () => {
    const d = providerDetail()
    await openPage(providerDetail({ profile: { ...d.profile, lastActive: { available: true }, serviceArea: { available: true, data: { districts: [] } } } }))

    const facts = screen.getByText('Last signed in').closest('dl')
    expect(within(facts).getAllByText('—').length).toBe(2)
  })

  it('rating with no reviews: dash and "No reviews yet"', async () => {
    await openPage(providerDetail({ rating: { available: true, data: { averageRating: 0, reviewCount: 0 } } }))
    expect(screen.getByText('No reviews yet')).toBeTruthy()
  })
})
