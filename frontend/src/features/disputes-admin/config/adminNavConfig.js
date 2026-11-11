import { ADMIN_ROLES } from './adminAreas'

/**
 * THE single source for admin sidebar links AND admin routes.
 *
 * To add a page, add ONE entry below. The sidebar link and the route are generated from it.
 *
 *   id          unique id, e.g. 'admin.disputes'
 *   area        the admin area (role) that owns the page: ADMIN_ROLES.*
 *   section     sidebar heading ('Operations', 'Insights', ...); null = top, no heading
 *   group       optional { id, label, icon } to nest the link under an expandable item
 *   label       link text and default page title
 *   path        path inside the area ('' = the area's dashboard), e.g. 'disputes'
 *   icon        icon name from shared/layouts/admin/Icon.jsx (not needed inside a group)
 *   description subtitle on the placeholder page
 *   page        optional lazy(() => import('...')); until set, a placeholder page is shown
 *
 * Who can open it is decided by GET /api/v1/admin/access (canEdit/canView), never here.
 */

const { SUPER_ADMIN, ADMIN, SUPPORT_ADMIN, MODERATOR, FINANCE_ADMIN } = ADMIN_ROLES

const MODERATION_GROUP = Object.freeze({ id: 'moderation', label: 'Moderation', icon: 'star' })

export const ADMIN_NAV_ENTRIES = Object.freeze([
  // ---------- Super Admin ----------
  { id: 'super-admin.dashboard', area: SUPER_ADMIN, section: null, label: 'Dashboard', path: '', icon: 'dashboard', description: 'Overview of the platform' },
  { id: 'super-admin.reports', area: SUPER_ADMIN, section: 'Insights', label: 'Reports', path: 'reports', icon: 'reports', description: 'Platform-wide reports' },
  { id: 'super-admin.platform-settings', area: SUPER_ADMIN, section: 'System', label: 'Platform settings', path: 'platform-settings', icon: 'settings', description: 'Commission, time windows and platform rules' },
  { id: 'super-admin.admin-accounts', area: SUPER_ADMIN, section: 'System', label: 'Admin accounts', path: 'admin-accounts', icon: 'userPlus', description: 'Create and manage admin accounts' },
  { id: 'super-admin.audit-log', area: SUPER_ADMIN, section: 'System', label: 'Audit log', path: 'audit-log', icon: 'fileText', description: 'Record of administrative actions' },

  // ---------- Admin ----------
  { id: 'admin.dashboard', area: ADMIN, section: null, label: 'Dashboard', path: '', icon: 'dashboard', description: 'Overview of admin operations' },
  { id: 'admin.disputes', area: ADMIN, section: 'Operations', label: 'Disputes', path: 'disputes', icon: 'alertCircle', description: 'Dispute console and appeals' },
  { id: 'admin.users', area: ADMIN, section: 'Operations', label: 'Users', path: 'users', icon: 'users', description: 'Customer and provider accounts' },
  { id: 'admin.provider-verification', area: ADMIN, section: 'Operations', label: 'Provider verification', path: 'provider-verification', icon: 'shield', description: 'Review provider verification requests' },
  { id: 'admin.moderation.reported-content', area: ADMIN, section: 'Operations', group: MODERATION_GROUP, label: 'Reported content', path: 'moderation/reported-content', description: 'Reported messages and content' },
  { id: 'admin.moderation.reviews', area: ADMIN, section: 'Operations', group: MODERATION_GROUP, label: 'Review moderation', path: 'moderation/reviews', description: 'Reported reviews' },
  { id: 'admin.overdue-bookings', area: ADMIN, section: 'Operations', label: 'Overdue bookings', path: 'overdue-bookings', icon: 'clock', description: 'Bookings still open 72 hours after the slot ended' },
  { id: 'admin.late-cases', area: ADMIN, section: 'Operations', label: 'Late cases', path: 'late-cases', icon: 'calendar', description: 'Problems reported after the 72-hour window' },
  { id: 'admin.repeat-cancellations', area: ADMIN, section: 'Operations', label: 'Repeat cancellations', path: 'repeat-cancellations', icon: 'xCircle', description: 'Warnings and suspensions for repeat cancellations' },
  { id: 'admin.categories', area: ADMIN, section: 'Management', label: 'Categories', path: 'categories', icon: 'grid', description: 'Service categories and commission' },
  { id: 'admin.announcements', area: ADMIN, section: 'Management', label: 'Announcements', path: 'announcements', icon: 'message', description: 'Platform announcements' },
  { id: 'admin.audit-log', area: ADMIN, section: 'Management', label: 'Audit log', path: 'audit-log', icon: 'fileText', description: 'Record of administrative actions' },
  { id: 'admin.provider-analytics', area: ADMIN, section: 'Insights', label: 'Provider analytics', path: 'provider-analytics', icon: 'barChart', description: 'Provider performance' },
  { id: 'admin.reports', area: ADMIN, section: 'Insights', label: 'Reports', path: 'reports', icon: 'reports', description: 'Operational reports' },

  // ---------- Support Admin ----------
  { id: 'support.dashboard', area: SUPPORT_ADMIN, section: null, label: 'Dashboard', path: '', icon: 'dashboard', description: 'Overview of support cases' },
  { id: 'support.overdue-bookings', area: SUPPORT_ADMIN, section: 'Operations', label: 'Overdue bookings', path: 'overdue-bookings', icon: 'clock', description: 'Bookings still open 72 hours after the slot ended' },
  { id: 'support.late-cases', area: SUPPORT_ADMIN, section: 'Operations', label: 'Late cases', path: 'late-cases', icon: 'calendar', description: 'Problems reported after the 72-hour window' },
  { id: 'support.repeat-cancellations', area: SUPPORT_ADMIN, section: 'Operations', label: 'Repeat cancellations', path: 'repeat-cancellations', icon: 'xCircle', description: 'Repeat cancellation cases' },

  // ---------- Moderator ----------
  { id: 'moderator.dashboard', area: MODERATOR, section: null, label: 'Dashboard', path: '', icon: 'dashboard', description: 'Overview of the moderation queue' },
  { id: 'moderator.moderation.reported-content', area: MODERATOR, section: 'Operations', group: MODERATION_GROUP, label: 'Reported content', path: 'moderation/reported-content', description: 'Reported messages and content' },
  { id: 'moderator.moderation.reviews', area: MODERATOR, section: 'Operations', group: MODERATION_GROUP, label: 'Review moderation', path: 'moderation/reviews', description: 'Reported reviews' },

  // ---------- Finance Admin ----------
  { id: 'finance.dashboard', area: FINANCE_ADMIN, section: null, label: 'Dashboard', path: '', icon: 'dashboard', description: 'Overview of platform finances' },
  { id: 'finance.transactions', area: FINANCE_ADMIN, section: 'Finance', label: 'Transactions', path: 'transactions', icon: 'creditCard', description: 'Payment transactions' },
  { id: 'finance.payout-queue', area: FINANCE_ADMIN, section: 'Finance', label: 'Payout queue', path: 'payout-queue', icon: 'clock', description: 'Pending and on-hold provider payouts' },
  { id: 'finance.refunds', area: FINANCE_ADMIN, section: 'Finance', label: 'Refunds', path: 'refunds', icon: 'refund', description: 'Refund requests and history' },
  { id: 'finance.reports', area: FINANCE_ADMIN, section: 'Insights', label: 'Reports', path: 'reports', icon: 'reports', description: 'Finance reports and reconciliation export' },
])
