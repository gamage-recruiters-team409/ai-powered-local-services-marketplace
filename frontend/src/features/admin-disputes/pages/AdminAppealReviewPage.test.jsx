// @vitest-environment jsdom

import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/adminDisputesService', () => ({
  getAdminDispute: vi.fn(),
  resolveAdminDispute: vi.fn(),
  decideAdminAppeal: vi.fn(),
  isRequestCancelled: () => false,
}))

import { decideAdminAppeal, getAdminDispute } from '../services/adminDisputesService'
import { apiError, BASE, DECISION, detail, ID, renderDisputes } from '../testing/renderAdminDisputes'

const APPEAL = { appealedBy: 'c-1', appealedByRole: 'CUSTOMER', grounds: 'The refund is too low for a no-show.',
  evidence: [], filedAt: '2026-11-23T08:00:00Z', reviewDueAt: '2099-11-27T08:00:00Z', verdict: null }

function appealed(actions, overrides = {}) {
  return detail({ status: 'APPEALED', decision: DECISION, appealStatus: 'FILED', appeal: APPEAL, actions, ...overrides })
}

async function openAppeal(dispute, options) {
  getAdminDispute.mockResolvedValue(dispute)
  renderDisputes(`${BASE}/${ID}/appeal`, options)
  await screen.findByRole('heading', { level: 1, name: 'Appeal review · D-001045' })
}

const review = () => screen.getByRole('region', { name: 'Your review' })

beforeEach(() => {
  getAdminDispute.mockReset()
  decideAdminAppeal.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('Admin appeal review', () => {
  it('shows the original decision, the appeal reason and party, and the review deadline', async () => {
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: true, reasonIfNot: null }))

    expect(screen.getByText('Refund partial (40% to the customer) + 30% provider penalty')).toBeTruthy()
    expect(screen.getByText('The refund is too low for a no-show.')).toBeTruthy()
    expect(screen.getByText(/Filed by the customer/)).toBeTruthy()
    expect(screen.getByText('Review due')).toBeTruthy()
    expect(screen.getByText('Time left')).toBeTruthy()
  })

  it('the admin who made the decision -> "A different admin must review this appeal", no form', async () => {
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: false, reasonIfNot: 'SAME_ADMIN_AS_DECISION' }))

    expect(within(review()).getByText('A different admin must review this appeal.')).toBeTruthy()
    expect(within(review()).queryByRole('radio')).toBeNull()
  })

  it('SUPER_ADMIN -> read only, no form', async () => {
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: false, reasonIfNot: 'VIEW_ONLY' }), { canEdit: false })

    expect(within(review()).getByText(/View only/)).toBeTruthy()
    expect(within(review()).queryByRole('button', { name: 'Submit appeal decision' })).toBeNull()
  })

  it('UPHOLD sends only the verdict and the note', async () => {
    decideAdminAppeal.mockResolvedValue(appealed({ canResolve: false, canDecideAppeal: false,
      reasonIfNot: 'ALREADY_DECIDED' }, { status: 'RESOLVED_FINAL',
      appeal: { ...APPEAL, verdict: 'UPHOLD', reviewedBy: 'admin-b', reviewedAt: '2026-11-24T08:00:00Z' } }))
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: true, reasonIfNot: null }))

    fireEvent.click(within(review()).getByRole('radio', { name: /Uphold/ }))
    expect(within(review()).queryByRole('radio', { name: /Refund full/ })).toBeNull()
    fireEvent.change(within(review()).getByLabelText(/Internal note/), { target: { value: 'The decision was fair.' } })
    fireEvent.click(within(review()).getByRole('button', { name: 'Submit appeal decision' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(decideAdminAppeal).toHaveBeenCalledWith(ID, { verdict: 'UPHOLD', note: 'The decision was fair.' }))
    expect(await screen.findByText('Decision upheld')).toBeTruthy()
  })

  it('CHANGE shows the same decision fields and sends the new decision', async () => {
    decideAdminAppeal.mockResolvedValue(appealed({ canResolve: false, canDecideAppeal: false,
      reasonIfNot: 'ALREADY_DECIDED' }))
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: true, reasonIfNot: null }))

    fireEvent.click(within(review()).getByRole('radio', { name: /Change/ }))
    fireEvent.click(within(review()).getByRole('radio', { name: /Refund full/ }))
    fireEvent.change(within(review()).getByLabelText(/Internal note/), { target: { value: 'The provider never came.' } })
    fireEvent.click(within(review()).getByRole('button', { name: 'Submit appeal decision' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(decideAdminAppeal).toHaveBeenCalledWith(ID, { verdict: 'CHANGE', action: 'REFUND_FULL',
      customerRefundPercent: null, applyProviderPenalty: false, note: 'The provider never came.' }))
  })

  it('no verdict chosen -> validation; 403 APPEAL_REVIEWER_MUST_DIFFER is still handled', async () => {
    decideAdminAppeal.mockRejectedValue(apiError(403, 'APPEAL_REVIEWER_MUST_DIFFER'))
    await openAppeal(appealed({ canResolve: false, canDecideAppeal: true, reasonIfNot: null }))

    fireEvent.click(within(review()).getByRole('button', { name: 'Submit appeal decision' }))
    expect(await within(review()).findByText('Choose Uphold or Change.')).toBeTruthy()

    fireEvent.click(within(review()).getByRole('radio', { name: /Uphold/ }))
    fireEvent.change(within(review()).getByLabelText(/Internal note/), { target: { value: 'Fair decision.' } })
    fireEvent.click(within(review()).getByRole('button', { name: 'Submit appeal decision' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm' }))

    expect(await screen.findByText('A different admin must review this appeal.')).toBeTruthy()
  })

  it('no appeal on the dispute -> a short note', async () => {
    await openAppeal(detail({ actions: { canResolve: true, canDecideAppeal: false, reasonIfNot: null } }))
    expect(screen.getByText('No appeal has been filed for this dispute.')).toBeTruthy()
  })
})
