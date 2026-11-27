import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { PageError } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { areaPath } from '../../disputes-admin/config/adminAreas'
import { getAnalyticsSummary, isRequestCancelled } from '../services/superAdminService'
import styles from '../superAdmin.module.css'

const numberFormat = new Intl.NumberFormat('en-US')
function fmt(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—'
  return numberFormat.format(Number(v))
}

function timeAgo(iso) {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'Just now'
  if (mins < 60) return `${mins} mins ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs} hrs ago`
  return 'Yesterday'
}

function initials(actorId) {
  if (!actorId) return '?'
  const parts = actorId.replace(/-/g, ' ').split(' ')
  return parts.slice(0, 2).map((p) => p[0]?.toUpperCase() ?? '').join('')
}

function MetricCard({ label, value, available, reason, icon = 'dashboard', tone = 'primary', loading }) {
  const danger = tone === 'danger'
  return (
    <article className={styles.summaryCard}>
      <div className={styles.summaryTop}>
        <p className={styles.summaryLabel}>{label}</p>
        <span className={`${styles.summaryIcon} ${danger ? styles.summaryIcon_danger : ''}`}>
          <Icon name={icon} size={18} />
        </span>
      </div>
      {loading ? (
        <span className={styles.summarySkeleton} aria-hidden="true" />
      ) : available ? (
        <p className={`${styles.summaryValue} ${danger ? styles.summaryValue_danger : ''}`}>{fmt(value)}</p>
      ) : (
        <p className={styles.summaryFoot}>{reason || 'Not available yet.'}</p>
      )}
    </article>
  )
}

function UnavailablePlaceholder({ reason, icon = 'barChart', title }) {
  return (
    <div className={styles.placeholder}>
      <Icon name={icon} size={22} />
      {title && <strong>{title}</strong>}
      <p>{reason || 'This information will appear when the data source is connected.'}</p>
    </div>
  )
}

export default function SuperAdminDashboardPage() {
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const loading = result.key !== reloadKey

  useEffect(() => {
    const controller = new AbortController()
    getAnalyticsSummary(controller.signal)
      .then((data) => setResult({ key: reloadKey, data, error: null }))
      .catch((err) => {
        if (!isRequestCancelled(err)) {
          const msg = err?.response?.data?.message || err?.message || 'Failed to load dashboard.'
          setResult({ key: reloadKey, data: null, error: { status: err?.response?.status ?? 0, message: msg } })
        }
      })
    return () => controller.abort()
  }, [reloadKey])

  const d = loading ? null : result.data

  const metricCards = useMemo(() => {
    if (!d) return []
    return [
      { label: 'Open disputes', value: d.openDisputes?.value, available: d.openDisputes?.available ?? false, reason: d.openDisputes?.reason, icon: 'alertCircle', tone: 'danger' },
      { label: 'Admin accounts', value: d.adminAccounts?.value, available: d.adminAccounts?.available ?? false, reason: d.adminAccounts?.reason, icon: 'userPlus' },
      { label: 'Pending verifications', value: d.pendingVerifications?.value, available: d.pendingVerifications?.available ?? false, reason: d.pendingVerifications?.reason, icon: 'shield' },
      { label: 'Users', value: d.users?.value, available: d.users?.available ?? false, reason: d.users?.reason || 'Not available yet — needs WP01 auth module', icon: 'users' },
      { label: 'Providers', value: d.providers?.value, available: d.providers?.available ?? false, reason: d.providers?.reason || 'Not available yet — needs WP01 auth module', icon: 'users' },
      { label: 'Bookings', value: d.bookings?.value, available: d.bookings?.available ?? false, reason: d.bookings?.reason || 'Not available yet — needs WP05 booking module', icon: 'calendar' },
      { label: 'Booking value', value: d.bookingValue?.value, available: d.bookingValue?.available ?? false, reason: d.bookingValue?.reason || 'Not available yet — needs WP07 payment module', icon: 'creditCard' },
      { label: 'Commission', value: d.commission?.value, available: d.commission?.available ?? false, reason: d.commission?.reason || 'Not available yet — needs WP07 payment module', icon: 'barChart' },
      { label: 'Refunds', value: d.refunds?.value, available: d.refunds?.available ?? false, reason: d.refunds?.reason || 'Not available yet — needs WP07 payment module', icon: 'refund' },
    ]
  }, [d])

  const breakdown = d?.disputeBreakdown
  const auditItems = d?.recentAuditActivity ?? []

  return (
    <AdminPage
      title="Dashboard"
      subtitle="Platform overview"
      actions={
        <Button variant="outline" onClick={() => setReloadKey((k) => k + 1)} loading={loading} icon={<Icon name="switch" size={15} />}>
          Refresh
        </Button>
      }
      plain
    >
      {result.error ? (
        <section className={styles.card}>
          <PageError statusCode={result.error.status} title="Dashboard could not be loaded" message={result.error.message} onRetry={() => setReloadKey((k) => k + 1)} />
        </section>
      ) : (
        <>
          {/* Metric cards */}
          <div className={styles.summaryGrid}>
            {loading
              ? Array.from({ length: 4 }, (_, i) => <MetricCard key={i} label="—" loading />)
              : metricCards.map((c) => <MetricCard key={c.label} {...c} />)}
          </div>

          {/* Dispute breakdown */}
          {d && breakdown && (
            <section className={styles.card} aria-labelledby="dash-breakdown">
              <div className={styles.cardHead}>
                <div>
                  <h2 id="dash-breakdown" className={styles.cardTitle}>Open dispute breakdown</h2>
                  <p className={styles.cardSubtitle}>Distribution of open disputes by status</p>
                </div>
              </div>
              <div className={styles.summaryGrid}>
                <MetricCard label="Under review" value={breakdown.underReview} available icon="alertCircle" />
                <MetricCard label="Awaiting response" value={breakdown.awaitingResponse} available icon="clock" />
                <MetricCard label="Appealed" value={breakdown.appealed} available icon="fileText" />
                <MetricCard label="Manual review" value={breakdown.manualReview} available icon="shield" tone="danger" />
              </div>
            </section>
          )}

          {/* Chart placeholders */}
          <div className={styles.twoCol}>
            <section className={styles.card}>
              <div className={styles.cardHead}>
                <div>
                  <h2 className={styles.cardTitle}>Bookings over time</h2>
                  <p className={styles.cardSubtitle}>Daily booking volume</p>
                </div>
              </div>
              <UnavailablePlaceholder reason="Not available yet — needs WP05 booking module" icon="barChart" />
            </section>

            <section className={styles.card}>
              <div className={styles.cardHead}>
                <div>
                  <h2 className={styles.cardTitle}>Bookings by status</h2>
                  <p className={styles.cardSubtitle}>Current breakdown of active bookings</p>
                </div>
              </div>
              <UnavailablePlaceholder reason="Not available yet — needs WP05 booking module" icon="reports" />
            </section>
          </div>

          <div className={styles.twoCol}>
            <section className={styles.card}>
              <div className={styles.cardHead}>
                <div>
                  <h2 className={styles.cardTitle}>Booking value vs commission</h2>
                  <p className={styles.cardSubtitle}>Monetary comparison</p>
                </div>
              </div>
              <UnavailablePlaceholder reason="Not available yet — needs WP07 payment module" icon="barChart" />
            </section>

            <section className={styles.card} aria-labelledby="dash-audit">
              <div className={styles.cardHead}>
                <div>
                  <h2 id="dash-audit" className={styles.cardTitle}>Recent audit activity</h2>
                  <p className={styles.cardSubtitle}>Latest security and operational changes</p>
                </div>
                <Link to={areaPath('SUPER_ADMIN', 'audit-log')} className="shared-btn shared-btn--outline shared-btn--small">
                  View log →
                </Link>
              </div>
              {auditItems.length > 0 ? (
                <ul className={styles.auditList}>
                  {auditItems.map((item, i) => (
                    <li key={item.id || i} className={styles.auditItem}>
                      <span className={styles.auditAvatar}>{initials(item.actorId)}</span>
                      <div className={styles.auditBody}>
                        <strong>{item.actorId}</strong>
                        <p>{item.action?.replace(/_/g, ' ').toLowerCase()}{item.targetRef ? ` — ${item.targetRef}` : ''}</p>
                      </div>
                      <span className={styles.auditTime}>{timeAgo(item.createdAt)}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.subtle}>No recent activity.</p>
              )}
            </section>
          </div>

          {/* Quick links */}
          <section className={styles.card}>
            <div className={styles.cardHead}>
              <h2 className={styles.cardTitle}>Quick links</h2>
            </div>
            <div className={styles.quickLinks}>
              <Link to={areaPath('SUPER_ADMIN', 'platform-settings')} className={styles.quickLink}>
                <span className={styles.quickLinkIcon}><Icon name="settings" size={20} /></span>
                <div className={styles.quickLinkText}>
                  <h3>Platform settings</h3>
                  <p>Global platform rules and configs</p>
                </div>
              </Link>
              <Link to={areaPath('SUPER_ADMIN', 'admin-accounts')} className={styles.quickLink}>
                <span className={styles.quickLinkIcon}><Icon name="userPlus" size={20} /></span>
                <div className={styles.quickLinkText}>
                  <h3>Admin accounts</h3>
                  <p>Manage roles, permissions & access</p>
                </div>
              </Link>
              <Link to={areaPath('SUPER_ADMIN', 'audit-log')} className={styles.quickLink}>
                <span className={styles.quickLinkIcon}><Icon name="fileText" size={20} /></span>
                <div className={styles.quickLinkText}>
                  <h3>Audit log</h3>
                  <p>Complete chronological security logs</p>
                </div>
              </Link>
            </div>
          </section>
        </>
      )}
    </AdminPage>
  )
}
