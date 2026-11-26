import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { EmptyState, PageError, SkeletonTable, useToast } from '../../../shared/components/feedback'
import { Button, DataTable, Pagination } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { useDebouncedValue } from '../../admin-announcements/hooks/useDebouncedValue'
import { HBars } from '../components/Charts'
import { ActionModal } from '../components/ActionModal'
import { ActionRequiredChip, CaseStatusChip, DaysOverdueChip } from '../components/Chips'
import { SummaryCards } from '../components/SummaryCards'
import { UnavailableSection } from '../components/Placeholder'
import {
  ACTION_LABEL,
  actionOptions,
  CASE_ACTIONS,
  DELAY_BUCKET_OPTIONS,
  PAGE_SIZE,
  SEARCH_MAX,
} from '../config/operationsConfig'
import { exportOverdueCsv, getOverdueBookings, isRequestCancelled, recordOverdueAction } from '../services/operationsService'
import { parseApiError } from '../utils/errors'
import { formatDate, formatNumber, shortId } from '../utils/format'
import { downloadBlob } from '../utils/download'
import styles from '../operations.module.css'

const BUCKETS = new Set(DELAY_BUCKET_OPTIONS.map((o) => o.value))
const DIST = [
  { key: 'oneDay', label: '1 Day', tone: 'primary' },
  { key: 'twoDays', label: '2 Days', tone: 'primary' },
  { key: 'threeDays', label: '3 Days', tone: 'danger' },
  { key: 'fourPlusDays', label: '4+ Days', tone: 'danger' },
]

function readParams(params) {
  const delayBucket = BUCKETS.has(params.get('delayBucket') ?? '') ? params.get('delayBucket') ?? '' : ''
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  return { q: (params.get('q') ?? '').slice(0, SEARCH_MAX), delayBucket, page }
}

export default function OverdueBookingsPage() {
  const { isViewOnly } = useAreaAccess() ?? {}
  const canEdit = !isViewOnly
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const { q, delayBucket, page } = readParams(params)
  const [search, setSearch] = useState(q)
  const debounced = useDebouncedValue(search.trim(), 300)
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, section: null, error: null })
  const [exporting, setExporting] = useState(false)
  const [handling, setHandling] = useState(null)

  const update = (changes) => {
    const next = { q, delayBucket, page, ...changes }
    const query = {}
    if (next.q) query.q = next.q
    if (next.delayBucket) query.delayBucket = next.delayBucket
    if (next.page > 1) query.page = String(next.page)
    setParams(query, { replace: true })
  }

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

  const requestKey = `${q}|${delayBucket}|${page}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    getOverdueBookings(
      { search: q || undefined, delayBucket: delayBucket || undefined, page: page - 1, size: PAGE_SIZE },
      controller.signal,
    )
      .then((section) => setResult({ key: requestKey, section, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, section: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [q, delayBucket, page, requestKey])

  const exportCsv = async () => {
    setExporting(true)
    try {
      const { blob, filename } = await exportOverdueCsv({ search: q || undefined, delayBucket: delayBucket || undefined })
      downloadBlob(blob, filename)
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

  const section = loading ? null : result.section
  const data = section?.available ? section.data : null
  const total = data?.rows?.totalElements ?? 0
  const from = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1
  const to = Math.min(page * PAGE_SIZE, total)
  const filtered = Boolean(q || delayBucket)
  const summary = data?.summary

  const columns = useMemo(
    () => [
      {
        key: 'ref',
        header: 'Booking',
        render: (_, r) => (
          <div className={styles.refCell}>
            <span className={styles.refId}>#{r.ref}</span>
            <span className={styles.subtle}>{r.serviceTitle || '—'}</span>
          </div>
        ),
      },
      { key: 'customerId', header: 'Customer', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'providerId', header: 'Provider', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'serviceDate', header: 'Service date', render: (v) => formatDate(v) },
      { key: 'daysOverdue', header: 'Days overdue', render: (v) => <DaysOverdueChip days={v} /> },
      { key: 'caseStatus', header: 'Status', render: (v) => <CaseStatusChip status={v} /> },
    ],
    [],
  )

  const rowActions = canEdit
    ? (r) => (
        <Button variant="outline" size="small" onClick={() => setHandling(r)}>
          Handle
        </Button>
      )
    : undefined

  const submitAction = async (action, note) => {
    await recordOverdueAction(handling.bookingId, { action, note })
    setHandling(null)
    toast.success(`${ACTION_LABEL[action] ?? 'Action'} recorded for #${handling.ref}.`)
    setReloadKey((k) => k + 1)
  }

  return (
    <AdminPage
      title={
        <>
          Overdue bookings
          {summary && <ActionRequiredChip count={summary.overdueNow} />}
        </>
      }
      subtitle="Manage jobs past scheduled completion awaiting provider closure or customer sign-off."
      breadcrumbs={['Operations', 'Overdue bookings']}
      actions={
        <Button variant="outline" onClick={exportCsv} loading={exporting} disabled={exporting} icon={<Icon name="fileText" size={15} />}>
          Export
        </Button>
      }
      plain
    >
      {!section || section.available ? (
        <>
          <SummaryCards
            loading={loading}
            cards={[
              { label: 'Overdue now', value: summary?.overdueNow, icon: 'clock', foot: 'Active delayed bookings' },
              { label: 'Over 24 hours', value: summary?.over24h, icon: 'alertCircle', tone: 'danger', foot: 'Critical delay' },
              { label: 'Resolved today', value: summary?.resolvedToday, icon: 'check', foot: 'Successfully closed' },
            ]}
          />

          <section className={styles.card} aria-labelledby="ovd-dist">
            <div className={styles.cardHead}>
              <div>
                <h2 id="ovd-dist" className={styles.cardTitle}>
                  Overdue by days
                </h2>
                <p className={styles.cardSubtitle}>Distribution of overdue duration across active delayed bookings</p>
              </div>
            </div>
            {loading ? (
              <div className={styles.chartSkeleton} aria-hidden="true" />
            ) : (
              <HBars
                summary="Overdue bookings by number of days overdue"
                rows={DIST.map((d) => {
                  const count = data?.distribution?.[d.key] ?? 0
                  const pct = total > 0 ? Math.round((count / total) * 1000) / 10 : 0
                  return { label: d.label, value: count, max: total, pct, tone: d.tone }
                })}
              />
            )}
          </section>

          <section className={styles.card} aria-label="Overdue bookings">
            <div className={styles.filterBar}>
              <label className={styles.searchBox}>
                <Icon name="eye" size={15} />
                <span className="fb-sr-only">Search by booking ID, customer, or provider</span>
                <input
                  type="search"
                  value={search}
                  maxLength={SEARCH_MAX}
                  placeholder="Search by booking ID, customer, or provider…"
                  onChange={(event) => setSearch(event.target.value)}
                />
              </label>
              <span className="fb-sr-only" id="ovd-delay-label">
                Delay
              </span>
              <select
                aria-labelledby="ovd-delay-label"
                className={styles.filterSelect}
                value={delayBucket}
                onChange={(event) => update({ delayBucket: event.target.value, page: 1 })}
              >
                {DELAY_BUCKET_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <div className={styles.filterSpacer} />
            </div>

            {result.error && !loading ? (
              <PageError
                statusCode={result.error.status}
                title="Overdue bookings could not be loaded"
                message={result.error.message}
                onRetry={() => setReloadKey((k) => k + 1)}
              />
            ) : (
              <DataTable
                columns={columns}
                data={data?.rows?.content ?? []}
                actions={rowActions}
                loading={loading}
                loadingContent={<SkeletonTable rows={PAGE_SIZE} columns={6} />}
                emptyContent={
                  <EmptyState
                    variant={filtered ? 'search' : 'default'}
                    title={filtered ? 'No overdue bookings match' : 'No overdue bookings'}
                    message={filtered ? 'Try another search or clear the filters.' : 'Overdue bookings will appear here.'}
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
                  Showing {formatNumber(from)} to {formatNumber(to)} of {formatNumber(total)} overdue bookings
                </p>
                <Pagination
                  currentPage={page}
                  totalPages={data?.rows?.totalPages ?? 1}
                  onPageChange={(next) => update({ page: next })}
                  disabled={loading}
                />
              </div>
            )}
          </section>
        </>
      ) : (
        <section className={styles.card}>
          <UnavailableSection reason={section.reason} icon="clock" title="Overdue bookings are not available" />
        </section>
      )}

      {handling && (
        <ActionModal
          isOpen
          onClose={() => setHandling(null)}
          title="Handle overdue booking"
          facts={[
            { label: 'Booking', value: `#${handling.ref}` },
            { label: 'Service', value: handling.serviceTitle || '—' },
            { label: 'Customer', value: shortId(handling.customerId) },
            { label: 'Provider', value: shortId(handling.providerId) },
            { label: 'Days overdue', value: <DaysOverdueChip days={handling.daysOverdue} /> },
          ]}
          actions={actionOptions(CASE_ACTIONS)}
          onSubmit={submitAction}
        />
      )}
    </AdminPage>
  )
}
