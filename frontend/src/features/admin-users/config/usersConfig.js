import { ADMIN_ROLES, areaPath } from '../../disputes-admin/config/adminAreas'

/** Values match the backend (modules/adminusers) exactly. */
export const ROLE = Object.freeze({ CUSTOMER: 'CUSTOMER', PROVIDER: 'PROVIDER' })
export const USER_STATUS = Object.freeze({
  ACTIVE: 'ACTIVE',
  SUSPENDED: 'SUSPENDED',
  PENDING_VERIFICATION: 'PENDING_VERIFICATION',
  LOCKED: 'LOCKED',
  DEACTIVATED: 'DEACTIVATED',
})

/** Same limits as the backend AdminUsersRules. */
export const LIMITS = Object.freeze({ PAGE_SIZE: 8, LISTINGS_PAGE_SIZE: 5, SEARCH: 100, NOTE_MIN: 10, NOTE_MAX: 500 })

export const ROLE_LABEL = Object.freeze({ CUSTOMER: 'Customer', PROVIDER: 'Provider' })

/** tone: success | danger | warning | neutral */
export const STATUS_META = Object.freeze({
  ACTIVE: { label: 'Active', tone: 'success' },
  SUSPENDED: { label: 'Suspended', tone: 'danger' },
  PENDING_VERIFICATION: { label: 'Pending verification', tone: 'neutral' },
  LOCKED: { label: 'Locked', tone: 'warning' },
  DEACTIVATED: { label: 'Deactivated', tone: 'neutral' },
})

export const ROLE_OPTIONS = Object.freeze([
  { value: '', label: 'All Roles' },
  { value: ROLE.CUSTOMER, label: 'Customers' },
  { value: ROLE.PROVIDER, label: 'Providers' },
])

/** The backend list filter accepts only these three statuses. */
export const STATUS_OPTIONS = Object.freeze([
  { value: '', label: 'All Statuses' },
  { value: USER_STATUS.ACTIVE, label: 'Active' },
  { value: USER_STATUS.SUSPENDED, label: 'Suspended' },
  { value: USER_STATUS.PENDING_VERIFICATION, label: 'Pending verification' },
])

/** Suspension reason codes (backend SuspensionReason). OTHER requires a note. */
export const SUSPENSION_REASONS = Object.freeze([
  { value: 'REPEATED_CANCELLATIONS', label: 'Repeated cancellations' },
  { value: 'POLICY_VIOLATION', label: 'Policy violation' },
  { value: 'SUSPECTED_FRAUD', label: 'Suspected fraud' },
  { value: 'ABUSIVE_BEHAVIOUR', label: 'Abusive behaviour' },
  { value: 'OFF_PLATFORM_CONTACT', label: 'Off-platform contact' },
  { value: 'OTHER', label: 'Other' },
])

export function reasonLabel(code) {
  return SUSPENSION_REASONS.find((r) => r.value === code)?.label ?? null
}

/** Listing count chips on the account page, in this order (Rejected is shown only when there are any). */
export const LISTING_COUNT_CHIPS = Object.freeze([
  { key: 'active', status: 'ACTIVE' },
  { key: 'paused', status: 'PAUSED' },
  { key: 'draft', status: 'DRAFT' },
  { key: 'flagged', status: 'FLAGGED' },
  { key: 'archived', status: 'ARCHIVED' },
])

export const LISTING_STATUS_META = Object.freeze({
  FLAGGED: { label: 'Flagged', tone: 'danger' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  PAUSED: { label: 'Paused', tone: 'neutral' },
  DRAFT: { label: 'Draft', tone: 'neutral' },
  ACTIVE: { label: 'Active', tone: 'success' },
  ARCHIVED: { label: 'Archived', tone: 'neutral' },
})

/** Booking statuses a queued booking can have, worded for the admin. */
export const BOOKING_STATUS_META = Object.freeze({
  PENDING_PROVIDER: { label: 'Awaiting provider', tone: 'warning' },
  AWAITING_PAYMENT: { label: 'Awaiting payment', tone: 'info' },
  CONFIRMED: { label: 'Confirmed', tone: 'success' },
  IN_PROGRESS: { label: 'In progress', tone: 'info' },
})

export const ADMIN_ACTION_META = Object.freeze({
  SUSPENDED: { label: 'Suspended', tone: 'danger' },
  REACTIVATED: { label: 'Reactivated', tone: 'success' },
  STATUS_CHANGED: { label: 'Status changed', tone: 'neutral' },
})

export const USERS_PATH = areaPath(ADMIN_ROLES.ADMIN, 'users')

export const userPaths = Object.freeze({
  list: (search = '') => (search ? `${USERS_PATH}?${search}` : USERS_PATH),
  detail: (id) => `${USERS_PATH}/${encodeURIComponent(id)}`,
})

/** Short display id: the backend has no reference number, so the last 4 characters of the id. */
export function shortUserId(id, role) {
  if (!id) return ''
  const prefix = role === ROLE.PROVIDER ? 'PRO' : 'USR'
  return `#${prefix}-${String(id).slice(-4).toUpperCase()}`
}

export function shortId(id) {
  return id ? `#${String(id).slice(-6).toUpperCase()}` : ''
}
