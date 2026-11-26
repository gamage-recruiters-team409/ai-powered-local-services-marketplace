import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { AreaAccessContext } from '../../disputes-admin/access/accessContexts'
import DisputesSection from '../pages/DisputesSection'

export const BASE = '/admin/admin/disputes'
export const ID = '6a0f00c0ffee00a000001045'

/** Renders the disputes section at `path` with the ADMIN area's access (canEdit false = SUPER_ADMIN view only). */
export function renderDisputes(path, { canEdit = true } = {}) {
  const access = { area: 'ADMIN', mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
  const router = createMemoryRouter(
    [{
      path: `${BASE}/*`,
      element: (
        <AreaAccessContext.Provider value={access}>
          <DisputesSection />
        </AreaAccessContext.Provider>
      ),
    }],
    { initialEntries: [path] },
  )
  render(<ToastProvider><RouterProvider router={router} /></ToastProvider>)
  return router
}

export function row(overrides = {}) {
  return {
    id: ID,
    bookingId: '6a0f00c0ffee00b000048195',
    customerId: 'c-1',
    providerId: 'p-1',
    raisedByRole: 'CUSTOMER',
    category: 'NO_SHOW',
    status: 'UNDER_REVIEW',
    responseDueAt: '2026-11-25T10:00:00Z',
    appealDeadline: null,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    settlementState: null,
    manualReviewReason: null,
    ...overrides,
  }
}

export function page(content, totalPages = 1) {
  return { content, page: 0, size: 10, totalElements: content.length, totalPages }
}

export const SUMMARY = Object.freeze({
  underReview: 4, awaitingResponse: 3, appealed: 1, resolvedLast30Days: 9, manualReview: 2,
  resolvedSince: '2026-10-21T10:00:00Z',
})

export function detail(overrides = {}) {
  return {
    id: ID,
    bookingId: '6a0f00c0ffee00b000048195',
    customerId: 'c-1',
    customerName: 'Nimal Perera',
    providerId: 'p-1',
    providerName: 'Kamal Silva',
    raisedBy: 'c-1',
    raisedByRole: 'CUSTOMER',
    category: 'NO_SHOW',
    description: 'The provider never arrived.',
    evidence: [],
    status: 'UNDER_REVIEW',
    response: { responderId: 'p-1', text: 'I arrived but nobody answered.', evidence: [],
      respondedAt: '2026-11-21T10:00:00Z' },
    responseDueAt: '2026-11-23T10:00:00Z',
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-21T10:00:00Z',
    decision: null,
    appealDeadline: null,
    appealStatus: 'NOT_AVAILABLE',
    appeal: null,
    finalDecision: null,
    finalizedAt: null,
    settlement: null,
    statusHistory: [
      { fromStatus: null, toStatus: 'AWAITING_RESPONSE', actorId: 'c-1', actorRole: 'CUSTOMER', reason: null,
        timestamp: '2026-11-20T10:00:00Z' },
      { fromStatus: 'AWAITING_RESPONSE', toStatus: 'UNDER_REVIEW', actorId: 'p-1', actorRole: 'PROVIDER', reason: null,
        timestamp: '2026-11-21T10:00:00Z' },
    ],
    evidenceFiles: [{ id: 'ev-1', fileName: 'door.jpg', type: 'JPG', contentType: 'image/jpeg', sizeBytes: 2048,
      uploadedByRole: 'CUSTOMER', uploadedAt: '2026-11-20T11:00:00Z' }],
    actions: { canResolve: true, canDecideAppeal: false, reasonIfNot: null },
    ...overrides,
  }
}

export const DECISION = Object.freeze({
  action: 'REFUND_PARTIAL', customerRefundPercent: 40, providerPenaltyApplied: true, providerPenaltyPercent: 30,
  decidedBy: 'admin-a', decidedByRole: 'ADMIN', decidedAt: '2026-11-22T10:00:00Z', note: 'Checked the GPS log.',
})

export function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { error, fieldErrors } } })
}
