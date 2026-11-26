import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { EmptyState, InlineMessage, SkeletonTable } from '../../../shared/components/feedback'
import { Button, Pagination } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { formatDateTime, shortReference } from '../../disputes/utils/format'
import { useDebouncedValue } from '../../admin-announcements/hooks/useDebouncedValue'
import { SettlementChip, StatusChip } from '../components/AdminDisputeChips'
import {
  casePath,
  CATEGORY_FILTERS,
  categoryName,
  LIMITS,
  PAGE_SIZE,
  SORT_OPTIONS,
  STATUS_FILTERS,
} from '../config/adminDisputeConfig'
import { getAdminDisputeSummary, isRequestCancelled, listAdminDisputes } from '../services/adminDisputesService'
import { parseAdminError } from '../utils/adminDisputeErrors'
import styles from '../adminDisputes.module.css'

const SUMMARY_CARDS = Object.freeze([
  { key: 'underReview', label: 'Under review', icon: 'eye', tone: 'blue' },
  { key: 'awaitingResponse', label: 'Awaiting response', icon: 'clock', tone: 'amber' },
  { key: 'appealed', label: 'Appealed', icon: 'flag', tone: 'blue' },
  { key: 'resolvedLast30Days', label: 'Resolved (30 days)', icon: 'check', tone: 'green' },
  { key: 'manualReview', label: 'Manual review', icon: 'alertCircle', tone: 'red' },
])

const FILTER_KEYS = ['status', 'category', 'q', 'from', 'to', 'sort']
const DATE = /^\d{4}-\d{2}-\d{2}$/

/** URL query -> API filters (only known keys and valid values; page is 1-based in the URL). */
function readFilters(params) {
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const page = Number.parseInt(params.get('page') ?? '1', 10)
  return {
    status: STATUS_FILTERS.some((s) => s.value === params.get('status')) ? params.get('status') : '',
    category: CATEGORY_FILTERS.some((c) => c.value === params.get('category')) ? params.get('category') : '',
    q: (params.get('q') ?? '').slice(0, LIMITS.SEARCH_MAX),
    from: DATE.test(from) ? from : '',
    to: DATE.test(to) ? to : '',
    sort: SORT_OPTIONS.some((s) => s.value === params.get('sort')) ? params.get('sort') : 'createdAt,desc',
    page: Number.isFinite(page) && page >= 1 ? page : 1,
  }
}

/**
 * Admin disputes list (/admin/admin/disputes): summary cards, filters (status, category, search, date range, sort)
 * and the table. Every filter and the page live in the URL, so a view can be shared, refreshed or returned to.
 */
export function AdminDisputesListPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const [search, setSearch] = useState(filters.q)
  const debouncedSearch = useDebouncedValue(search, 350)
  const [summary, setSummary] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const requestKey = `${params.toString()}#${attempt}`
  const [result, setResult] = useState({ key: null, data: null, error: null })

  function update(patch) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      Object.entries(patch).forEach(([key, value]) => {
        if (value === '' || value === null || value === undefined) next.delete(key)
        else next.set(key, String(value))
      })
      if (!('page' in patch)) next.delete('page')
      if (next.get('sort') === 'createdAt,desc') next.delete('sort')
      return next
    })
  }

  // Search text reaches the URL (and the API) after a short pause.
  useEffect(() => {
    setParams((current) => {
      const value = debouncedSearch.trim()
      if ((current.get('q') ?? '') === value) return current
      const next = new URLSearchParams(current)
      if (value) next.set('q', value)
      else next.delete('q')
      next.delete('page')
      return next
    })
  }, [debouncedSearch, setParams])

  useEffect(() => {
    const controller = new AbortController()
    getAdminDisputeSummary(controller.signal).then(setSummary).catch(() => setSummary(null))
    return () => controller.abort()
  }, [attempt])

  useEffect(() => {
    const controller = new AbortController()
    const { page, ...rest } = readFilters(new URLSearchParams(requestKey.split('#')[0]))
    listAdminDisputes({ ...rest, page: page - 1, size: PAGE_SIZE }, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key: requestKey, data, error: null })
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isRequestCancelled(error)) {
          setResult({ key: requestKey, data: null, error: parseAdminError(error) })
        }
      })
    return () => controller.abort()
  }, [requestKey])

  const loading = result.key !== requestKey
  const rows = result.data?.content ?? []
  const totalPages = Math.max(1, result.data?.totalPages ?? 1)
  const hasFilters = FILTER_KEYS.some((key) => key !== 'sort' && params.get(key))

  return (
    <AdminPage
      title="Disputes"
      subtitle="Review dispute cases, make decisions and handle appeals."
      breadcrumbs={[{ label: 'Admin', to: '/admin/admin' }, 'Disputes']}
      plain
    >
      <ul className={styles.summaryCards} aria-label="Dispute summary">
        {SUMMARY_CARDS.map((card) => (
          <li key={card.key} className={styles.summaryCard}>
            <span className={`${styles.summaryIcon} ${styles[`tone_${card.tone}`]}`}><Icon name={card.icon} size={16} /></span>
            <span className={styles.summaryText}>
              <span className={styles.summaryLabel}>{card.label}</span>
              <strong className={styles.summaryValue} data-testid={`summary-${card.key}`}>
                {summary ? summary[card.key] ?? 0 : '–'}
              </strong>
            </span>
          </li>
        ))}
      </ul>

      <div className={styles.card}>
        <div className={styles.filters} role="search" aria-label="Filter disputes">
          <label className={styles.filter}>
            <span>Search</span>
            <input
              type="search"
              value={search}
              maxLength={LIMITS.SEARCH_MAX}
              placeholder="Case, booking or user id"
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <label className={styles.filter}>
            <span>Status</span>
            <select value={filters.status} onChange={(event) => update({ status: event.target.value })}>
              <option value="">All statuses</option>
              {STATUS_FILTERS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <label className={styles.filter}>
            <span>Category</span>
            <select value={filters.category} onChange={(event) => update({ category: event.target.value })}>
              <option value="">All categories</option>
              {CATEGORY_FILTERS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <label className={styles.filter}>
            <span>From</span>
            <input type="date" value={filters.from} max={filters.to || undefined}
              onChange={(event) => update({ from: event.target.value })} />
          </label>
          <label className={styles.filter}>
            <span>To</span>
            <input type="date" value={filters.to} min={filters.from || undefined}
              onChange={(event) => update({ to: event.target.value })} />
          </label>
          <label className={styles.filter}>
            <span>Sort</span>
            <select value={filters.sort} onChange={(event) => update({ sort: event.target.value })}>
              {SORT_OPTIONS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          {hasFilters && (
            <Button variant="outline" size="small" onClick={() => { setSearch(''); setParams({}) }}>
              Clear filters
            </Button>
          )}
        </div>

        <div aria-live="polite" aria-busy={loading}>
          {loading && <SkeletonTable rows={5} columns={6} />}
          {!loading && result.error && (
            <div className={styles.stateBox}>
              <InlineMessage variant="error">{result.error.message}</InlineMessage>
              <Button variant="secondary" onClick={() => setAttempt((value) => value + 1)}>Try again</Button>
            </div>
          )}
          {!loading && !result.error && rows.length === 0 && (
            <EmptyState
              variant={hasFilters ? 'search' : 'default'}
              title={hasFilters ? 'No disputes match these filters' : 'No disputes yet'}
              message={hasFilters ? 'Try other filters or clear them.' : 'New dispute cases will appear here.'}
              compact
            />
          )}
          {!loading && !result.error && rows.length > 0 && (
            <>
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <caption className={styles.srOnly}>Dispute cases</caption>
                  <thead>
                    <tr>
                      <th scope="col">Case</th>
                      <th scope="col">Booking</th>
                      <th scope="col">Category</th>
                      <th scope="col">Raised by</th>
                      <th scope="col">Status</th>
                      <th scope="col">Response deadline</th>
                      <th scope="col">Raised</th>
                      <th scope="col">Settlement</th>
                      <th scope="col"><span className={styles.srOnly}>Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((dispute) => {
                      const isFinal = dispute.status === 'RESOLVED_FINAL'
                      return (
                        <tr key={dispute.id}>
                          <td data-label="Case"><span className={styles.ref}>{shortReference('D', dispute.id)}</span></td>
                          <td data-label="Booking">{shortReference('B', dispute.bookingId)}</td>
                          <td data-label="Category">{categoryName(dispute.category)}</td>
                          <td data-label="Raised by">{dispute.raisedByRole === 'PROVIDER' ? 'Provider' : 'Customer'}</td>
                          <td data-label="Status"><StatusChip status={dispute.status} /></td>
                          <td data-label="Response deadline">
                            {dispute.status === 'AWAITING_RESPONSE' ? formatDateTime(dispute.responseDueAt) : '–'}
                          </td>
                          <td data-label="Raised">{formatDateTime(dispute.createdAt)}</td>
                          <td data-label="Settlement">
                            {isFinal ? <SettlementChip state={dispute.settlementState} /> : '–'}
                          </td>
                          <td>
                            <Link to={casePath(dispute.id)} className={styles.rowLink}
                              aria-label={`Open case ${shortReference('D', dispute.id)}`}>
                              View
                            </Link>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {totalPages > 1 && (
                <div className={styles.paginationRow}>
                  <Pagination currentPage={filters.page} totalPages={totalPages}
                    onPageChange={(page) => update({ page: page > 1 ? page : '' })} />
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </AdminPage>
  )
}
