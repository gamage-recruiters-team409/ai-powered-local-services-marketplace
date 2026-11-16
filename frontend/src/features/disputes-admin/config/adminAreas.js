/**
 * Admin areas, one per admin role. The role values match the backend Role enum.
 * Labels, descriptions, role-card details (category, icon, tags) and URL slugs live here only.
 */
export const ADMIN_ROLES = Object.freeze({
  SUPER_ADMIN: 'SUPER_ADMIN',
  ADMIN: 'ADMIN',
  SUPPORT_ADMIN: 'SUPPORT_ADMIN',
  MODERATOR: 'MODERATOR',
  FINANCE_ADMIN: 'FINANCE_ADMIN',
})

export const ADMIN_AREAS = Object.freeze({
  [ADMIN_ROLES.SUPER_ADMIN]: {
    slug: 'super-admin',
    label: 'Super Admin',
    description: 'Platform dashboard, settings, admin accounts and audit log.',
    category: 'Platform governance',
    icon: 'shield',
    tags: ['Dashboard', 'Settings', 'Admin accounts', 'Audit log'],
  },
  [ADMIN_ROLES.ADMIN]: {
    slug: 'admin',
    label: 'Admin',
    description: 'Disputes, users, provider verification, categories and announcements.',
    category: 'Full operations',
    icon: 'userCircle',
    tags: ['Disputes', 'Users', 'Verification', 'Categories'],
  },
  [ADMIN_ROLES.SUPPORT_ADMIN]: {
    slug: 'support',
    label: 'Support Admin',
    description: 'Overdue bookings, late cases and repeat cancellations.',
    category: 'Operations & fulfilment',
    icon: 'clock',
    tags: ['Overdue bookings', 'Late cases', 'Cancellations'],
  },
  [ADMIN_ROLES.MODERATOR]: {
    slug: 'moderator',
    label: 'Moderator',
    description: 'Reported content and review moderation.',
    category: 'Trust & safety',
    icon: 'flag',
    tags: ['Reported content', 'Reviews'],
  },
  [ADMIN_ROLES.FINANCE_ADMIN]: {
    slug: 'finance',
    label: 'Finance Admin',
    description: 'Transactions, payout queue, refunds and reports.',
    category: 'Financial operations',
    icon: 'landmark',
    tags: ['Transactions', 'Payouts', 'Refunds', 'Reports'],
  },
})

/** Display order for role cards and lists. */
export const AREA_ORDER = Object.freeze([
  ADMIN_ROLES.SUPER_ADMIN,
  ADMIN_ROLES.ADMIN,
  ADMIN_ROLES.SUPPORT_ADMIN,
  ADMIN_ROLES.MODERATOR,
  ADMIN_ROLES.FINANCE_ADMIN,
])

export const ADMIN_BASE_PATH = '/admin'

export const ADMIN_PATHS = Object.freeze({
  root: ADMIN_BASE_PATH,
  selectRole: `${ADMIN_BASE_PATH}/select-role`,
})

/** areaPath('MODERATOR', 'moderation/reviews') -> '/admin/moderator/moderation/reviews' */
export function areaPath(area, subPath = '') {
  const base = `${ADMIN_BASE_PATH}/${ADMIN_AREAS[area].slug}`
  return subPath ? `${base}/${subPath}` : base
}

/** '/admin/finance/refunds' -> 'FINANCE_ADMIN'; null when the path is not inside an area. */
export function areaFromPathname(pathname) {
  if (!pathname.startsWith(`${ADMIN_BASE_PATH}/`)) {
    return null
  }
  const slug = pathname.slice(ADMIN_BASE_PATH.length + 1).split('/')[0]
  return AREA_ORDER.find((area) => ADMIN_AREAS[area].slug === slug) ?? null
}

export function roleLabel(role) {
  return ADMIN_AREAS[role]?.label ?? role ?? ''
}
