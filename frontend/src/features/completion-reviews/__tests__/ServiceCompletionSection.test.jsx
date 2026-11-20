// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ServiceCompletionSection from '../components/ServiceCompletionSection'

// These screen tests open dialogs and type text; slower PCs need more than the 5 s default.
vi.setConfig({ testTimeout: 20000 })

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
  providerName: 'Bright Home Cleaning',
  slotStart: '2026-11-11T04:30:00Z',
  slotEnd: '2026-11-11T06:30:00Z',
  status,
  ...extra,
})

const started = { id: 'c-1', bookingId: 'b-1', startedAt: '2026-11-11T04:35:00Z', photoUrls: [] }
const marked = {
  ...started,
  completionNote: 'Cleaned 3 rooms.',
  photoUrls: ['mock://1', 'mock://2'],
  markedCompletedAt: '2026-11-11T00:00:00Z',
}

function fakeApi(overrides = {}) {
  return {
    getCompletion: vi.fn().mockResolvedValue(started),
    startService: vi.fn().mockResolvedValue(started),
    markCompleted: vi.fn().mockResolvedValue(marked),
    confirmCompletion: vi.fn(),
    uploadCompletionPhoto: vi.fn().mockImplementation(async (f) => `mock://${f.name}`),
    ...overrides,
  }
}

describe('ServiceCompletionSection (provider)', () => {
  it('CONFIRMED: starts the service after confirming in the S01 dialog', async () => {
    const user = userEvent.setup()
    const api = fakeApi()
    const onStatus = vi.fn()
    render(<ServiceCompletionSection booking={booking('CONFIRMED')} api={api} now={NOW} onBookingStatusChange={onStatus} />)

    expect(screen.getByText('today, 10:00 – 12:00')).toBeTruthy()
    expect(api.getCompletion).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Start Service' }))
    expect(screen.getByText('Start this service?')).toBeTruthy()
    expect(screen.getByText('The customer will be notified that the service has started.')).toBeTruthy()

    const buttons = screen.getAllByRole('button', { name: 'Start Service' })
    await user.click(buttons[buttons.length - 1])

    await waitFor(() => expect(api.startService).toHaveBeenCalledWith('b-1'))
    await screen.findByText('Service started at 10:05. Nimal P. has been notified.')
    expect(onStatus).toHaveBeenCalledWith('IN_PROGRESS')
    expect(screen.getByRole('button', { name: 'Mark Completed' })).toBeTruthy()
  })

  it('CONFIRMED: shows the backend error in the dialog when starting fails', async () => {
    const user = userEvent.setup()
    const error = Object.assign(new Error('x'), {
      response: { status: 409, data: { error: 'INVALID_STATUS_TRANSITION', message: 'Service can only be started for a confirmed booking' } },
    })
    const api = fakeApi({ startService: vi.fn().mockRejectedValue(error) })
    render(<ServiceCompletionSection booking={booking('CONFIRMED')} api={api} now={NOW} />)

    await user.click(screen.getByRole('button', { name: 'Start Service' }))
    const buttons = screen.getAllByRole('button', { name: 'Start Service' })
    await user.click(buttons[buttons.length - 1])

    expect((await screen.findByRole('alert')).textContent).toContain('Service can only be started for a confirmed booking')
  })

  it('IN_PROGRESS after the slot ended: shows the overdue warning', async () => {
    const api = fakeApi()
    render(<ServiceCompletionSection booking={booking('IN_PROGRESS')} api={api} now={new Date('2026-11-11T09:40:00Z')} />)

    expect(await screen.findByText('The booking time ended 3 hours ago.')).toBeTruthy()
    expect(screen.getByText(/Started at 10:05\./)).toBeTruthy()
  })

  it('IN_PROGRESS: S02 rejects unsupported photos and submits note + valid photos', async () => {
    const user = userEvent.setup({ applyAccept: false })
    const api = fakeApi()
    const onStatus = vi.fn()
    render(<ServiceCompletionSection booking={booking('IN_PROGRESS')} api={api} now={NOW} onBookingStatusChange={onStatus} />)

    await user.click(await screen.findByRole('button', { name: 'Mark Completed' }))
    await user.type(screen.getByPlaceholderText('Describe the work you completed...'), 'Cleaned 3 rooms.')
    expect(screen.getByText('16 / 500')).toBeTruthy()

    const input = screen.getByTestId('sc-photo-input')
    await user.upload(input, [
      new File(['a'], 'photo_1.jpg', { type: 'image/jpeg' }),
      new File(['b'], 'photo_3.heic', { type: 'image/heic' }),
    ])
    expect(screen.getByText('photo_3.heic – this file type is not supported')).toBeTruthy()

    const submit = () => screen.getAllByRole('button', { name: 'Mark Completed' }).at(-1)
    expect(submit().disabled).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Remove photo_3.heic' }))
    expect(submit().disabled).toBe(false)
    await user.click(submit())

    await waitFor(() => expect(api.markCompleted).toHaveBeenCalledWith('b-1', {
      completionNote: 'Cleaned 3 rooms.',
      photoUrls: ['mock://photo_1.jpg'],
    }))
    expect(onStatus).toHaveBeenCalledWith('COMPLETION_PENDING')
    expect(await screen.findByText(/Waiting for Nimal P\. to confirm/)).toBeTruthy()
  })

  it('COMPLETION_PENDING: shows the auto-confirm countdown, note and earnings info', async () => {
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue(marked) })
    render(<ServiceCompletionSection booking={booking('COMPLETION_PENDING')} api={api} now={new Date('2026-11-11T16:40:00Z')} />)

    expect(await screen.findByText('Auto-confirms in 31h 20m')).toBeTruthy()
    expect(screen.getByText('“Cleaned 3 rooms.”')).toBeTruthy()
    expect(screen.getByText(/released 72 hours after completion/)).toBeTruthy()
  })

  it('DISPUTED: shows the dispute banner, reason and link', async () => {
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue({ ...marked, disputeId: 'DSP-1043' }) })
    render(
      <ServiceCompletionSection
        booking={booking('DISPUTED')}
        api={api}
        now={NOW}
        dispute={{ id: 'DSP-1043', reason: 'Balcony was not cleaned.', url: '/disputes/DSP-1043' }}
      />,
    )

    expect(await screen.findByText('A dispute is open for this booking (DSP-1043).')).toBeTruthy()
    expect(screen.getByText(/Balcony was not cleaned\./)).toBeTruthy()
    expect(screen.getByRole('link', { name: /View dispute/ }).getAttribute('href')).toBe('/disputes/DSP-1043')
  })

  it('Resolved dispute: shows the decision and an "Open appeal" link to the Dispute Details screen', async () => {
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue({ ...marked, disputeId: 'DSP-1043' }) })
    render(
      <ServiceCompletionSection
        booking={booking('PARTIALLY_REFUNDED')}
        api={api}
        now={NOW}
        dispute={{
          id: 'DSP-1043', status: 'RESOLVED', resolvedAt: '2026-11-11T08:50:00Z',
          outcome: '30% refunded to the customer.', canAppeal: true, url: '/disputes/DSP-1043',
        }}
      />,
    )

    expect(await screen.findByText(/was resolved on Wed, 11 Nov 2026 at 14:20/)).toBeTruthy()
    expect(screen.getByText(/reviewed by a different admin/)).toBeTruthy()
    expect(screen.getByRole('link', { name: /Open appeal/ }).getAttribute('href')).toBe('/disputes/DSP-1043')
  })

  it('Resolved dispute without appeal right: shows "View dispute" only', async () => {
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue({ ...marked, disputeId: 'DSP-1043' }) })
    render(
      <ServiceCompletionSection
        booking={booking('COMPLETED')}
        api={api}
        now={NOW}
        dispute={{ id: 'DSP-1043', status: 'RESOLVED', canAppeal: false, url: '/disputes/DSP-1043' }}
      />,
    )

    expect(await screen.findByRole('link', { name: /View dispute/ })).toBeTruthy()
    expect(screen.queryByRole('link', { name: /Open appeal/ })).toBeNull()
  })

  it('COMPLETED: shows who confirmed and opens the S03 completion details', async () => {
    const user = userEvent.setup()
    const confirmed = { ...marked, confirmedAt: '2026-11-11T08:50:00Z', confirmationType: 'CUSTOMER' }
    const api = fakeApi({ getCompletion: vi.fn().mockResolvedValue(confirmed) })
    render(<ServiceCompletionSection booking={booking('COMPLETED')} api={api} now={NOW} />)

    expect(await screen.findByText(/Nimal Perera confirmed the service on Wed, 11 Nov 2026 at 14:20/)).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'View completion details' }))
    expect(screen.getByText('Completion details')).toBeTruthy()
    expect(screen.getByText('Confirmed by customer')).toBeTruthy()
    expect(screen.getByText(/14 Nov 2026 · if no dispute is raised/)).toBeTruthy()
  })
})
