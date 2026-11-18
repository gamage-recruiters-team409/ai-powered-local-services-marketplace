// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import CustomerCompletionSection from '../components/CustomerCompletionSection'
import ServiceCompletionSection from '../components/ServiceCompletionSection'

// jsdom does not implement <dialog>.showModal()/close(); the shared Modal needs them.
beforeAll(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
  }
})

afterEach(() => cleanup())

const NOW = new Date('2026-11-11T05:00:00Z') // 10:30 in Colombo

const booking = (status, extra = {}) => ({
  id: 'b-1',
  ref: 'BK-2041',
  title: 'Home cleaning – 3 rooms',
  customerName: 'Nimal Perera',
  providerName: 'Kamal Services',
  slotStart: '2026-11-11T04:30:00Z', // 10:00 Colombo -> no-show allowed from 12:00
  slotEnd: '2026-11-11T06:30:00Z',
  status,
  ...extra,
})

const marked = {
  id: 'c-1',
  bookingId: 'b-1',
  startedAt: '2026-11-11T04:35:00Z',
  completionNote: 'Cleaned 3 rooms.',
  photoUrls: ['mock://1', 'mock://2'],
  markedCompletedAt: '2026-11-11T00:00:00Z', // auto-confirm 43h later
}

function fakeApi(overrides = {}) {
  return {
    getCompletion: vi.fn().mockResolvedValue(marked),
    confirmCompletion: vi.fn().mockResolvedValue({ ...marked, confirmedAt: NOW.toISOString(), confirmationType: 'CUSTOMER' }),
    raiseIssue: vi.fn().mockResolvedValue({ ...marked, issueRaisedAt: NOW.toISOString(), disputeId: 'DSP-1043' }),
    reportNoShow: vi.fn().mockResolvedValue({ bookingId: 'b-1', type: 'NO_SHOW_BY_PROVIDER', bookingStatus: 'DISPUTED' }),
    supportsEvidence: false,
    uploadEvidence: vi.fn(),
    ...overrides,
  }
}

const dialog = () => screen.getByRole('dialog')

describe('CustomerCompletionSection (S04–S06)', () => {
  it('COMPLETION_PENDING: shows the provider note, countdown and both actions', async () => {
    render(<CustomerCompletionSection booking={booking('COMPLETION_PENDING')} api={fakeApi()} now={NOW} />)

    expect(await screen.findByText('“Cleaned 3 rooms.”')).toBeTruthy()
    expect(screen.getByText(/Auto-confirms in 43h 0m/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Raise Issue/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Confirm Completion/ })).toBeTruthy()
  })

  it('S04: confirms completion and shows the completed state with "Leave a review"', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    const onStatus = vi.fn()
    const onLeaveReview = vi.fn()
    render(
      <CustomerCompletionSection
        booking={booking('COMPLETION_PENDING')}
        api={api}
        now={NOW}
        onBookingStatusChange={onStatus}
        onLeaveReview={onLeaveReview}
      />,
    )

    await user.click(await screen.findByRole('button', { name: /Confirm Completion/ }))
    expect(screen.getByText('Confirm the service is completed?')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Yes, confirm' }))

    await waitFor(() => expect(api.confirmCompletion).toHaveBeenCalledWith('b-1'))
    expect(await screen.findByText('Thanks! Your booking is completed.')).toBeTruthy()
    expect(onStatus).toHaveBeenCalledWith('COMPLETED')
    await user.click(screen.getByRole('button', { name: /Leave a review/ }))
    expect(onLeaveReview).toHaveBeenCalled()
  })

  it('S04: shows the backend error inside the dialog when confirming fails', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      confirmCompletion: vi.fn().mockRejectedValue({
        response: { status: 409, data: { message: 'Completion can only be confirmed while it is pending' } },
      }),
    })
    render(<CustomerCompletionSection booking={booking('COMPLETION_PENDING')} api={api} now={NOW} />)

    await user.click(await screen.findByRole('button', { name: /Confirm Completion/ }))
    await user.click(screen.getByRole('button', { name: 'Yes, confirm' }))

    expect(await screen.findByText('Completion can only be confirmed while it is pending')).toBeTruthy()
  })

  it('S05: requires a reason and a description of at least 10 characters', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    render(<CustomerCompletionSection booking={booking('COMPLETION_PENDING')} api={api} now={NOW} />)

    await user.click(await screen.findByRole('button', { name: /Raise Issue/ }))
    await user.click(screen.getByRole('button', { name: 'Submit issue' }))
    expect(screen.getByText('Select a reason.')).toBeTruthy()
    expect(screen.getByText('Describe the problem.')).toBeTruthy()

    await user.type(screen.getByRole('textbox'), 'Too short')
    await user.click(screen.getByRole('button', { name: 'Submit issue' }))
    expect(screen.getByText(/at least 10 characters/)).toBeTruthy()
    expect(api.raiseIssue).not.toHaveBeenCalled()
  })

  it('S05: submits "<reason>: <description>" and shows the dispute reference', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    const onStatus = vi.fn()
    render(
      <CustomerCompletionSection
        booking={booking('COMPLETION_PENDING')}
        api={api}
        now={NOW}
        onBookingStatusChange={onStatus}
        disputeUrlFor={(id) => `/disputes/${id}`}
      />,
    )

    await user.click(await screen.findByRole('button', { name: /Raise Issue/ }))
    expect(screen.queryByText('Evidence')).toBeNull() // hidden while the API cannot store evidence
    await user.selectOptions(screen.getByRole('combobox'), 'POOR_QUALITY')
    await user.type(screen.getByRole('textbox'), 'The balcony was not cleaned at all.')
    await user.click(screen.getByRole('button', { name: 'Submit issue' }))

    await waitFor(() => expect(api.raiseIssue).toHaveBeenCalledWith('b-1', {
      reason: 'Poor quality of work: The balcony was not cleaned at all.',
      evidenceUrls: [],
    }))
    expect(await screen.findByText('Issue submitted')).toBeTruthy()
    expect(screen.getByText('DSP-1043', { selector: 'strong' })).toBeTruthy()
    expect(screen.getAllByRole('link', { name: /View dispute/ })[0].getAttribute('href')).toBe('/disputes/DSP-1043')
    expect(onStatus).toHaveBeenCalledWith('DISPUTED')
  })

  it('S06: before slot start + 2 hours shows the countdown and a disabled button', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    render(<CustomerCompletionSection booking={booking('CONFIRMED')} api={api} now={NOW} />)

    await user.click(screen.getByRole('button', { name: /Report No-Show/ }))

    expect(screen.getByText('12:00')).toBeTruthy()
    expect(screen.getByText('Time left: 1h 30m')).toBeTruthy()
    const buttons = screen.getAllByRole('button', { name: 'Report No-Show' })
    expect(buttons[buttons.length - 1].disabled).toBe(true)
    expect(api.getCompletion).not.toHaveBeenCalled()
  })

  it('S06: after the grace period reports the no-show with the optional note', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    const onStatus = vi.fn()
    const later = new Date('2026-11-11T07:00:00Z') // 12:30 Colombo
    render(<CustomerCompletionSection booking={booking('CONFIRMED')} api={api} now={later} onBookingStatusChange={onStatus} />)

    await user.click(screen.getByRole('button', { name: /Report No-Show/ }))
    expect(screen.getByText('The provider did not arrive')).toBeTruthy()
    await user.type(screen.getByRole('textbox'), 'Waited until 12:30.')
    const buttons = screen.getAllByRole('button', { name: 'Report No-Show' })
    await user.click(buttons[buttons.length - 1])

    await waitFor(() => expect(api.reportNoShow).toHaveBeenCalledWith('b-1', { note: 'Waited until 12:30.', evidenceUrls: [] }))
    expect(await screen.findByText(/Your no-show report was submitted/)).toBeTruthy()
    expect(onStatus).toHaveBeenCalledWith('DISPUTED')
  })

  it('COMPLETED with a review already written: shows "View your review"', async () => {
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue({ ...marked, confirmedAt: NOW.toISOString() }) })
    render(
      <CustomerCompletionSection booking={booking('COMPLETED')} api={api} now={NOW} hasReview onViewReview={vi.fn()} />,
    )

    expect(await screen.findByText('Thanks! Your booking is completed.')).toBeTruthy()
    expect(screen.getByRole('button', { name: /View your review/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Leave a review/ })).toBeNull()
  })
})

describe('ServiceCompletionSection – provider no-show (S06)', () => {
  it('shows the provider wording and reports the no-show', async () => {
    const user = userEvent.setup()
    const api = { ...fakeApi(), getCompletion: vi.fn(), startService: vi.fn() }
    const later = new Date('2026-11-11T07:00:00Z')
    render(<ServiceCompletionSection booking={booking('CONFIRMED')} api={api} now={later} />)

    await user.click(screen.getByRole('button', { name: /Report No-Show/ }))
    expect(screen.getByText('The customer was not available')).toBeTruthy()
    expect(dialog()).toBeTruthy()
    const buttons = screen.getAllByRole('button', { name: 'Report No-Show' })
    await user.click(buttons[buttons.length - 1])

    await waitFor(() => expect(api.reportNoShow).toHaveBeenCalledWith('b-1', { note: null, evidenceUrls: [] }))
    expect(await screen.findByText(/Your no-show report was submitted/)).toBeTruthy()
  })
})
