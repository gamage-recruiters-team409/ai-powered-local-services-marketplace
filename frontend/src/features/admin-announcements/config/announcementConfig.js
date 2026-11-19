import { ADMIN_ROLES, areaPath } from '../../disputes-admin/config/adminAreas'

/** Values match the backend enums exactly. */
export const STATUS = Object.freeze({ DRAFT: 'DRAFT', SCHEDULED: 'SCHEDULED', PUBLISHED: 'PUBLISHED' })
export const AUDIENCE = Object.freeze({ ALL_USERS: 'ALL_USERS', CUSTOMERS: 'CUSTOMERS', PROVIDERS: 'PROVIDERS' })
export const INTENT = Object.freeze({ DRAFT: 'DRAFT', PUBLISH: 'PUBLISH' })

/** Same limits as the backend AnnouncementRules. */
export const LIMITS = Object.freeze({ TITLE: 80, MESSAGE: 5000, SEARCH: 80, PAGE_SIZE: 5 })

export const PIN_HOURS = 48

export const STATUS_META = Object.freeze({
  [STATUS.PUBLISHED]: { label: 'Published', tone: 'success' },
  [STATUS.SCHEDULED]: { label: 'Scheduled', tone: 'warning' },
  [STATUS.DRAFT]: { label: 'Draft', tone: 'neutral' },
})

export const AUDIENCE_META = Object.freeze({
  [AUDIENCE.ALL_USERS]: { label: 'All users', formLabel: 'All platform users (Everyone)', icon: 'globe' },
  [AUDIENCE.CUSTOMERS]: { label: 'Customers', formLabel: 'Customers', icon: 'user' },
  [AUDIENCE.PROVIDERS]: { label: 'Providers', formLabel: 'Providers', icon: 'briefcase' },
})

export const AUDIENCE_OPTIONS = Object.values(AUDIENCE).map((value) => ({
  value,
  label: AUDIENCE_META[value].formLabel,
}))

/** List tabs: URL value -> backend status filter. */
export const TABS = Object.freeze([
  { id: 'all', label: 'All', status: null, countKey: 'all' },
  { id: 'published', label: 'Published', status: STATUS.PUBLISHED, countKey: 'published' },
  { id: 'scheduled', label: 'Scheduled', status: STATUS.SCHEDULED, countKey: 'scheduled' },
  { id: 'drafts', label: 'Drafts', status: STATUS.DRAFT, countKey: 'drafts' },
])

export function tabForStatus(status) {
  return TABS.find((tab) => tab.status === status)?.id ?? 'all'
}

export const ANNOUNCEMENTS_PATH = areaPath(ADMIN_ROLES.ADMIN, 'announcements')

export const announcementPaths = Object.freeze({
  list: (tab) => (tab && tab !== 'all' ? `${ANNOUNCEMENTS_PATH}?tab=${tab}` : ANNOUNCEMENTS_PATH),
  create: () => `${ANNOUNCEMENTS_PATH}/new`,
  view: (id) => `${ANNOUNCEMENTS_PATH}/${encodeURIComponent(id)}`,
  edit: (id) => `${ANNOUNCEMENTS_PATH}/${encodeURIComponent(id)}/edit`,
})

/** "In-app banner + push notification" from the delivery flags. */
export function deliveryLine({ sendPush, pinToHeader }) {
  const parts = []
  if (pinToHeader) parts.push('In-app banner')
  if (sendPush) parts.push('push notification')
  if (parts.length === 0) return 'Announcement only (no push, not pinned)'
  const text = parts.join(' + ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** Short reference shown on the view page: the backend has no reference number, so the id's last 6 characters. */
export function shortReference(id) {
  return id ? `#${String(id).slice(-6).toUpperCase()}` : ''
}
