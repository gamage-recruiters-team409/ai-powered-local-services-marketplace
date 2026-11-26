/**
 * Admin dispute screens: routes, labels and plain-word texts. Values mirror the backend dispute module
 * (DisputeStatus, ResolutionAction, SettlementState, DisputeRules).
 */

export const ADMIN_DISPUTES_BASE = '/admin/admin/disputes'

export function casePath(id) {
  return `${ADMIN_DISPUTES_BASE}/${encodeURIComponent(id)}`
}

export function appealPath(id) {
  return `${casePath(id)}/appeal`
}

export const PAGE_SIZE = 10

export const LIMITS = Object.freeze({
  NOTE_MAX: 1000,
  PARTIAL_MIN: 1,
  PARTIAL_MAX: 99,
  PENALTY_PERCENT: 30,
  SEARCH_MAX: 64,
})

/** tone: green | amber | blue | grey | red (chip colours). */
export const STATUS = Object.freeze({
  OPEN: { label: 'Open', tone: 'amber' },
  AWAITING_RESPONSE: { label: 'Awaiting response', tone: 'amber' },
  UNDER_REVIEW: { label: 'Under review', tone: 'blue' },
  RESOLVED: { label: 'Resolved', tone: 'green' },
  APPEALED: { label: 'Appealed', tone: 'blue' },
  RESOLVED_FINAL: { label: 'Final', tone: 'green' },
  CLOSED: { label: 'Closed', tone: 'grey' },
})

export const STATUS_FILTERS = Object.freeze(Object.entries(STATUS).map(([value, { label }]) => ({ value, label })))

export const CATEGORY_FILTERS = Object.freeze([
  { value: 'SERVICE_NOT_DELIVERED', label: 'Service not delivered' },
  { value: 'POOR_QUALITY', label: 'Poor quality' },
  { value: 'NO_SHOW', label: 'No-show' },
  { value: 'PRICING_OR_PAYMENT', label: 'Pricing or payment' },
  { value: 'PROPERTY_DAMAGE', label: 'Property damage' },
  { value: 'CONDUCT', label: 'Conduct' },
  { value: 'CUSTOMER_BEHAVIOUR', label: 'Customer behaviour' },
  { value: 'NON_PAYMENT', label: 'Non-payment' },
  { value: 'OTHER', label: 'Other' },
])

export function categoryName(value) {
  return CATEGORY_FILTERS.find((item) => item.value === value)?.label ?? 'Other'
}

export const SORT_OPTIONS = Object.freeze([
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'responseDueAt,asc', label: 'Response deadline (soonest)' },
  { value: 'appealDeadline,asc', label: 'Appeal deadline (soonest)' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
])

export const ACTIONS = Object.freeze({
  REFUND_FULL: 'Refund full',
  REFUND_PARTIAL: 'Refund partial',
  RELEASE_TO_PROVIDER: 'Release to provider',
})

export function decisionText(decision) {
  if (!decision) return ''
  const base = ACTIONS[decision.action] ?? decision.action
  const parts = [decision.action === 'REFUND_PARTIAL' ? `${base} (${decision.customerRefundPercent}% to the customer)` : base]
  if (decision.providerPenaltyApplied) {
    parts.push(`${decision.providerPenaltyPercent || LIMITS.PENALTY_PERCENT}% provider penalty`)
  }
  return parts.join(' + ')
}

export const REFUND_NOTE = 'Refunds are recorded and sent to manual review until finance integration is complete.'

/** Settlement states in plain words. */
export const SETTLEMENT_STATE = Object.freeze({
  PENDING: { label: 'Pending', tone: 'grey', text: 'Not attempted yet.' },
  NOT_APPLIED: { label: 'Waiting for integration', tone: 'amber', text: 'Nothing was applied yet: the integration is not connected.' },
  APPLIED: { label: 'Applied', tone: 'green', text: 'Confirmed as carried out.' },
  FAILED: { label: 'Failed, will retry', tone: 'red', text: 'The last attempt failed. It is retried automatically.' },
  MANUAL_REVIEW: { label: 'Manual review', tone: 'amber', text: 'Needs an admin to handle it manually.' },
  REJECTED: { label: 'Rejected', tone: 'red', text: 'Finance permanently rejected the settlement.' },
})

const REASONS = Object.freeze({
  REFUND_PENDING_INTEGRATION: REFUND_NOTE,
  RETRY_LIMIT_REACHED: 'Automatic retries stopped after repeated failures.',
  WP07_NOT_INTEGRATED: 'The finance integration is not connected yet.',
  WP05_NOT_INTEGRATED: 'The booking integration is not connected yet.',
})

/** A settlement / manual review reason in plain words (SETTLEMENT_REJECTED:<code> keeps its code). */
export function reasonText(reason) {
  if (!reason) return null
  if (reason.startsWith('SETTLEMENT_REJECTED:')) {
    return `Finance rejected the settlement (code ${reason.slice('SETTLEMENT_REJECTED:'.length)}).`
  }
  return REASONS[reason] ?? reason
}

/** Why the signed-in admin cannot act (backend `actions.reasonIfNot`). */
export const ACTION_BLOCKED = Object.freeze({
  VIEW_ONLY: 'View only: Super Admin can review disputes but decisions are made by admins.',
  NOT_READY: 'This dispute can be decided once the response window has closed.',
  ALREADY_DECIDED: 'A decision has already been made for this dispute.',
  SAME_ADMIN_AS_DECISION: 'A different admin must review this appeal.',
})
