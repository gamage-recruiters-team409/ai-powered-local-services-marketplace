// @vitest-environment jsdom

import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/adminDisputesService', () => ({
  getAdminDispute: vi.fn(),
  resolveAdminDispute: vi.fn(),
  decideAdminAppeal: vi.fn(),
  isRequestCancelled: () => false,
}))
vi.mock('../../disputes/services/disputesApi', () => ({
  fetchEvidenceUrl: vi.fn(),
  uploadEvidence: vi.fn(),
}))

import { getAdminDispute, resolveAdminDispute } from '../services/adminDisputesService'
import { apiError, BASE, DECISION, detail, ID, renderDisputes } from '../testing/renderAdminDisputes'

const CASE = `${BASE}/${ID}`

beforeEach(() => {
  getAdminDispute.mockReset()
  resolveAdminDispute.mockReset()
})

afterEach(() => {
  cleanup()
})

async function openCase(dispute, options) {
  getAdminDispute.mockResolvedValue(dispute)
  const router = renderDisputes(CASE, options)
  await screen.findByRole('heading', { level: 1, name: 'Case D-001045' })
  return router
}

const decisionPanel = () => screen.getByRole('region', { name: 'Decision' })

describe('Admin dispute case', () => {
  it('header, both statements, evidence, status history and the placeholders (no fake data)', async () => {
    await openCase(detail())

    expect(screen.getByText('Nimal Perera')).toBeTruthy()
    expect(screen.getByText('The provider never arrived.')).toBeTruthy()
    expect(screen.getByText('I arrived but nobody answered.')).toBeTruthy()
    expect(screen.getByText('door.jpg')).toBeTruthy()
    expect(screen.getByRole('region', { name: 'Status history' })).toBeTruthy()
    expect(screen.getByText('Chat history will appear when messaging is connected.')).toBeTruthy()
    expect(screen.getByText('AI summary will appear when AI-005 is connected.')).toBeTruthy()
  })

  it('decision form rules: penalty only with a refund, partial needs 1-99%, note required', async () => {
    await openCase(detail())
    const panel = decisionPanel()
    const penalty = within(panel).getByRole('checkbox', { name: /30% provider penalty/ })

    fireEvent.click(within(panel).getByRole('radio', { name: /Release to provider/ }))
    expect(penalty.disabled).toBe(true)
    fireEvent.click(within(panel).getByRole('radio', { name: /Refund full/ }))
    expect(penalty.disabled).toBe(false)
    expect(within(panel).getByText(/Refunds are recorded and sent to manual review/)).toBeTruthy()

    fireEvent.click(within(panel).getByRole('radio', { name: /Refund partial/ }))
    fireEvent.change(within(panel).getByLabelText('Refund to the customer (%)'), { target: { value: '100' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Submit decision' }))

    expect(await within(panel).findByText('Enter a whole number from 1 to 99.')).toBeTruthy()
    expect(within(panel).getByText('Add an internal note explaining the decision.')).toBeTruthy()
    expect(resolveAdminDispute).not.toHaveBeenCalled()
  })

  it('switching from a refund with penalty to release clears the penalty', async () => {
    await openCase(detail())
    const panel = decisionPanel()

    fireEvent.click(within(panel).getByRole('radio', { name: /Refund full/ }))
    const penalty = within(panel).getByRole('checkbox', { name: /30% provider penalty/ })
    fireEvent.click(penalty)
    expect(penalty.checked).toBe(true)
    fireEvent.click(within(panel).getByRole('radio', { name: /Release to provider/ }))

    expect(penalty.checked).toBe(false)
  })

  it('confirm -> POST resolve with the decision, then the summary replaces the form', async () => {
    resolveAdminDispute.mockResolvedValue(detail({ status: 'RESOLVED', decision: DECISION,
      actions: { canResolve: false, canDecideAppeal: false, reasonIfNot: 'ALREADY_DECIDED' } }))
    await openCase(detail())
    const panel = decisionPanel()

    fireEvent.click(within(panel).getByRole('radio', { name: /Refund partial/ }))
    fireEvent.change(within(panel).getByLabelText('Refund to the customer (%)'), { target: { value: '40' } })
    fireEvent.click(within(panel).getByRole('checkbox', { name: /30% provider penalty/ }))
    fireEvent.change(within(panel).getByLabelText(/Internal note/), { target: { value: ' Checked the GPS log. ' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Submit decision' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Refund partial \(40% to the customer\) \+ 30% provider penalty/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(resolveAdminDispute).toHaveBeenCalledWith(ID, { action: 'REFUND_PARTIAL',
      customerRefundPercent: 40, applyProviderPenalty: true, note: 'Checked the GPS log.' }))
    expect(await screen.findByText('Refund partial (40% to the customer) + 30% provider penalty')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Submit decision' })).toBeNull()
  })

  it('a 409 from resolve is shown in the form', async () => {
    resolveAdminDispute.mockRejectedValue(apiError(409, 'INVALID_DISPUTE_STATUS'))
    await openCase(detail())
    const panel = decisionPanel()

    fireEvent.click(within(panel).getByRole('radio', { name: /Release to provider/ }))
    fireEvent.change(within(panel).getByLabelText(/Internal note/), { target: { value: 'Provider showed proof.' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Submit decision' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm' }))

    expect(await screen.findByText(/can no longer be decided in its current state/)).toBeTruthy()
  })

  it('SUPER_ADMIN (view only) never gets the decision form', async () => {
    await openCase(detail({ actions: { canResolve: false, canDecideAppeal: false, reasonIfNot: 'VIEW_ONLY' } }),
      { canEdit: false })

    expect(within(decisionPanel()).queryByRole('button', { name: 'Submit decision' })).toBeNull()
    expect(within(decisionPanel()).getByText(/View only/)).toBeTruthy()
    expect(screen.getByText('View only')).toBeTruthy()
  })

  it('response window still open -> no form, a clear note', async () => {
    await openCase(detail({ status: 'AWAITING_RESPONSE', response: null,
      actions: { canResolve: false, canDecideAppeal: false, reasonIfNot: 'NOT_READY' } }))

    expect(within(decisionPanel()).getByText(/once the response window has closed/)).toBeTruthy()
  })

  it.each([
    ['MANUAL_REVIEW', 'REFUND_PENDING_INTEGRATION', 'Manual review',
      /Refunds are recorded and sent to manual review until finance integration is complete/],
    ['REJECTED', 'SETTLEMENT_REJECTED:NO_CAPTURED_PAYMENT', 'Rejected', /Finance rejected the settlement \(code NO_CAPTURED_PAYMENT\)/],
    ['FAILED', null, 'Failed, will retry', /retried automatically/],
    ['NOT_APPLIED', null, 'Waiting for integration', /finance integration is not connected yet/],
  ])('settlement panel (read-only): %s', async (state, manualReviewReason, label, text) => {
    await openCase(detail({ status: 'RESOLVED_FINAL', decision: DECISION, finalDecision: DECISION,
      actions: { canResolve: false, canDecideAppeal: false, reasonIfNot: 'ALREADY_DECIDED' },
      settlement: { state, financeState: state === 'NOT_APPLIED' ? 'NOT_APPLIED' : 'PENDING', bookingState: 'PENDING',
        attempts: state === 'FAILED' ? 2 : 0, lastAttemptAt: '2026-11-23T10:00:00Z', nextAttemptAt: null,
        lastErrorCode: state === 'FAILED' ? 'QueryTimeoutException' : null,
        notAppliedReason: state === 'NOT_APPLIED' ? 'WP07_NOT_INTEGRATED' : null, manualReviewReason } }))

    const panel = screen.getByRole('region', { name: 'Settlement' })
    expect(within(panel).getAllByText(label).length).toBeGreaterThan(0)
    expect(within(panel).getByText(text)).toBeTruthy()
    expect(within(panel).getByText('1. Finance (payout, refund, penalty)')).toBeTruthy()
    expect(within(panel).getByText('2. Booking status')).toBeTruthy()
    expect(within(panel).queryByRole('button')).toBeNull()
  })

  it('an appealed case links to the appeal review', async () => {
    await openCase(detail({ status: 'APPEALED', decision: DECISION, appealStatus: 'FILED',
      appeal: { appealedBy: 'c-1', appealedByRole: 'CUSTOMER', grounds: 'The refund is too low.', evidence: [],
        filedAt: '2026-11-23T08:00:00Z', reviewDueAt: '2026-11-27T08:00:00Z' },
      actions: { canResolve: false, canDecideAppeal: true, reasonIfNot: null } }))

    expect(screen.getByRole('link', { name: 'Review appeal' }).getAttribute('href')).toBe(`${CASE}/appeal`)
    expect(screen.getByText('The refund is too low.')).toBeTruthy()
  })

  it('unknown case -> not found message with a way back', async () => {
    getAdminDispute.mockRejectedValue(apiError(404, 'DISPUTE_NOT_FOUND'))
    renderDisputes(CASE)

    expect(await screen.findByText('This dispute could not be found.')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Back to disputes' }).getAttribute('href')).toBe(BASE)
  })
})
