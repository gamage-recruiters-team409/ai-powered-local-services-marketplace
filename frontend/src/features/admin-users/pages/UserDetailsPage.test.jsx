// @vitest-environment jsdom

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getUser,
  getUserListing,
  reactivateUser,
  reviewUserListing,
  suspendUser,
} from '../services/adminUsersService'
import {
  apiError,
  BASE,
  CUSTOMER_ID,
  customerDetail,
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
  reviewUserListing: vi.fn(),
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
  hiddenSince: '2026-10-24T00:00:00Z',
  lastUpdatedAt: '2026-10-24T00:00:00Z',
  hiddenReason: 'Flagged for review: the description contains contact details.',
  moderationFlags: ['CONTACT_DETAILS'],
  description: 'Deep cleaning for offices. <img src=x onerror="alert(1)"> Contact +94 77 123 4567 or book@clean.lk today.',
  imageUrl: null,
}

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
  reviewUserListing.mockReset()
})

describe('Account details', () => {
  it('renders the profile and every section from real data', async () => {
    await openPage()

    expect(screen.getByText('Detailed account overview, metrics, listings, and dispute history for Sarah Smith')).toBeTruthy()
    expect(screen.getByText('Verified')).toBeTruthy()
    expect(screen.getByText('sarah.smith@example.test')).toBeTruthy()
    expect(screen.getByText('#PRO-0002')).toBeTruthy()
    expect(screen.getByText('Dehiwala, Colombo')).toBeTruthy()
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
    expect(screen.getByText('Commercial Deep Cleaning')).toBeTruthy()
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
    expect(screen.getByText('Hidden listings will appear when the listing service is connected.')).toBeTruthy()
    expect(screen.getByText('Recent disputes will appear when the dispute service is connected.')).toBeTruthy()
    expect(screen.getByText('Admin actions will appear when the audit log is connected.')).toBeTruthy()
    expect(screen.getAllByText('Not available yet').length).toBe(5)
    expect(screen.queryByText('%')).toBeNull()
    expect(screen.queryByText(/completed$/)).toBeNull()
    expect(screen.queryByText('Verified')).toBeNull()
    expect(screen.queryByText('Service area')).toBeNull()
    expect(screen.queryByText('Last active')).toBeNull()
  })

  it('a section whose source failed says it could not be loaded', async () => {
    await openPage(providerDetail({ disputes: { available: false, reason: 'SOURCE_ERROR' } }))

    expect(screen.getByText('Recent disputes could not be loaded right now. Try again later.')).toBeTruthy()
  })

  it('customer: no rating card, no hidden listings and no provider-only profile facts', async () => {
    await openPage(customerDetail())

    expect(screen.queryByText('Avg. rating')).toBeNull()
    expect(screen.queryByText('Hidden listings')).toBeNull()
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
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canReviewListings: true } }))
    expect(screen.getByRole('button', { name: 'Reactivate account' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull()
  })

  it('SUPER_ADMIN, an admin target or your own account (actions all false): no account buttons', async () => {
    await openPage(providerDetail({ actions: { canSuspend: false, canReactivate: false, canReviewListings: false } }), { canEdit: false })
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
    expect(screen.queryByRole('button', { name: /Review/ })).toBeNull()
    expect(screen.getByLabelText('Loading account')).toBeTruthy()
    expect(getUser).toHaveBeenLastCalledWith(CUSTOMER_ID, expect.anything())

    await resolve(customerDetail({ actions: { canSuspend: false, canReactivate: true, canReviewListings: false } }))
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

  it('an open review modal for the previous account closes on navigation', async () => {
    const router = await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Review Commercial Deep Cleaning' }))
    expect(screen.getByRole('dialog', { name: 'Review listing' })).toBeTruthy()
    pendingNextAccount()

    await act(async () => router.navigate(`${BASE}/${CUSTOMER_ID}`))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(reviewUserListing).not.toHaveBeenCalled()
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
    await openPage(providerDetail({ profile: { ...providerDetail().profile, status: 'SUSPENDED' }, actions: { canSuspend: false, canReactivate: true, canReviewListings: true } }))
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

describe('Review listing modal', () => {
  const open = async (detail, options) => {
    await openPage(detail, options)
    fireEvent.click(screen.getByRole('button', { name: 'Review Commercial Deep Cleaning' }))
    const dialog = screen.getByRole('dialog', { name: 'Review listing' })
    await within(dialog).findByText(/Deep cleaning for offices/)
    return dialog
  }

  it('shows the listing facts, the reason and the description with contact details highlighted as text only', async () => {
    const dialog = await open()

    expect(getUserListing).toHaveBeenCalledWith(PROVIDER_ID, LISTING.id, expect.anything())
    expect(within(dialog).getByText('Cleaning')).toBeTruthy()
    expect(within(dialog).getByText(/12,000/)).toBeTruthy()
    expect(within(dialog).getByText('Reason hidden')).toBeTruthy()
    const marks = dialog.querySelectorAll('mark')
    expect([...marks].map((m) => m.textContent)).toEqual(['+94 77 123 4567', 'book@clean.lk'])
    expect(dialog.querySelector('img')).toBeNull()
    expect(within(dialog).getByText(/<img src=x onerror="alert\(1\)">/)).toBeTruthy()
  })

  it('note is required for a decision', async () => {
    const dialog = await open()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make visible' }))
    expect(within(dialog).getByText('Enter a note.')).toBeTruthy()
    expect(reviewUserListing).not.toHaveBeenCalled()
  })

  it('Keep hidden and Make visible send their decision with the note', async () => {
    reviewUserListing.mockResolvedValue()
    let dialog = await open()
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Admin note/ }), { target: { value: 'Contact details still present' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep hidden' }))
    await waitFor(() => expect(reviewUserListing).toHaveBeenCalledWith(PROVIDER_ID, LISTING.id, {
      decision: 'KEEP_HIDDEN', note: 'Contact details still present',
    }))
    expect(await screen.findByText('"Commercial Deep Cleaning" stays hidden.')).toBeTruthy()

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await screen.findByRole('button', { name: 'Review Commercial Deep Cleaning' })
    fireEvent.click(screen.getByRole('button', { name: 'Review Commercial Deep Cleaning' }))
    dialog = screen.getByRole('dialog', { name: 'Review listing' })
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Admin note/ }), { target: { value: 'Contact details were removed' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make visible' }))
    await waitFor(() => expect(reviewUserListing).toHaveBeenLastCalledWith(PROVIDER_ID, LISTING.id, {
      decision: 'MAKE_VISIBLE', note: 'Contact details were removed',
    }))
  })

  it('503: "Listing decisions are not available yet"', async () => {
    reviewUserListing.mockRejectedValue(apiError(503, 'LISTING_ACTION_UNAVAILABLE'))
    const dialog = await open()
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Admin note/ }), { target: { value: 'Contact details were removed' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make visible' }))

    expect(await within(dialog).findByText('Listing decisions are not available yet — the listing service is not connected.')).toBeTruthy()
  })

  it('read-only for SUPER_ADMIN (canReviewListings false): no note and no decision buttons', async () => {
    const dialog = await open(
      providerDetail({ actions: { canSuspend: false, canReactivate: false, canReviewListings: false } }),
      { canEdit: false },
    )

    expect(within(dialog).queryByRole('button', { name: 'Keep hidden' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Make visible' })).toBeNull()
    expect(within(dialog).queryByRole('textbox')).toBeNull()
    expect(within(dialog).getByText(/View only/)).toBeTruthy()
  })

  it('ADMIN while listing decisions are not supported (canReviewListings false): listing shown read-only with a '
    + '"not available yet" note, no actionable buttons', async () => {
    const dialog = await open(providerDetail({ actions: { canSuspend: true, canReactivate: false, canReviewListings: false } }))

    expect(within(dialog).getByText('Commercial Deep Cleaning')).toBeTruthy()
    expect(dialog.querySelectorAll('mark').length).toBe(2)
    expect(within(dialog).queryByRole('button', { name: 'Keep hidden' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Make visible' })).toBeNull()
    expect(within(dialog).queryByRole('textbox')).toBeNull()
    expect(within(dialog).getByText(/Listing decisions are not available yet — the listing service is not connected\./)).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeTruthy()
    expect(reviewUserListing).not.toHaveBeenCalled()
  })

  it('listing details not connected: placeholder instead of the description', async () => {
    getUserListing.mockResolvedValue(notConnected())
    await openPage()
    fireEvent.click(screen.getByRole('button', { name: 'Review Commercial Deep Cleaning' }))
    const dialog = screen.getByRole('dialog', { name: 'Review listing' })

    expect(await within(dialog).findByText('Listing details will appear when the listing service is connected.')).toBeTruthy()
  })
})
