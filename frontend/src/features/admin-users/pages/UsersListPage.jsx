import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'

import { EmptyState, PageError, SkeletonTable, useToast } from '../../../shared/components/feedback'
import { Button, DataTable, Pagination } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useDebouncedValue } from '../../admin-announcements/hooks/useDebouncedValue'
import { BarChart } from '../components/Charts'
import { Avatar, ProChip, UserStatusChip } from '../components/UserChips'
import { UsersIcon } from '../components/UsersIcon'
import { UsersSummaryCards } from '../components/UsersSummaryCards'
import {
  LIMITS,
  ROLE_LABEL,
  ROLE_OPTIONS,
  STATUS_OPTIONS,
  shortUserId,
  userPaths,
} from '../config/usersConfig'
import {
  exportUsersCsv,
  getRegistrations,
  getUsersSummary,
  isRequestCancelled,
  listUsers,
} from '../services/adminUsersService'
import { parseApiError } from '../utils/errors'
import { bucketLabel, formatDate, formatNumber } from '../utils/format'
import styles from '../adminUsers.module.css'

const BREADCRUMBS = ['Operations', 'Users']
const ROLES = new Set(ROLE_OPTIONS.map((o) => o.value))
const STATUSES = new Set(STATUS_OPTIONS.map((o) => o.value))
const SERIES = [
  { key: 'customers', label: 'Customers', tone: 'primary' },
  { key: 'providers', label: 'Providers', tone: 'secondary' },
]

/** Filters and page live in the URL query, so Back from an account returns to the same list. */
function readParams(params) {
  const role = ROLES.has(params.get('role') ?? '') ? params.get('role') ?? '' : ''
  const status = STATUSES.has(params.get('status') ?? '') ? params.get('status') ?? '' : ''
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  return { q: (params.get('q') ?? '').slice(0, LIMITS.SEARCH), role, status, page }
}

function RegistrationsChart() {
  const [period, setPeriod] = useState('WEEKS')
  const [result, setResult] = useState({ period: null, data: null, error: null })
  const loading = result.period !== period

  useEffect(() => {
    const controller = new AbortController()
    getRegistrations(period, controller.signal)
      .then((data) => setResult({ period, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ period, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [period])

  const buckets = !loading && result.data?.available ? result.data.buckets ?? [] : null
  const unit = period === 'WEEKS' ? 'week' : 'month'
  const totals = buckets
    ? buckets.reduce((sum, b) => ({ c: sum.c + b.customers, p: sum.p + b.providers }), { c: 0, p: 0 })
    : null

  return (
    <section className={styles.card} aria-labelledby="users-reg-title">
      <div className={styles.cardHead}>
        <div>
          <h2 id="users-reg-title" className={styles.cardTitle}>
            New users per {unit}
          </h2>
          <p className={styles.cardSubtitle}>
            {period === 'WEEKS' ? 'Last 8 weeks' : 'Last 6 months'} user registration trend
          </p>
        </div>
        <div className={styles.chartControls}>
          {buckets && (
            <ul className={styles.legend} aria-hidden="true">
              <li>
                <span className={`${styles.legendDot} ${styles.bar_primary}`} />
                Customers
              </li>
              <li>
                <span className={`${styles.legendDot} ${styles.bar_secondary}`} />
                Providers
              </li>
            </ul>
          )}
          <div className={styles.segmented} role="group" aria-label="Chart period">
            {[
              ['WEEKS', 'Weeks'],
              ['MONTHS', 'Months'],
            ].map(([value, label]) => (
              <button
                key={value}
                type="button"
                aria-pressed={period === value}
                className={period === value ? styles.segmentOn : ''}
                onClick={() => setPeriod(value)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      </div>
      {loading ? (
        <div className={styles.chartSkeleton} aria-hidden="true" />
      ) : result.error ? (
        <p className={styles.inlineError} role="alert">
          Registration data could not be loaded. {result.error.message}
        </p>
      ) : buckets ? (
        <>
          <BarChart
            data={buckets.map((b) => ({
              label: bucketLabel(b.start, period),
              title: `${period === 'WEEKS' ? 'Week of' : ''} ${bucketLabel(b.start, period)}`.trim(),
              values: { customers: b.customers, providers: b.providers },
            }))}
            series={SERIES}
            caption={`New users per ${unit}`}
            summary={`New users per ${unit}: ${formatNumber(totals.c)} customers and ${formatNumber(totals.p)} providers in total`}
          />
          <p className={styles.chartNote}>
            {period === 'WEEKS' ? 'Weeks start on Monday (UTC). ' : ''}Accounts without a sign-up date are not counted.
          </p>
        </>
      ) : (
        <div className={styles.chartEmpty}>
          <UsersIcon name="barChart" size={22} />
          <p>Registration data will appear when the accounts service is connected.</p>
        </div>
      )}
    </section>
  )
}

/** Screen 1: users list. */
export function UsersListPage() {
  const toast = useToast()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const { q, role, status, page } = readParams(params)
  const [search, setSearch] = useState(q)
  const debounced = useDebouncedValue(search.trim(), 300)
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const [summary, setSummary] = useState({ loading: true, data: null })
  const [exporting, setExporting] = useState(false)

  const update = (changes) => {
    const next = { q, role, status, page, ...changes }
    const query = {}
    if (next.q) query.q = next.q
    if (next.role) query.role = next.role
    if (next.status) query.status = next.status
    if (next.page > 1) query.page = String(next.page)
    setParams(query, { replace: true })
  }

  // Runs only when the debounced text changes (never on other URL changes, so "Clear filters" is not undone by a
  // stale value) and writes it to the URL, back on page 1, only if it differs from what the URL already has.
  useEffect(() => {
    setParams(
      (current) => {
        if ((current.get('q') ?? '') === debounced) return current
        const next = new URLSearchParams(current)
        if (debounced) next.set('q', debounced)
        else next.delete('q')
        next.delete('page')
        return next
      },
      { replace: true },
    )
  }, [debounced, setParams])

  const requestKey = `${q}|${role}|${status}|${page}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    listUsers({ q: q || undefined, role: role || undefined, status: status || undefined, page: page - 1, size: LIMITS.PAGE_SIZE }, controller.signal)
      .then((data) => setResult({ key: requestKey, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [q, role, status, page, requestKey])

  useEffect(() => {
    const controller = new AbortController()
    getUsersSummary(controller.signal)
      .then((data) => setSummary({ loading: false, data }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setSummary({ loading: false, data: null })
      })
    return () => controller.abort()
  }, [reloadKey])

  const exportCsv = async () => {
    setExporting(true)
    try {
      const { blob, filename } = await exportUsersCsv({ q: q || undefined, role: role || undefined, status: status || undefined })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      toast.success(`Exported ${filename}.`)
    } catch (error) {
      toast.error(parseApiError(error).message)
    } finally {
      setExporting(false)
    }
  }

  const clearFilters = () => {
    setSearch('')
    setParams({}, { replace: true })
  }

  const data = loading ? null : result.data
  const total = data?.totalElements ?? 0
  const from = total === 0 ? 0 : (page - 1) * LIMITS.PAGE_SIZE + 1
  const to = Math.min(page * LIMITS.PAGE_SIZE, total)
  const filtered = Boolean(q || role || status)

  const columns = useMemo(
    () => [
      {
        key: 'displayName',
        header: 'Name',
        render: (_, user) => (
          <div className={styles.nameCell}>
            <Avatar name={user.displayName} tone={user.status === 'SUSPENDED' ? 'danger' : undefined} />
            <div>
              <span className={styles.nameLine}>
                <Link
                  to={userPaths.detail(user.id)}
                  state={{ listSearch: location.search }}
                  className={styles.nameLink}
                >
                  {user.displayName || 'Unnamed account'}
                </Link>
                {user.pro === true && <ProChip />}
              </span>
              <span className={styles.subtle}>ID: {shortUserId(user.id, user.role)}</span>
            </div>
          </div>
        ),
      },
      { key: 'role', header: 'Role', render: (value) => ROLE_LABEL[value] ?? value },
      { key: 'email', header: 'Email', render: (value) => <span className={styles.emailCell}>{value}</span> },
      { key: 'joinedAt', header: 'Joined', render: (value) => formatDate(value) },
      { key: 'status', header: 'Status', render: (value) => <UserStatusChip status={value} /> },
    ],
    [location.search],
  )

  const rowActions = (user) => (
    <Link
      to={userPaths.detail(user.id)}
      state={{ listSearch: location.search }}
      className={styles.actionLink}
      aria-label={`View ${user.displayName || 'account'}`}
    >
      View
    </Link>
  )

  return (
    <AdminPage
      title="Users"
      subtitle="Manage customers, providers, and account status across the marketplace"
      breadcrumbs={BREADCRUMBS}
      plain
    >
      <UsersSummaryCards summary={summary.data} loading={summary.loading} />

      <RegistrationsChart />

      <section className={styles.card} aria-label="Accounts">
        <div className={styles.filterBar}>
          <label className={styles.searchBox}>
            <UsersIcon name="search" size={15} />
            <span className="fb-sr-only">Search by name, email, or user ID</span>
            <input
              type="search"
              value={search}
              maxLength={LIMITS.SEARCH}
              placeholder="Search by name, email, or user ID..."
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <label className={styles.filterSelect}>
            <span className="fb-sr-only">Role</span>
            <select value={role} onChange={(event) => update({ role: event.target.value, page: 1 })}>
              {ROLE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.filterSelect}>
            <span className="fb-sr-only">Status</span>
            <select value={status} onChange={(event) => update({ status: event.target.value, page: 1 })}>
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <div className={styles.filterSpacer} />
          <Button
            variant="outline"
            onClick={exportCsv}
            loading={exporting}
            disabled={exporting}
            icon={<UsersIcon name="download" size={15} />}
          >
            Export CSV
          </Button>
        </div>

        {result.error && !loading ? (
          <PageError
            statusCode={result.error.status}
            title="Users could not be loaded"
            message={result.error.message}
            onRetry={() => setReloadKey((k) => k + 1)}
          />
        ) : (
          <DataTable
            className={styles.table}
            columns={columns}
            data={data?.content ?? []}
            actions={rowActions}
            loading={loading}
            loadingContent={<SkeletonTable rows={LIMITS.PAGE_SIZE} columns={6} />}
            emptyContent={
              <EmptyState
                variant={filtered ? 'search' : 'default'}
                title={filtered ? 'No users match' : 'No users yet'}
                message={filtered ? 'Try another search or clear the filters.' : 'Customer and provider accounts will appear here.'}
                actionLabel={filtered ? 'Clear filters' : undefined}
                onAction={filtered ? clearFilters : undefined}
                compact
              />
            }
          />
        )}

        {!result.error && total > 0 && (
          <div className={styles.paginationRow}>
            <p className={styles.subtle} aria-live="polite">
              Showing {formatNumber(from)} to {formatNumber(to)} of {formatNumber(total)} users
            </p>
            <Pagination
              currentPage={page}
              totalPages={data?.totalPages ?? 1}
              onPageChange={(next) => update({ page: next })}
              disabled={loading}
            />
          </div>
        )}
      </section>
    </AdminPage>
  )
}
