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
 * Renders a super admin page inside a data router with SUPER_ADMIN access context.
 */
export function renderSuperAdminPage(Page, { path = '/', canEdit = true } = {}) {
  const access = { area: 'SUPER_ADMIN', mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
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

export function apiError(status, error, message = error) {
  return Object.assign(new Error(error), { response: { status, data: { status, error, message } } })
}

/** Build a mock analytics summary response with real + connected metrics. */
export function analyticsSummary(overrides = {}) {
  return {
    openDisputes: { available: true, value: 19 },
    adminAccounts: { available: true, value: 5 },
    pendingVerifications: { available: true, value: 47 },
    users: { available: true, value: 14280 },
    providers: { available: true, value: 1842 },
    bookings: { available: true, value: 628 },
    bookingValue: { available: true, value: 18400000 },
    commission: { available: true, value: 2700000 },
    refunds: { available: true, value: 141000 },
    disputeBreakdown: { underReview: 8, awaitingResponse: 6, appealed: 3, manualReview: 2 },
    recentAuditActivity: [
      { id: 'a1', actorId: 'Eleanor Vance', action: 'PLATFORM_SETTINGS_UPDATED', targetRef: 'setting:booking', createdAt: new Date().toISOString() },
    ],
    ...overrides,
  }
}

/** Build a mock disputes report response. */
export function disputeReport(overrides = {}) {
  return {
    available: true,
    type: 'disputes',
    rows: [
      { date: '2026-11-20', opened: 5, resolved: 3, underReview: 2, fullRefund: 2, partialRefund: 1, noRefund: 0, avgResolutionHours: 6.0 },
    ],
    totals: { opened: 5, resolved: 3, underReview: 2, fullRefund: 2, partialRefund: 1, noRefund: 0 },
    totalElements: 1,
    totalPages: 1,
    page: 0,
    size: 20,
    ...overrides,
  }
}

/** Build a mock grouped settings response. */
export function settingsResponse() {
  return [
    {
      group: 'booking',
      version: 4,
      updatedBy: 'Eleanor Vance',
      updatedAt: '2026-03-24T10:00:00Z',
      settings: [
        { key: 'min_booking_notice_hours', value: 3, version: 4 },
        { key: 'provider_response_cutoff_hours', value: 1, version: 4 },
        { key: 'default_buffer_minutes', value: 30, version: 4 },
        { key: 'max_reschedules_per_booking', value: 2, version: 4 },
        { key: 'max_series_occurrences', value: 8, version: 4 },
        { key: 'max_series_length_months', value: 3, version: 4 },
        { key: 'provider_confirmation_window_hours', value: 12, version: 4 },
      ],
    },
    {
      group: 'cancellation',
      version: 2,
      updatedBy: 'Eleanor Vance',
      updatedAt: '2026-03-24T10:00:00Z',
      settings: [
        { key: 'refund_full_pct', value: 100, version: 2 },
        { key: 'refund_partial_pct', value: 50, version: 2 },
        { key: 'refund_none_pct', value: 0, version: 2 },
        { key: 'provider_cancellation_refund_pct', value: 100, version: 2 },
        { key: 'repeat_cancellation_threshold', value: 3, version: 2 },
      ],
    },
    {
      group: 'customer',
      version: 1,
      updatedBy: 'Eleanor Vance',
      updatedAt: '2026-03-24T10:00:00Z',
      settings: [
        { key: 'auto_confirm_completion_hours', value: 48, version: 1 },
        { key: 'review_window_days', value: 14, version: 1 },
      ],
    },
    {
      group: 'dispute',
      version: 1,
      updatedBy: 'Eleanor Vance',
      updatedAt: '2026-03-24T10:00:00Z',
      settings: [
        { key: 'dispute_raise_window_hours', value: 72, version: 1 },
        { key: 'no_show_report_delay_hours', value: 2, version: 1 },
        { key: 'dispute_response_period_hours', value: 72, version: 1 },
        { key: 'dispute_appeal_window_hours', value: 48, version: 1 },
      ],
    },
    {
      group: 'penalty',
      version: 1,
      updatedBy: 'Eleanor Vance',
      updatedAt: '2026-03-24T10:00:00Z',
      settings: [{ key: 'provider_penalty_pct', value: 30, version: 1 }],
    },
    {
      group: 'ai_thresholds',
      version: 1,
      settings: [
        { key: 'ai_flag_threshold', value: 0.6, version: 1 },
        { key: 'ai_high_risk_threshold', value: 0.85, version: 1 },
      ],
    },
  ]
}

/** Build mock admin accounts page response. */
export function adminAccountsResponse(overrides = {}) {
  return {
    items: [
      {
        id: 'user-1',
        fullName: 'Eleanor Vance',
        email: 'eleanor.vance@marketplace.local',
        role: 'SUPER_ADMIN',
        status: 'ACTIVE',
        createdAt: '2026-03-24T10:00:00Z',
      },
      {
        id: 'user-2',
        fullName: 'Alex Dawson',
        email: 'alex.dawson@marketplace.local',
        role: 'ADMIN',
        status: 'ACTIVE',
        createdAt: '2026-03-24T10:00:00Z',
      },
      {
        id: 'user-3',
        fullName: 'David Silva',
        email: 'david.silva@marketplace.local',
        role: 'MODERATOR',
        status: 'SUSPENDED',
        createdAt: '2026-03-14T10:00:00Z',
      },
    ],
    total: 3,
    page: 0,
    size: 20,
    totalPages: 1,
    ...overrides,
  }
}

/** Build mock admin accounts summary response. */
export function adminAccountsSummary(overrides = {}) {
  return {
    total: 7,
    byRole: {
      SUPER_ADMIN: 1,
      ADMIN: 1,
      SUPPORT_ADMIN: 2,
      MODERATOR: 2,
      FINANCE_ADMIN: 1,
    },
    byStatus: {
      ACTIVE: 6,
      SUSPENDED: 1,
    },
    ...overrides,
  }
}

/** Build mock audit logs response. */
export function auditLogsResponse(overrides = {}) {
  return {
    items: [
      {
        id: 'log-1',
        actorId: 'Eleanor Vance',
        actorRole: 'SUPER_ADMIN',
        action: 'ADMIN_ACCOUNT_CREATED',
        targetUserId: 'Kasun Fernando',
        targetRole: 'SUPPORT_ADMIN',
        fromStatus: null,
        toStatus: null,
        reason: null,
        outcome: 'SUCCESS',
        createdAt: '2026-03-24T11:00:00Z',
      },
      {
        id: 'log-2',
        actorId: 'Eleanor Vance',
        actorRole: 'SUPER_ADMIN',
        action: 'ADMIN_STATUS_CHANGED',
        targetUserId: 'David Silva',
        targetRole: 'MODERATOR',
        fromStatus: 'ACTIVE',
        toStatus: 'SUSPENDED',
        reason: 'Policy violation',
        outcome: 'SUCCESS',
        createdAt: '2026-03-24T12:00:00Z',
      },
    ],
    total: 248,
    page: 0,
    size: 20,
    totalPages: 13,
    ...overrides,
  }
}
