import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { AreaAccessContext } from '../../disputes-admin/access/accessContexts'

// jsdom does not implement the native <dialog> methods the shared Modal uses; provide minimal behaviour.
if (typeof HTMLDialogElement !== 'undefined') {
  HTMLDialogElement.prototype.showModal =
    HTMLDialogElement.prototype.showModal ||
    function showModal() {
      this.open = true
    }
  HTMLDialogElement.prototype.close =
    HTMLDialogElement.prototype.close ||
    function close() {
      this.open = false
      this.dispatchEvent(new Event('close'))
    }
}

/**
 * Renders an operations page inside a data router with the given admin area's access, so the same component can be
 * verified in both the ADMIN and SUPPORT_ADMIN routes (SUPER_ADMIN = view-only when canEdit is false).
 */
export function renderOpsPage(Page, { path = '/', area = 'SUPPORT_ADMIN', canEdit = true } = {}) {
  const access = { area, mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
  const router = createMemoryRouter(
    [
      {
        path: '*',
        element: (
          <AreaAccessContext.Provider value={access}>
            <ToastProvider>
              <Page />
            </ToastProvider>
          </AreaAccessContext.Provider>
        ),
      },
    ],
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return router
}

export const section = (data) => ({ available: true, data })
export const unavailable = (reason) => ({ available: false, reason })

export function opsPage(content, totalElements = content.length, number = 0, size = 20) {
  return { content, page: number, size, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)) }
}

export function overdueRow(overrides = {}) {
  return {
    bookingId: 'bk-1',
    ref: 'BK-9102',
    serviceTitle: 'Deep Cleaning',
    customerId: 'cust-1111111111',
    providerId: 'prov-2222222222',
    serviceDate: '2026-03-21T00:00:00Z',
    daysOverdue: 3,
    caseStatus: 'OPEN',
    lastAction: null,
    ...overrides,
  }
}

export function overdueSection(rows = [overdueRow()]) {
  return section({
    rows: opsPage(rows),
    summary: { overdueNow: 7, over24h: 3, resolvedToday: 4 },
    distribution: { oneDay: 2, twoDays: 2, threeDays: 1, fourPlusDays: 2 },
  })
}

export function lateStartRow(overrides = {}) {
  return {
    bookingId: 'bk-late-1',
    ref: 'BK-9304',
    serviceTitle: 'AC Installation',
    customerId: 'cust-3',
    providerId: 'prov-3',
    scheduledStart: '2026-11-26T03:00:00Z',
    minutesLate: 255,
    urgency: 'CRITICAL',
    caseStatus: 'OPEN',
    lastAction: null,
    ...overrides,
  }
}

export function noShowRow(overrides = {}) {
  return {
    disputeId: 'dsp-1',
    bookingId: 'bk-ns-1',
    customerId: 'cust-9',
    providerId: 'prov-9',
    status: 'UNDER_REVIEW',
    createdAt: '2026-11-25T10:00:00Z',
    ...overrides,
  }
}

export function lateCasesResponse({ lateAvailable = false, noShowRows = [noShowRow()] } = {}) {
  return {
    lateStarts: lateAvailable
      ? section({
          rows: opsPage([lateStartRow()]),
          summary: { open: 14, critical: 5, resolvedToday: 9 },
          perDay: Array.from({ length: 14 }, (_, i) => ({ date: `2026-11-${13 + i}`, count: i % 4 })),
        })
      : unavailable('Late-start tracking is not available yet (WP05).'),
    noShowReports: section({ rows: opsPage(noShowRows), counts: { total: noShowRows.length, byStatus: { UNDER_REVIEW: noShowRows.length } } }),
  }
}

export function repeatRow(overrides = {}) {
  return {
    userId: 'user-david-miller',
    role: 'CUSTOMER',
    count: 8,
    lastCancelledAt: '2026-11-26T05:00:00Z',
    caseStatus: 'FLAGGED',
    lastAction: null,
    ...overrides,
  }
}

export function repeatSection(rows = [repeatRow()]) {
  return section({
    rows: opsPage(rows),
    summary: { flagged: 6, customers: 4, providers: 2 },
    top5: rows,
    suspensionRecommendations: [],
    thresholdMinCount: 4,
    thresholdDays: 30,
  })
}

export function dashboardResponse(overrides = {}) {
  return {
    overdueCount: section(7),
    lateCount: unavailable('Late-start tracking is not available yet (WP05).'),
    flaggedUsersCount: section(6),
    overdueDistribution: section({ oneDay: 2, twoDays: 2, threeDays: 1, fourPlusDays: 2 }),
    latePerDay: unavailable('Late-start tracking is not available yet (WP05).'),
    mostOverdue: section([overdueRow()]),
    ...overrides,
  }
}

export function apiError(status, error, message = error) {
  return Object.assign(new Error(error), { response: { status, data: { status, error, message } } })
}
