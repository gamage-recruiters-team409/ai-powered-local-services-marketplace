export const PAGE_SIZE = 20
export const NOTE_MIN = 10
export const NOTE_MAX = 1000
export const SUSPEND_NOTE_MAX = 500
export const SEARCH_MAX = 120

/** Returns the maximum allowed note length for the chosen action (500 for SUSPEND, 1000 for record-only). */
export function noteMaxForAction(action) {
  return action === 'SUSPEND' ? SUSPEND_NOTE_MAX : NOTE_MAX
}

/** Human labels for every backend action. */
export const ACTION_LABEL = Object.freeze({
  REVIEWED: 'Mark reviewed',
  FLAG_CONCERN: 'Flag concern',
  CONTACT_PROVIDER: 'Contact provider',
  CONTACT_CUSTOMER: 'Contact customer',
  ESCALATE: 'Escalate to dispute team',
  MARK_RESOLVED: 'Mark resolved',
  RECOMMEND_SUSPENSION: 'Recommend suspension',
  SUSPEND: 'Suspension',
})

/** Handle actions for overdue bookings and late cases (same for SUPPORT_ADMIN and ADMIN). */
export const CASE_ACTIONS = ['CONTACT_PROVIDER', 'CONTACT_CUSTOMER', 'ESCALATE', 'MARK_RESOLVED', 'FLAG_CONCERN']

/**
 * Take-action options for repeat cancellations, by the area the page is mounted in.
 * SUPPORT_ADMIN recommends; ADMIN suspends through the Admin Users flow. No Warning (cannot notify), no ban, no timed
 * suspension.
 */
export function repeatActionsForArea(area) {
  if (area === 'ADMIN') {
    return ['FLAG_CONCERN', 'SUSPEND']
  }
  return ['FLAG_CONCERN', 'RECOMMEND_SUSPENSION']
}

export function actionOptions(values) {
  return values.map((value) => ({ value, label: ACTION_LABEL[value] ?? value }))
}

/** Case-status chip: { label, variant }. */
export const CASE_STATUS_CHIP = Object.freeze({
  OPEN: { label: 'Open', variant: 'neutral' },
  REVIEWED: { label: 'Reviewed', variant: 'info' },
  CONTACTED: { label: 'Contacted', variant: 'info' },
  FLAGGED: { label: 'Flagged', variant: 'warning' },
  ESCALATED: { label: 'Escalated', variant: 'warning' },
  SUSPENSION_RECOMMENDED: { label: 'Suspension recommended', variant: 'warning' },
  SUSPENDED: { label: 'Suspended', variant: 'danger' },
  RESOLVED: { label: 'Resolved', variant: 'success' },
})

export const URGENCY_CHIP = Object.freeze({
  MEDIUM: { label: 'Medium', variant: 'info' },
  HIGH: { label: 'High', variant: 'warning' },
  CRITICAL: { label: 'Critical', variant: 'danger' },
})

export const DELAY_BUCKET_OPTIONS = [
  { value: '', label: 'All delays' },
  { value: '1', label: '1 day' },
  { value: '2', label: '2 days' },
  { value: '3', label: '3 days' },
  { value: '4+', label: '4+ days' },
]

export const URGENCY_OPTIONS = [
  { value: '', label: 'All urgency' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HIGH', label: 'High' },
  { value: 'CRITICAL', label: 'Critical' },
]

export const ROLE_OPTIONS = [
  { value: '', label: 'All roles' },
  { value: 'CUSTOMER', label: 'Customer' },
  { value: 'PROVIDER', label: 'Provider' },
]

export const ROLE_LABEL = Object.freeze({ CUSTOMER: 'Customer', PROVIDER: 'Provider' })

/** Placeholder copy shown when a section's source is unavailable (no numbers, just the reason). */
export const UNAVAILABLE_FALLBACK = 'This information will appear when booking data is connected.'
