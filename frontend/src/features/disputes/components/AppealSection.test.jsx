// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/disputesApi', () => ({
  submitAppeal: vi.fn(),
}))

import { ToastProvider } from '../../../shared/components/feedback'
import { submitAppeal } from '../services/disputesApi'
import { AppealSection } from './AppealSection'

const NOW = Date.parse('2026-11-20T10:00:00Z')
const HOUR = 3600 * 1000

function resolved(overrides = {}) {
  return {
    id: 'd-1',
    status: 'RESOLVED',
    appealEligible: true,
    appealDeadline: new Date(NOW + 30 * HOUR).toISOString(),
    appealStatus: 'OPEN',
    appeal: null,
    outcome: {
      action: 'REFUND_PARTIAL',
      customerRefundPercent: 40,
      providerPenaltyApplied: null,
      decidedAt: new Date(NOW - 18 * HOUR).toISOString(),
      finalDecision: false,
      paymentNote: null,
    },
    ...overrides,
  }
}

function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { error, fieldErrors } } })
}

function renderSection(dispute, { viewerRole = 'CUSTOMER' } = {}) {
  const onUpdated = vi.fn()
  const onReload = vi.fn()
  render(
    <ToastProvider>
      <AppealSection dispute={dispute} now={NOW} viewerRole={viewerRole} onUpdated={onUpdated} onReload={onReload} />
    </ToastProvider>,
  )
  return { onUpdated, onReload }
}

const reasonBox = () => screen.queryByLabelText(/why are you appealing/i)

beforeEach(() => {
  submitAppeal.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('AppealSection: when the appeal form shows', () => {
  it('RESOLVED, eligible and before the deadline: decision summary + "Open appeal" with deadline and countdown', () => {
    renderSection(resolved())

    expect(screen.getByText('Admin decision')).toBeTruthy()
    expect(screen.getByText('Partial refund to the customer (40%)')).toBeTruthy()
    expect(screen.getByText('Open appeal')).toBeTruthy()
    expect(screen.getByText(/You can appeal until/)).toBeTruthy()
    expect(screen.getByText('1 day 6 hours left')).toBeTruthy()
    expect(reasonBox()).toBeTruthy()
    expect(screen.getByText('Payment handling for this dispute is being processed.')).toBeTruthy()
  })

  it('deadline passed -> no form, a short note instead', () => {
    renderSection(resolved({ appealDeadline: new Date(NOW - HOUR).toISOString() }))

    expect(reasonBox()).toBeNull()
    expect(screen.getByText(/appeal window for this dispute has closed/i)).toBeTruthy()
  })

  it('not eligible (e.g. the other party already appealed) -> no form', () => {
    renderSection(resolved({ appealEligible: false, appeal: { appealedByRole: 'PROVIDER' } }))

    expect(reasonBox()).toBeNull()
    expect(screen.getByText(/already been filed/i)).toBeTruthy()
  })

  it('APPEALED -> "Appeal under review", no form', () => {
    renderSection(resolved({
      status: 'APPEALED',
      appealEligible: false,
      appealStatus: 'FILED',
      appeal: { appealedByRole: 'CUSTOMER', filedAt: new Date(NOW - HOUR).toISOString(),
        reviewDueAt: new Date(NOW + 95 * HOUR).toISOString() },
    }))

    expect(screen.getByText('Appeal under review')).toBeTruthy()
    expect(screen.getByText(/You appealed this decision/)).toBeTruthy()
    expect(reasonBox()).toBeNull()
  })

  it.each([
    ['UPHELD', 'Decision upheld', /decision was upheld/],
    ['CHANGED', 'Decision changed', /decision was changed/],
    ['CLOSED', 'Final decision', /This decision is final/],
  ])('RESOLVED_FINAL with appeal %s -> "%s" and final', (appealStatus, heading, text) => {
    renderSection(resolved({
      status: 'RESOLVED_FINAL',
      appealEligible: false,
      appealStatus,
      outcome: { ...resolved().outcome, action: 'RELEASE_TO_PROVIDER', finalDecision: true,
        paymentNote: 'Payment handling for this dispute is being processed.' },
    }))

    expect(screen.getByText(heading)).toBeTruthy()
    expect(screen.getByText(text)).toBeTruthy()
    expect(screen.getByText('Payment released to the provider')).toBeTruthy()
    expect(screen.getByText('Final')).toBeTruthy()
    expect(reasonBox()).toBeNull()
    expect(screen.queryByText(/refund (was|has been) (paid|made)/i)).toBeNull()
  })

  it('no decision yet -> nothing rendered', () => {
    const { container } = render(<ToastProvider><AppealSection dispute={{ status: 'UNDER_REVIEW', outcome: null }}
      now={NOW} viewerRole="CUSTOMER" onUpdated={vi.fn()} /></ToastProvider>)
    expect(container.querySelector('section')).toBeNull()
  })

  it('provider sees the penalty note; the customer never does', () => {
    renderSection(resolved({ outcome: { ...resolved().outcome, providerPenaltyApplied: true } }), { viewerRole: 'PROVIDER' })
    expect(screen.getByText(/provider penalty applies/i)).toBeTruthy()
  })
})

describe('AppealSection: submitting', () => {
  it('validates the reason, asks to confirm, then POSTs the trimmed reason and reports the updated dispute', async () => {
    const updated = resolved({ status: 'APPEALED', appealEligible: false })
    submitAppeal.mockResolvedValue(updated)
    const { onUpdated } = renderSection(resolved())

    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    expect(await screen.findByText('Explain why you are appealing.')).toBeTruthy()

    fireEvent.change(reasonBox(), { target: { value: 'too short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    expect(await screen.findByText('Write at least 10 characters.')).toBeTruthy()
    expect(submitAppeal).not.toHaveBeenCalled()

    fireEvent.change(reasonBox(), { target: { value: '  The provider never arrived; the GPS log proves it.  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Submit appeal' }))

    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(updated))
    expect(submitAppeal).toHaveBeenCalledWith('d-1', 'The provider never arrived; the GPS log proves it.')
    expect(await screen.findByText(/Your appeal was submitted/)).toBeTruthy()
  })

  it.each([
    ['APPEAL_WINDOW_CLOSED', 'The appeal window for this dispute has closed.'],
    ['APPEAL_ALREADY_FILED', 'An appeal has already been filed for this dispute.'],
  ])('409 %s -> toast with a clear message and the dispute is reloaded', async (code, message) => {
    submitAppeal.mockRejectedValue(apiError(409, code))
    const { onReload, onUpdated } = renderSection(resolved())

    fireEvent.change(reasonBox(), { target: { value: 'The decision ignores the photos I sent.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit appeal' }))

    expect(await screen.findByText(message)).toBeTruthy()
    expect(onReload).toHaveBeenCalled()
    expect(onUpdated).not.toHaveBeenCalled()
  })

  it('403 -> toast; 400 field error shows under the reason', async () => {
    submitAppeal.mockRejectedValueOnce(apiError(403, 'NOT_DISPUTE_PARTY'))
      .mockRejectedValueOnce(apiError(400, 'VALIDATION_FAILED', [{ field: 'grounds', message: 'grounds is required' }]))
    renderSection(resolved())

    fireEvent.change(reasonBox(), { target: { value: 'The decision ignores the photos I sent.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit appeal' }))
    expect(await screen.findByText('You do not have access to this dispute.')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Submit appeal' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit appeal' }))
    expect(await screen.findByText('grounds is required')).toBeTruthy()
  })
})
