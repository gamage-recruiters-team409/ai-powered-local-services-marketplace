/**
 * The ONE place for dispute status display: badge text, colour (tone) and the 3-step stage.
 * Status values come from the backend DisputeStatus enum (proposed, awaiting TL approval).
 */

export const DISPUTE_STATUS = Object.freeze({
  OPEN: 'OPEN',
  AWAITING_RESPONSE: 'AWAITING_RESPONSE',
  UNDER_REVIEW: 'UNDER_REVIEW',
  RESOLVED: 'RESOLVED',
  APPEALED: 'APPEALED',
  RESOLVED_FINAL: 'RESOLVED_FINAL',
  CLOSED: 'CLOSED',
})

export const TOTAL_STAGES = 3

/** tone: 'warning' | 'info' | 'success' | 'neutral' (used for badge and banner colours). */
const STATUS_CONFIG = Object.freeze({
  OPEN: { tone: 'warning', stage: 1, label: 'Open' },
  AWAITING_RESPONSE: { tone: 'warning', stage: 1, label: null },
  UNDER_REVIEW: { tone: 'info', stage: 2, label: 'Under review' },
  RESOLVED: { tone: 'success', stage: 3, label: 'Resolved' },
  APPEALED: { tone: 'info', stage: 3, label: 'Appeal under review' },
  RESOLVED_FINAL: { tone: 'success', stage: 3, label: 'Resolved (final)' },
  CLOSED: { tone: 'neutral', stage: 3, label: 'Closed' },
})

const UNKNOWN = Object.freeze({ tone: 'neutral', stage: null, label: 'Unknown status' })

const ROLE_WORD = Object.freeze({ CUSTOMER: 'customer', PROVIDER: 'provider' })

/** The party who must answer: the one who did not raise the dispute. */
export function responderRoleOf(dispute) {
  return dispute?.raisedByRole === 'PROVIDER' ? 'CUSTOMER' : 'PROVIDER'
}

/**
 * Badge for a status, worded for the viewer.
 * AWAITING_RESPONSE reads "Awaiting your response" for the responder and
 * "Awaiting provider's response" (or customer's) for everyone else.
 */
export function getStatusBadge(status, { viewerIsResponder = false, responderRole = 'PROVIDER' } = {}) {
  const config = STATUS_CONFIG[status] ?? UNKNOWN
  let label = config.label
  if (status === DISPUTE_STATUS.AWAITING_RESPONSE) {
    label = viewerIsResponder
      ? 'Awaiting your response'
      : `Awaiting ${ROLE_WORD[responderRole] ?? 'the other party'}'s response`
  }
  return { label, tone: config.tone, stage: config.stage }
}

export function isAwaitingResponse(status) {
  return status === DISPUTE_STATUS.AWAITING_RESPONSE
}

/** Display names for the backend DisputeCategory values. */
const CATEGORY_LABELS = Object.freeze({
  SERVICE_NOT_DELIVERED: 'Service not delivered',
  POOR_QUALITY: 'Poor quality',
  NO_SHOW: 'No-show',
  PRICING_OR_PAYMENT: 'Pricing or payment',
  PROPERTY_DAMAGE: 'Property damage',
  CONDUCT: 'Conduct',
  OTHER: 'Other',
  CUSTOMER_BEHAVIOUR: 'Customer behaviour',
  NON_PAYMENT: 'Non-payment',
})

/** What a provider can raise from "Raise a dispute" (no-show is reported from the booking, WP08). */
export const PROVIDER_RAISE_CATEGORIES = Object.freeze(['PROPERTY_DAMAGE', 'CUSTOMER_BEHAVIOUR', 'NON_PAYMENT', 'OTHER'])

/** Same limits as the backend (DisputeRules / request validation); the minimums are UI guidance. */
export const DISPUTE_LIMITS = Object.freeze({
  DESCRIPTION_MAX: 2000,
  DESCRIPTION_MIN: 10,
  APPEAL_GROUNDS_MAX: 2000,
  APPEAL_GROUNDS_MIN: 10,
  PAGE_SIZE: 10,
})

/**
 * My Disputes tabs -> backend statuses (GET /disputes/my?status=...). "Open" is everything still being handled;
 * "Final" also covers closed cases.
 */
export const MY_DISPUTE_TABS = Object.freeze([
  { id: 'all', label: 'All', statuses: [] },
  { id: 'open', label: 'Open', statuses: ['OPEN', 'AWAITING_RESPONSE', 'UNDER_REVIEW', 'APPEALED'] },
  { id: 'awaiting', label: 'Awaiting response', statuses: ['AWAITING_RESPONSE'] },
  { id: 'review', label: 'Under review', statuses: ['UNDER_REVIEW', 'APPEALED'] },
  { id: 'resolved', label: 'Resolved', statuses: ['RESOLVED'] },
  { id: 'final', label: 'Final', statuses: ['RESOLVED_FINAL', 'CLOSED'] },
])

/** Neutral payment wording for the parties: never claims that a refund or payout happened. */
export const PAYMENT_NOTE = 'Payment handling for this dispute is being processed.'

export const ACTION_LABELS = Object.freeze({
  REFUND_FULL: 'Full refund to the customer',
  REFUND_PARTIAL: 'Partial refund to the customer',
  RELEASE_TO_PROVIDER: 'Payment released to the provider',
})

export function categoryLabel(category) {
  return CATEGORY_LABELS[category] ?? 'Other'
}

export function roleLabel(role) {
  if (role === 'CUSTOMER') return 'Customer'
  if (role === 'PROVIDER') return 'Provider'
  if (role) return 'Admin'
  return 'System'
}
