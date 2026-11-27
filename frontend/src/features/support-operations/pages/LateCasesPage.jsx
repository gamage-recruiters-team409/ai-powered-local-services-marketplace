import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { EmptyState, PageError, SkeletonTable, useToast } from '../../../shared/components/feedback'
import { Button, DataTable, Pagination } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { useDebouncedValue } from '../../admin-announcements/hooks/useDebouncedValue'
import { OpsBarChart } from '../components/Charts'
import { ActionModal } from '../components/ActionModal'
import { CaseStatusChip, DelayChip, SourceTag, UrgencyChip } from '../components/Chips'
import { SummaryCards } from '../components/SummaryCards'
import { UnavailableSection } from '../components/Placeholder'
import {
  ACTION_LABEL,
  actionOptions,
  CASE_ACTIONS,
  PAGE_SIZE,
  SEARCH_MAX,
  URGENCY_OPTIONS,
} from '../config/operationsConfig'
import { exportLateCasesCsv, getLateCases, isRequestCancelled, recordLateCaseAction } from '../services/operationsService'
import { downloadBlob } from '../utils/download'
import { parseApiError } from '../utils/errors'
import { formatDateTime, formatNumber, shortId } from '../utils/format'
import styles from '../operations.module.css'

const URGENCIES = new Set(URGENCY_OPTIONS.map((o) => o.value))
const DAY_SERIES = [{ key: 'count', label: 'Late cases', tone: 'primary' }]

function readParams(params) {
  const urgency = URGENCIES.has(params.get('urgency') ?? '') ? params.get('urgency') ?? '' : ''
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  return { q: (params.get('q') ?? '').slice(0, SEARCH_MAX), urgency, page }
}

function dayLabel(iso, index, total) {
  if (index === total - 1) return 'Today'
  return `D${index + 1}`
}

export default function LateCasesPage() {
  const { isViewOnly } = useAreaAccess() ?? {}
  const canEdit = !isViewOnly
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const { q, urgency, page } = readParams(params)
  const [search, setSearch] = useState(q)
  const debounced = useDebouncedValue(search.trim(), 300)
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const [exporting, setExporting] = useState(false)
  const [handling, setHandling] = useState(null)

  const update = (changes) => {
    const next = { q, urgency, page, ...changes }
    const query = {}
    if (next.q) query.q = next.q
    if (next.urgency) query.urgency = next.urgency
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

  const requestKey = `${q}|${urgency}|${page}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    getLateCases({ search: q || undefined, urgency: urgency || undefined, page: page - 1, size: PAGE_SIZE }, controller.signal)
      .then((data) => setResult({ key: requestKey, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [q, urgency, page, requestKey])

  const exportCsv = async () => {
    setExporting(true)
    try {
      const { blob, filename, sourceLimited, truncated } = await exportLateCasesCsv({ search: q || undefined })
      downloadBlob(blob, filename)
      if (sourceLimited) {
        toast.warning(`Exported ${filename}. Note: Data source limit reached; some records may be omitted.`)
      } else if (truncated) {
        toast.warning(`Exported ${filename}. Note: Export capped at maximum export limit.`)
      } else {
        toast.success(`Exported ${filename}.`)
      }
    } catch (error) {
      toast.error(parseApiError(error).message)
    } finally {
      setExporting(false)
    }
  }

  const response = loading ? null : result.data
  const lateStarts = response?.lateStarts
  const noShow = response?.noShowReports
  const lateAvailable = Boolean(lateStarts?.available)
  const lateData = lateAvailable ? lateStarts.data : null
  const noShowData = noShow?.available ? noShow.data : null

  const lateRows = lateData?.rows?.content ?? []
  const noShowRows = noShowData?.rows?.content ?? []
  // One page param: drives the late-starts table when available, otherwise the no-show table.
  const activeRows = lateAvailable ? lateData?.rows : noShowData?.rows
  const total = activeRows?.totalElements ?? 0
  const from = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1
  const to = Math.min(page * PAGE_SIZE, total)
  const filtered = Boolean(q || urgency)

  const lateColumns = useMemo(
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
      { key: 'scheduledStart', header: 'Scheduled time', render: (v) => formatDateTime(v) },
      { key: 'minutesLate', header: 'Delay', render: (v) => <DelayChip minutesLate={v} /> },
      { key: 'urgency', header: 'Urgency', render: (v) => <UrgencyChip urgency={v} /> },
      { key: 'caseStatus', header: 'Status', render: (v) => <CaseStatusChip status={v} /> },
    ],
    [],
  )

  const noShowColumns = useMemo(
    () => [
      {
        key: 'disputeId',
        header: 'Dispute',
        render: (v, r) => (
          <div className={styles.refCell}>
            <span className={styles.refId}>
              {shortId(v)}
              <SourceTag />
            </span>
            <span className={styles.subtle}>Booking #{shortId(r.bookingId)}</span>
          </div>
        ),
      },
      { key: 'customerId', header: 'Customer', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'providerId', header: 'Provider', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'status', header: 'Dispute status', render: (v) => <CaseStatusChip status={v} /> },
      { key: 'createdAt', header: 'Raised', render: (v) => formatDateTime(v) },
    ],
    [],
  )

  const lateActions = canEdit
    ? (r) => (
        <Button variant="outline" size="small" onClick={() => setHandling(r)}>
          Handle
        </Button>
      )
    : undefined

  const submitAction = async (action, note) => {
    await recordLateCaseAction(handling.bookingId, { action, note })
    setHandling(null)
    toast.success(`${ACTION_LABEL[action] ?? 'Action'} recorded for #${handling.ref}.`)
    setReloadKey((k) => k + 1)
  }

  return (
    <AdminPage
      title="Late cases"
      subtitle="Track bookings past scheduled time and initiate operational intervention."
      breadcrumbs={['Operations', 'Late cases']}
      actions={
        <>
          <Button variant="outline" onClick={() => setReloadKey((k) => k + 1)} icon={<Icon name="switch" size={15} />}>
            Refresh cases
          </Button>
          <Button variant="outline" onClick={exportCsv} loading={exporting} disabled={exporting} icon={<Icon name="fileText" size={15} />}>
            Export
          </Button>
        </>
      }
      plain
    >
      <SummaryCards
        loading={loading}
        cards={[
          { label: 'Open late cases', value: lateData?.summary?.open, available: lateAvailable, reason: lateStarts?.reason, icon: 'clock' },
          { label: 'Critical (>4h)', value: lateData?.summary?.critical, available: lateAvailable, reason: lateStarts?.reason, icon: 'alertCircle', tone: 'danger' },
          { label: 'Resolved today', value: lateData?.summary?.resolvedToday, available: lateAvailable, reason: lateStarts?.reason, icon: 'check' },
        ]}
      />

      <section className={styles.card} aria-labelledby="late-chart">
        <div className={styles.cardHead}>
          <div>
            <h2 id="late-chart" className={styles.cardTitle}>
              Late cases per day
            </h2>
            <p className={styles.cardSubtitle}>Daily incident trend for the last 14 days</p>
          </div>
        </div>
        {loading ? (
          <div className={styles.chartSkeleton} aria-hidden="true" />
        ) : lateAvailable ? (
          <OpsBarChart
            highlightLast
            showValues
            series={DAY_SERIES}
            summary="Late cases per day for the last 14 days"
            caption="Late cases per day"
            data={(lateData?.perDay ?? []).map((d, index, arr) => ({
              label: dayLabel(d.date, index, arr.length),
              title: d.date,
              values: { count: d.count },
            }))}
          />
        ) : (
          <UnavailableSection reason={lateStarts?.reason} icon="calendar" />
        )}
      </section>

      <section className={styles.card} aria-label="Late starts">
        <div className={styles.filterBar}>
          <label className={styles.searchBox}>
            <Icon name="eye" size={15} />
            <span className="fb-sr-only">Search booking ID, customer, or provider</span>
            <input
              type="search"
              value={search}
              maxLength={SEARCH_MAX}
              placeholder="Search booking ID, customer, or provider…"
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <span className="fb-sr-only" id="late-urgency-label">
            Urgency
          </span>
          <select
            aria-labelledby="late-urgency-label"
            className={styles.filterSelect}
            value={urgency}
            disabled={!lateAvailable}
            onChange={(event) => update({ urgency: event.target.value, page: 1 })}
          >
            {URGENCY_OPTIONS.map((o) => (
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
            title="Late cases could not be loaded"
            message={result.error.message}
            onRetry={() => setReloadKey((k) => k + 1)}
          />
        ) : lateAvailable ? (
          <DataTable
            columns={lateColumns}
            data={lateRows}
            actions={lateActions}
            loading={loading}
            loadingContent={<SkeletonTable rows={PAGE_SIZE} columns={7} />}
            emptyContent={
              <EmptyState variant={filtered ? 'search' : 'default'} title="No late cases" message="Late-start cases will appear here." compact />
            }
          />
        ) : (
          <UnavailableSection reason={lateStarts?.reason} icon="calendar" title="Late-start tracking is not available yet" />
        )}
      </section>

      <section className={styles.card} aria-labelledby="noshow-title">
        <div className={styles.cardHead}>
          <div>
            <h2 id="noshow-title" className={styles.cardTitle}>
              No-show reports
            </h2>
            <p className={styles.cardSubtitle}>
              NO_SHOW disputes with their current dispute status
              {noShowData && <> · {formatNumber(noShowData.counts.total)} total</>}
            </p>
          </div>
        </div>
        {!noShow || noShow.available ? (
          <DataTable
            columns={noShowColumns}
            data={noShowRows}
            loading={loading}
            loadingContent={<SkeletonTable rows={5} columns={5} />}
            emptyContent={<EmptyState title="No no-show reports" message="NO_SHOW disputes will appear here." compact />}
          />
        ) : (
          <UnavailableSection reason={noShow.reason} icon="alertCircle" />
        )}
      </section>

      {!result.error && total > 0 && (
        <div className={styles.paginationRow}>
          <p className={styles.subtle} aria-live="polite">
            Showing {formatNumber(from)} to {formatNumber(to)} of {formatNumber(total)} {lateAvailable ? 'late cases' : 'no-show reports'}
          </p>
          <Pagination currentPage={page} totalPages={activeRows?.totalPages ?? 1} onPageChange={(next) => update({ page: next })} disabled={loading} />
        </div>
      )}

      {handling && (
        <ActionModal
          isOpen
          onClose={() => setHandling(null)}
          title="Handle late case"
          facts={[
            { label: 'Booking', value: `#${handling.ref}` },
            { label: 'Provider', value: shortId(handling.providerId) },
            { label: 'Scheduled start', value: formatDateTime(handling.scheduledStart) },
            { label: 'Minutes late', value: <DelayChip minutesLate={handling.minutesLate} /> },
          ]}
          actions={actionOptions(CASE_ACTIONS)}
          onSubmit={submitAction}
        />
      )}
    </AdminPage>
  )
}
