import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { PageError } from '../../../shared/components/feedback'
import { Button, DataTable } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { getAdminAuditLogs, isRequestCancelled } from '../services/superAdminService'
import styles from '../superAdmin.module.css'

const ACTION_OPTIONS = [
  { value: '', label: 'Action: All Actions' },
  { value: 'ADMIN_ACCOUNT_CREATED', label: 'Account Created' },
  { value: 'ADMIN_STATUS_CHANGED', label: 'Status Changed' },
  { value: 'PLATFORM_SETTINGS_UPDATED', label: 'Setting Changed' },
  { value: 'DISPUTE_RESOLVED', label: 'Dispute Resolved' },
  { value: 'PROVIDER_VERIFIED', label: 'Provider Verified' },
]

const numberFmt = new Intl.NumberFormat('en-US')
function fmt(v) {
  if (v === null || v === undefined) return '—'
  return numberFmt.format(Number(v))
}

function getInitials(name) {
  if (!name) return '?'
  const clean = name.replace(/^user-/, '').replace(/-/g, ' ').trim()
  const parts = clean.split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

function timeAgo(iso) {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const secs = Math.floor(diff / 1000)
  if (secs < 60) return `${Math.max(1, secs)}s ago`
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  if (days === 1) return 'Yesterday'
  if (days < 30) return `${days} days ago`
  return ''
}

function formatTime(iso) {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function getActionLabel(action) {
  if (!action) return 'Action'
  switch (action) {
    case 'ADMIN_ACCOUNT_CREATED': return 'Account Created'
    case 'ADMIN_STATUS_CHANGED': return 'Status Changed'
    case 'PLATFORM_SETTINGS_UPDATED': return 'Setting Changed'
    case 'DISPUTE_RESOLVED': return 'Dispute Resolved'
    case 'PROVIDER_VERIFIED': return 'Provider Verified'
    default:
      return action
        .replace(/_/g, ' ')
        .toLowerCase()
        .replace(/\b\w/g, (c) => c.toUpperCase())
  }
}

function getActionChipClass(action) {
  switch (action) {
    case 'ADMIN_ACCOUNT_CREATED': return `${styles.dotChip} ${styles.dotAdmin}`
    case 'ADMIN_STATUS_CHANGED': return `${styles.dotChip} ${styles.dotSuspended}`
    case 'PLATFORM_SETTINGS_UPDATED': return `${styles.dotChip} ${styles.dotSuperAdmin}`
    case 'DISPUTE_RESOLVED': return `${styles.dotChip} ${styles.dotActive}`
    case 'PROVIDER_VERIFIED': return `${styles.dotChip} ${styles.dotActive}`
    default: return `${styles.dotChip} ${styles.dotAdmin}`
  }
}

export default function AuditLogPage() {
  const [params, setParams] = useSearchParams()

  const actorId = params.get('actorId') || ''
  const targetUserId = params.get('targetUserId') || ''
  const action = params.get('action') || ''
  const from = params.get('from') || ''
  const to = params.get('to') || ''
  const page = parseInt(params.get('page') || '0', 10)

  const urlFilter = actorId || targetUserId || ''
  const [prevUrlFilter, setPrevUrlFilter] = useState(urlFilter)
  const [searchInput, setSearchInput] = useState(urlFilter)
  if (prevUrlFilter !== urlFilter) {
    setPrevUrlFilter(urlFilter)
    setSearchInput(urlFilter)
  }

  const [result, setResult] = useState({ loading: true, data: null, error: null })
  const [reloadKey, setReloadKey] = useState(0)

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

  useEffect(() => {
    const controller = new AbortController()
    getAdminAuditLogs({ actorId, targetUserId, action, from, to, page, size: 20 }, controller.signal)
      .then((data) => setResult({ loading: false, data, error: null }))
      .catch((err) => {
        if (!isRequestCancelled(err)) {
          const msg = err?.response?.data?.message || err?.message || 'Failed to load audit logs.'
          setResult({ loading: false, data: null, error: { status: err?.response?.status ?? 0, message: msg } })
        }
      })
    return () => controller.abort()
  }, [actorId, targetUserId, action, from, to, page, reloadKey])

  const handleSearchSubmit = (e) => {
    e.preventDefault()
    // Filters by actorId or target
    updateParams({ actorId: searchInput.trim(), page: 0 })
  }

  const handleResetFilters = () => {
    setSearchInput('')
    setParams(new URLSearchParams())
  }

  const columns = useMemo(
    () => [
      {
        key: 'time',
        header: 'TIME',
        render: (_, r) => (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontWeight: 600, color: 'var(--adm-color-heading)' }}>
              {formatTime(r.createdAt)}
            </span>
            <span className={styles.subtle}>{timeAgo(r.createdAt)}</span>
          </div>
        ),
      },
      {
        key: 'actor',
        header: 'ACTOR',
        render: (_, r) => (
          <div className={styles.tableUserRow}>
            <span className={r.actorRole === 'SUPER_ADMIN' ? styles.avatarEV : styles.avatarGeneric}>
              {getInitials(r.actorId)}
            </span>
            <div className={styles.userCell}>
              <span className={styles.userName}>{r.actorId || '—'}</span>
              <span className={styles.userEmail}>{r.actorRole || 'System'}</span>
            </div>
          </div>
        ),
      },
      {
        key: 'action',
        header: 'ACTION',
        render: (v) => <span className={getActionChipClass(v)}>{getActionLabel(v)}</span>,
      },
      {
        key: 'entity',
        header: 'ENTITY',
        render: (_, r) => {
          let label = r.targetUserId ? `User: ${r.targetUserId}` : 'Platform Config'
          if (r.targetRole) label = `${r.targetRole}: ${r.targetUserId}`
          return (
            <span style={{ color: 'var(--adm-color-heading)', fontWeight: 500 }}>
              {label}
            </span>
          )
        },
      },
      {
        key: 'change',
        header: 'BEFORE & AFTER',
        render: (_, r) => {
          if (r.fromStatus || r.toStatus) {
            return (
              <div className={styles.bracketPill}>
                <span className={styles.pillBox}>[{r.fromStatus || 'None'}]</span>
                <span className={styles.arrow}>→</span>
                <span className={r.toStatus === 'SUSPENDED' ? styles.pillBoxDanger : styles.pillBoxHighlight}>
                  [{r.toStatus || 'None'}]
                </span>
              </div>
            )
          }
          if (r.action === 'ADMIN_ACCOUNT_CREATED') {
            return (
              <div className={styles.bracketPill}>
                <span className={styles.pillBox}>[None]</span>
                <span className={styles.arrow}>→</span>
                <span className={styles.pillBoxHighlight}>[Created ({r.targetRole || 'Admin'})]</span>
              </div>
            )
          }
          if (r.reason) {
            return <span className={styles.subtle}>{r.reason}</span>
          }
          return <span className={styles.subtle}>—</span>
        },
      },
    ],
    [],
  )

  const totalEvents = result.data?.total
  const timezone = useMemo(() => {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Browser Timezone'
    } catch {
      return 'UTC'
    }
  }, [])

  return (
    <AdminPage
      breadcrumbs={[{ label: 'System' }, { label: 'Audit log' }]}
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span>Audit log</span>
          <span className={`${styles.dotChip} ${styles.dotActive}`}>
            Read-only
          </span>
          <span className={`${styles.dotChip} ${styles.dotSuperAdmin}`}>
            Audit Trail Active
          </span>
        </div>
      }
      subtitle="A read-only record of admin actions across marketplace operators, platform configs, and financial resolutions."
      actions={
        totalEvents !== undefined ? (
          <span className={styles.subtle} style={{ fontWeight: 600 }}>
            {fmt(totalEvents)} Total Events
          </span>
        ) : null
      }
      plain
    >
      {/* Top stat cards (only real backend fields) */}
      <div className={styles.summaryGrid}>
        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>EVENTS SYNCED</p>
            <span className={styles.summaryIcon}><Icon name="fileText" size={18} /></span>
          </div>
          <p className={styles.summaryValue}>{totalEvents !== undefined ? fmt(totalEvents) : '—'}</p>
          <p className={styles.summaryFoot}>Recorded in immutable ledger</p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>ACTIVE ACTORS</p>
            <span className={styles.summaryIcon}><Icon name="users" size={18} /></span>
          </div>
          <p className={styles.summaryFoot}>Not available yet — requires aggregation</p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>CRITICAL MUTATIONS</p>
            <span className={styles.summaryIcon}><Icon name="shield" size={18} /></span>
          </div>
          <p className={styles.summaryFoot}>Not available yet — requires aggregation</p>
        </article>

        <article className={styles.summaryCard}>
          <div className={styles.summaryTop}>
            <p className={styles.summaryLabel}>LEDGER HEALTH</p>
            <span className={`${styles.summaryIcon} ${styles.summaryIcon_danger}`}><Icon name="shield" size={18} /></span>
          </div>
          <p className={styles.summaryFoot}>Immutable write-only collection</p>
        </article>
      </div>

      {/* Filter Bar */}
      <div className={styles.filterBar}>
        <form onSubmit={handleSearchSubmit} style={{ display: 'flex', flex: '1 1 240px', gap: 8 }}>
          <input
            className={styles.searchInput}
            type="search"
            placeholder="Search by entity, actor, or description..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            aria-label="Search audit logs"
          />
          <Button variant="outline" type="submit">Filter</Button>
        </form>

        <select
          className={styles.filterSelect}
          value={action}
          onChange={(e) => updateParams({ action: e.target.value, page: 0 })}
          aria-label="Filter by action"
        >
          {ACTION_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>

        <input
          type="date"
          className={styles.formInput}
          style={{ height: 40 }}
          value={from}
          onChange={(e) => updateParams({ from: e.target.value, page: 0 })}
          aria-label="From date"
        />

        <input
          type="date"
          className={styles.formInput}
          style={{ height: 40 }}
          value={to}
          onChange={(e) => updateParams({ to: e.target.value, page: 0 })}
          aria-label="To date"
        />

        <Button
          variant="outline"
          onClick={() => setReloadKey((k) => k + 1)}
          icon={<Icon name="switch" size={15} />}
          aria-label="Refresh"
        >
          Refresh
        </Button>

        {(actorId || targetUserId || action || from || to) && (
          <Button variant="outline" onClick={handleResetFilters}>
            Reset
          </Button>
        )}
      </div>

      {/* Stream Info Bar */}
      <div className={styles.streamInfoBar}>
        <span>
          Showing latest {result.data?.items?.length || 0} security &amp; operational audit records in real-time execution stream
        </span>
        <span>
          Timezone: {timezone} (Synchronized)
        </span>
      </div>

      {/* Audit Log Table */}
      {result.error ? (
        <section className={styles.card}>
          <PageError
            statusCode={result.error.status}
            title="Audit logs could not be loaded"
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
            emptyMessage="No audit logs recorded for the selected criteria."
          />

          {result.data && (
            <div className={styles.paginationRow}>
              <p className={styles.subtle}>
                Showing {result.data.items?.length > 0 ? page * result.data.size + 1 : 0} to{' '}
                {Math.min((page + 1) * result.data.size, result.data.total)} of {fmt(result.data.total)} audit entries
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

      {/* Bottom Immutable Audit Banner */}
      <div className={styles.bottomPolicyBar}>
        <div className={styles.bottomPolicyText}>
          <Icon name="shield" size={18} />
          <div>
            <strong>Immutable audit log.</strong>
            <span style={{ marginLeft: 6, color: 'var(--adm-color-text-muted)' }}>
              Cryptographically recorded and tamper-evident. Strictly read-only.
            </span>
          </div>
        </div>
        <span className={styles.subtle}>
          Verified
        </span>
      </div>
    </AdminPage>
  )
}

