import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { PageError } from '../../../shared/components/feedback'
import { Button, DataTable, Modal } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { areaPath } from '../../disputes-admin/config/adminAreas'
import { downloadBlob } from '../../support-operations/utils/download'
import {
  listAdminAccounts,
  getAdminAccountsSummary,
  createAdminAccount,
  changeAdminStatus,
  changeAdminRole,
  isRequestCancelled,
} from '../services/superAdminService'
import styles from '../superAdmin.module.css'

const ROLE_OPTIONS = [
  { value: '', label: 'Role: All Roles' },
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
  { value: 'ADMIN', label: 'Admin' },
  { value: 'SUPPORT_ADMIN', label: 'Support Admin' },
  { value: 'MODERATOR', label: 'Moderator' },
  { value: 'FINANCE_ADMIN', label: 'Finance Admin' },
]

const CREATABLE_ROLES = [
  { value: 'ADMIN', label: 'Admin', scope: 'Full operations, dispute resolution, user management, and operational reporting' },
  { value: 'SUPPORT_ADMIN', label: 'Support Admin', scope: 'Customer support, overdue bookings, late cases, and repeat cancellations' },
  { value: 'MODERATOR', label: 'Moderator', scope: 'Review moderation and reported user-generated content' },
  { value: 'FINANCE_ADMIN', label: 'Finance Admin', scope: 'Payout queue approvals, financial transactions, refunds, and reconciliation' },
]

const STATUS_OPTIONS = [
  { value: '', label: 'Status: All Statuses' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
]

const numberFmt = new Intl.NumberFormat('en-US')
function fmt(v) {
  if (v === null || v === undefined) return '—'
  return numberFmt.format(Number(v))
}

function getInitials(name) {
  if (!name) return '?'
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

function getRoleLabel(role) {
  switch (role) {
    case 'SUPER_ADMIN': return 'Super Admin'
    case 'ADMIN': return 'Admin'
    case 'SUPPORT_ADMIN': return 'Support Admin'
    case 'MODERATOR': return 'Moderator'
    case 'FINANCE_ADMIN': return 'Finance Admin'
    default: return role
  }
}

function getRoleChipClass(role) {
  switch (role) {
    case 'SUPER_ADMIN': return `${styles.dotChip} ${styles.dotSuperAdmin}`
    case 'ADMIN': return `${styles.dotChip} ${styles.dotAdmin}`
    case 'SUPPORT_ADMIN': return `${styles.dotChip} ${styles.dotSupportAdmin}`
    case 'MODERATOR': return `${styles.dotChip} ${styles.dotModerator}`
    case 'FINANCE_ADMIN': return `${styles.dotChip} ${styles.dotFinanceAdmin}`
    default: return `${styles.dotChip} ${styles.dotAdmin}`
  }
}

function sanitizeCsv(val) {
  if (val === null || val === undefined) return '""'
  let str = String(val).replace(/"/g, '""')
  if (/^[=+\-@\t\r]/.test(str)) {
    str = `'${str}`
  }
  return `"${str}"`
}

export default function AdminAccountsPage() {
  const [params, setParams] = useSearchParams()

  const q = params.get('q') || ''
  const role = params.get('role') || ''
  const status = params.get('status') || ''
  const page = parseInt(params.get('page') || '0', 10)

  // Local search input
  const [prevQ, setPrevQ] = useState(q)
  const [searchInput, setSearchInput] = useState(q)
  if (prevQ !== q) {
    setPrevQ(q)
    setSearchInput(q)
  }

  // Summary state
  const [summary, setSummary] = useState(null)

  // Table state
  const [result, setResult] = useState({ loading: true, data: null, error: null })
  const [reloadKey, setReloadKey] = useState(0)

  // Modals state
  const [createOpen, setCreateOpen] = useState(false)
  const [roleModalUser, setRoleModalUser] = useState(null)
  const [statusModalUser, setStatusModalUser] = useState(null)

  // Create form
  const [createForm, setCreateForm] = useState({ fullName: '', email: '', temporaryPassword: '', role: 'ADMIN' })
  const [createError, setCreateError] = useState(null)
  const [creating, setCreating] = useState(false)

  // Role form
  const [targetRole, setTargetRole] = useState('ADMIN')
  const [roleReason, setRoleReason] = useState('')
  const [roleError, setRoleError] = useState(null)
  const [roleSaving, setRoleSaving] = useState(false)

  // Status form
  const [statusReason, setStatusReason] = useState('')
  const [statusError, setStatusError] = useState(null)
  const [statusSaving, setStatusSaving] = useState(false)

  const updateParams = useCallback(
    (updates) => {
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        Object.entries(updates).forEach(([k, v]) => {
          if (v === null || v === undefined || v === '') next.delete(k)
          else next.set(k, String(v))
        })
        return next
      })
    },
    [setParams],
  )

  // Fetch summary
  useEffect(() => {
    const controller = new AbortController()
    getAdminAccountsSummary(controller.signal)
      .then(setSummary)
      .catch(() => {})
    return () => controller.abort()
  }, [reloadKey])

  // Fetch accounts list
  useEffect(() => {
    const controller = new AbortController()
    listAdminAccounts({ role, status, q, page, size: 20 }, controller.signal)
      .then((data) => setResult({ loading: false, data, error: null }))
      .catch((err) => {
        if (!isRequestCancelled(err)) {
          const msg = err?.response?.data?.message || err?.message || 'Failed to load accounts.'
          setResult({ loading: false, data: null, error: { status: err?.response?.status ?? 0, message: msg } })
        }
      })
    return () => controller.abort()
  }, [role, status, q, page, reloadKey])

  const handleSearchSubmit = (e) => {
    e.preventDefault()
    updateParams({ q: searchInput, page: 0 })
  }

  // Export CSV
  const handleExportCsv = useCallback(async () => {
    try {
      const data = await listAdminAccounts({ role, status, q, page: 0, size: 100 })
      const rows = data?.items || []
      const headers = ['Full Name', 'Email', 'Role', 'Status', 'Created At']
      const csvLines = [
        headers.map(sanitizeCsv).join(','),
        ...rows.map((u) => [
          sanitizeCsv(u.fullName),
          sanitizeCsv(u.email),
          sanitizeCsv(u.role),
          sanitizeCsv(u.status),
          sanitizeCsv(u.createdAt ? new Date(u.createdAt).toISOString() : ''),
        ].join(',')),
      ]
      const csvBlob = new Blob(['\uFEFF' + csvLines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
      downloadBlob(csvBlob, `admin-accounts-${new Date().toISOString().slice(0, 10)}.csv`)
    } catch (err) {
      alert(err?.response?.data?.message || 'Failed to export CSV.')
    }
  }, [role, status, q])

  // Create admin account
  const handleCreateSubmit = async (e) => {
    e.preventDefault()
    setCreateError(null)
    setCreating(true)
    try {
      await createAdminAccount(createForm)
      setCreateOpen(false)
      setCreateForm({ fullName: '', email: '', temporaryPassword: '', role: 'ADMIN' })
      setReloadKey((k) => k + 1)
    } catch (err) {
      setCreateError(err?.response?.data?.message || 'Failed to create admin account.')
    } finally {
      setCreating(false)
    }
  }

  // Change role submit
  const handleRoleSubmit = async (e) => {
    e.preventDefault()
    setRoleError(null)
    setRoleSaving(true)
    try {
      await changeAdminRole(roleModalUser.id, { role: targetRole, reason: roleReason })
      setRoleModalUser(null)
      setReloadKey((k) => k + 1)
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Role modification is not available or failed.'
      setRoleError(msg)
    } finally {
      setRoleSaving(false)
    }
  }

  // Change status submit
  const handleStatusSubmit = async (e) => {
    e.preventDefault()
    setStatusError(null)
    if (!statusReason || statusReason.trim().length < 10) {
      setStatusError('Reason must be between 10 and 500 characters.')
      return
    }
    setStatusSaving(true)
    const newStatus = statusModalUser.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
    try {
      await changeAdminStatus(statusModalUser.id, { status: newStatus, reason: statusReason.trim() })
      setStatusModalUser(null)
      setStatusReason('')
      setReloadKey((k) => k + 1)
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Status change failed.'
      setStatusError(msg)
    } finally {
      setStatusSaving(false)
    }
  }

  const columns = useMemo(
    () => [
      {
        key: 'admin',
        header: 'NAME & EMAIL',
        render: (_, r) => (
          <div className={styles.tableUserRow}>
            <span className={r.role === 'SUPER_ADMIN' ? styles.avatarEV : styles.avatarGeneric}>
              {getInitials(r.fullName)}
            </span>
            <div className={styles.userCell}>
              <span className={styles.userName}>{r.fullName || '—'}</span>
              <span className={styles.userEmail}>{r.email || '—'}</span>
            </div>
          </div>
        ),
      },
      {
        key: 'role',
        header: 'ROLE',
        render: (v) => <span className={getRoleChipClass(v)}>{getRoleLabel(v)}</span>,
      },
      {
        key: 'status',
        header: 'STATUS',
        render: (v) => (
          <span className={`${styles.dotChip} ${v === 'ACTIVE' ? styles.dotActive : styles.dotSuspended}`}>
            {v === 'ACTIVE' ? 'Active' : 'Suspended'}
          </span>
        ),
      },
      {
        key: 'lastActive',
        header: 'LAST ACTIVE',
        render: () => <span className={styles.subtle}>—</span>,
      },
      {
        key: 'actions',
        header: 'ACTIONS',
        render: (_, r) => (
          <div style={{ display: 'flex', gap: 8 }}>
            <Button
              variant="outline"
              size="small"
              onClick={() => {
                setRoleModalUser(r)
                setTargetRole(r.role === 'SUPER_ADMIN' ? 'ADMIN' : r.role)
                setRoleError(null)
              }}
            >
              Change role
            </Button>
            <Button
              variant={r.status === 'ACTIVE' ? 'danger' : 'outline'}
              size="small"
              onClick={() => {
                setStatusModalUser(r)
                setStatusReason('')
                setStatusError(null)
              }}
            >
              {r.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}
            </Button>
          </div>
        ),
      },
    ],
    [],
  )

  const elevatedCount = summary?.byRole
    ? (summary.byRole.SUPER_ADMIN || 0) + (summary.byRole.ADMIN || 0)
    : undefined

  const suspendedCount = summary?.byStatus
    ? summary.byStatus.SUSPENDED || 0
    : undefined

  return (
    <AdminPage
      breadcrumbs={[{ label: 'Administration' }, { label: 'System' }, { label: 'Admin accounts' }]}
      title="Admin accounts"
      subtitle="Manage administrative operators, access scopes, and privilege delegations across the marketplace."
      actions={
        <Button variant="primary" onClick={() => setCreateOpen(true)} icon={<Icon name="userPlus" size={15} />}>
          Create admin account
        </Button>
      }
      plain
    >
      {/* Summary cards */}
      <div className={styles.summaryGrid}>
        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>TOTAL ADMINS</p>
            <span className={styles.summaryIcon}><Icon name="users" size={18} /></span>
          </div>
          <p className={styles.summaryValue}>{summary ? fmt(summary.total) : '—'}</p>
          <p className={styles.summaryFoot}>All roles assigned</p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>ACTIVE SESSIONS</p>
            <span className={styles.summaryIcon}><Icon name="clock" size={18} /></span>
          </div>
          <p className={styles.summaryFoot}>Not available yet — requires session tracking</p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>ELEVATED ROLES</p>
            <span className={styles.summaryIcon}><Icon name="shield" size={18} /></span>
          </div>
          <p className={styles.summaryValue}>{elevatedCount !== undefined ? fmt(elevatedCount) : '—'}</p>
          <p className={styles.summaryFoot}>
            {summary?.byRole?.SUPER_ADMIN || 0} Super, {summary?.byRole?.ADMIN || 0} Platform Admin
          </p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>FLAGGED / SUSPENDED</p>
            <span className={`${styles.summaryIcon} ${styles.summaryIcon_danger}`}><Icon name="xCircle" size={18} /></span>
          </div>
          <p className={`${styles.summaryValue} ${styles.summaryValue_danger}`}>{suspendedCount !== undefined ? fmt(suspendedCount) : '—'}</p>
          <p className={styles.summaryFoot}>
            {suspendedCount !== undefined ? `${suspendedCount} Account suspended` : '—'}
          </p>
        </article>
      </div>

      {/* Filter and search bar */}
      <div className={styles.filterBar}>
        <form onSubmit={handleSearchSubmit} style={{ display: 'flex', flex: '1 1 240px', gap: 8 }}>
          <input
            className={styles.searchInput}
            type="search"
            placeholder="Search by name, email, or role..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            aria-label="Search admin accounts"
          />
          <Button variant="outline" type="submit">Search</Button>
        </form>

        <select
          className={styles.filterSelect}
          value={role}
          onChange={(e) => updateParams({ role: e.target.value, page: 0 })}
          aria-label="Filter by role"
        >
          {ROLE_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>

        <select
          className={styles.filterSelect}
          value={status}
          onChange={(e) => updateParams({ status: e.target.value, page: 0 })}
          aria-label="Filter by status"
        >
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>

        <span className={styles.subtle} style={{ whiteSpace: 'nowrap' }}>
          Showing {result.data?.items?.length || 0} admin accounts
        </span>

        <Button variant="outline" onClick={handleExportCsv} icon={<Icon name="fileText" size={15} />}>
          Export CSV
        </Button>
      </div>

      {/* Accounts Table */}
      {result.error ? (
        <section className={styles.card}>
          <PageError
            statusCode={result.error.status}
            title="Accounts could not be loaded"
            message={result.error.message}
            onRetry={() => setReloadKey((k) => k + 1)}
          />
        </section>
      ) : (
        <section className={styles.card}>
          <DataTable
            columns={columns}
            data={result.data?.items || []}
            loading={result.loading}
            emptyMessage="No admin accounts match the current filters."
          />

          {result.data && (
            <div className={styles.paginationRow}>
              <p className={styles.subtle}>
                Showing {result.data.items?.length > 0 ? page * result.data.size + 1 : 0} to{' '}
                {Math.min((page + 1) * result.data.size, result.data.total)} of {fmt(result.data.total)} results
              </p>
              {result.data.totalPages > 1 && (
                <div style={{ display: 'flex', gap: 6 }}>
                  <Button
                    variant="outline"
                    size="small"
                    disabled={page === 0}
                    onClick={() => updateParams({ page: page - 1 })}
                  >
                    Previous
                  </Button>
                  <Button
                    variant="outline"
                    size="small"
                    disabled={page >= result.data.totalPages - 1}
                    onClick={() => updateParams({ page: page + 1 })}
                  >
                    Next
                  </Button>
                </div>
              )}
            </div>
          )}
        </section>
      )}

      {/* Bottom Privileged Access Policy Bar */}
      <div className={styles.bottomPolicyBar}>
        <div className={styles.bottomPolicyText}>
          <Icon name="shield" size={18} />
          <div>
            <strong>Privileged Access Policy Active</strong>
            <span style={{ marginLeft: 6, color: 'var(--adm-color-text-muted)' }}>
              Role assignment actions trigger audit log entries and require two-factor confirmation for Super Admin elevation.
            </span>
          </div>
        </div>
        <Link to={areaPath('SUPER_ADMIN', 'audit-log')} className={styles.bottomPolicyLink}>
          View Audit Log →
        </Link>
      </div>

      {/* Create Admin Modal */}
      {createOpen && (
        <Modal isOpen={createOpen} title="Create admin account" onClose={() => setCreateOpen(false)}>
          <form onSubmit={handleCreateSubmit}>
            {createError && <div className={styles.formError} role="alert">{createError}</div>}

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="create-name">Full name</label>
              <input
                id="create-name"
                className={styles.formInput}
                type="text"
                required
                value={createForm.fullName}
                onChange={(e) => setCreateForm({ ...createForm, fullName: e.target.value })}
              />
            </div>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="create-email">Email address</label>
              <input
                id="create-email"
                className={styles.formInput}
                type="email"
                required
                value={createForm.email}
                onChange={(e) => setCreateForm({ ...createForm, email: e.target.value })}
              />
            </div>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="create-password">Temporary password</label>
              <input
                id="create-password"
                className={styles.formInput}
                type="password"
                required
                value={createForm.temporaryPassword}
                onChange={(e) => setCreateForm({ ...createForm, temporaryPassword: e.target.value })}
              />
              <p className={styles.formHelp}>8–64 characters with at least one uppercase, lowercase, and digit.</p>
            </div>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="create-role">Role</label>
              <select
                id="create-role"
                className={styles.formSelect}
                value={createForm.role}
                onChange={(e) => setCreateForm({ ...createForm, role: e.target.value })}
              >
                {CREATABLE_ROLES.map((r) => (
                  <option key={r.value} value={r.value}>{r.label}</option>
                ))}
              </select>
            </div>

            <div className={styles.roleScopeBox}>
              <strong>Role scope</strong>
              <p>{CREATABLE_ROLES.find((r) => r.value === createForm.role)?.scope}</p>
            </div>

            <p className={styles.subtle}>
              Password change is enforced on first sign-in. Temporary password is never logged or stored in plaintext.
            </p>

            <div className={styles.modalActions}>
              <Button variant="outline" type="button" onClick={() => setCreateOpen(false)}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" loading={creating}>
                Create account
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Change Role Modal */}
      {roleModalUser && (
        <Modal isOpen={!!roleModalUser} title="Change admin role" onClose={() => setRoleModalUser(null)}>
          <form onSubmit={handleRoleSubmit}>
            {roleError && <div className={styles.formError} role="alert">{roleError}</div>}

            <p style={{ margin: '0 0 12px' }}>
              Modifying role for <strong>{roleModalUser.fullName}</strong> ({roleModalUser.email}).
            </p>

            <div className={styles.formGroup}>
              <label className={styles.formLabel}>Current role</label>
              <div><span className={getRoleChipClass(roleModalUser.role)}>{getRoleLabel(roleModalUser.role)}</span></div>
            </div>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="new-role">New role</label>
              <select
                id="new-role"
                className={styles.formSelect}
                value={targetRole}
                onChange={(e) => setTargetRole(e.target.value)}
              >
                {CREATABLE_ROLES.map((r) => (
                  <option key={r.value} value={r.value}>{r.label}</option>
                ))}
              </select>
            </div>

            <div className={styles.roleScopeBox}>
              <strong>New role scope</strong>
              <p>{CREATABLE_ROLES.find((r) => r.value === targetRole)?.scope}</p>
            </div>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="role-reason">Reason (optional)</label>
              <input
                id="role-reason"
                className={styles.formInput}
                type="text"
                placeholder="Reason for role change..."
                value={roleReason}
                onChange={(e) => setRoleReason(e.target.value)}
              />
            </div>

            <p className={styles.subtle}>
              Changes take effect on the admin&apos;s next session.
            </p>

            <div className={styles.modalActions}>
              <Button variant="outline" type="button" onClick={() => setRoleModalUser(null)}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" loading={roleSaving}>
                Change role
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Change Status Modal */}
      {statusModalUser && (
        <Modal
          isOpen={!!statusModalUser}
          title={statusModalUser.status === 'ACTIVE' ? 'Suspend admin account' : 'Reactivate admin account'}
          onClose={() => setStatusModalUser(null)}
        >
          <form onSubmit={handleStatusSubmit}>
            {statusError && <div className={styles.formError} role="alert">{statusError}</div>}

            <p style={{ margin: '0 0 12px' }}>
              Are you sure you want to {statusModalUser.status === 'ACTIVE' ? 'suspend' : 'reactivate'}{' '}
              <strong>{statusModalUser.fullName}</strong> ({statusModalUser.email})?
            </p>

            <div className={styles.formGroup}>
              <label className={styles.formLabel} htmlFor="status-reason">
                Reason for status change <span style={{ color: 'var(--adm-color-danger)' }}>*</span>
              </label>
              <textarea
                id="status-reason"
                className={styles.formTextarea}
                maxLength={500}
                placeholder="Explain why this status is being changed (10–500 characters)..."
                value={statusReason}
                onChange={(e) => setStatusReason(e.target.value)}
              />
              <p className={styles.formHelp} style={{ textAlign: 'right' }}>
                {statusReason.length} / 500 characters
              </p>
            </div>

            <p className={styles.subtle}>
              This action will be permanently recorded in the immutable audit log.
            </p>

            <div className={styles.modalActions}>
              <Button variant="outline" type="button" onClick={() => setStatusModalUser(null)}>
                Cancel
              </Button>
              <Button
                variant={statusModalUser.status === 'ACTIVE' ? 'danger' : 'primary'}
                type="submit"
                loading={statusSaving}
              >
                Confirm {statusModalUser.status === 'ACTIVE' ? 'suspension' : 'reactivation'}
              </Button>
            </div>
          </form>
        </Modal>
      )}
    </AdminPage>
  )
}

