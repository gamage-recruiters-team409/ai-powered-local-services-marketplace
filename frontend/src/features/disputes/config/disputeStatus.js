/**
 * The ONE place for dispute status display: badge text, colour (tone) and the 3-step stage.
 * Status values come from the backend DisputeStatus enum (proposed, awaiting TL approval).
 */

export const DISPUTE_STATUS = Object.freeze({
  OPEN: 'OPEN',
  AWAITING_RESPONSE: 'AWAITING_RESPONSE',
  UNDER_REVIEW: 'UNDER_REVIEW',
  RESOLVED: 'RESOLVED',
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
