import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { EmptyState, PageError, SkeletonTable, useToast } from '../../../shared/components/feedback'
import { Button, DataTable, Pagination } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { useDebouncedValue } from '../../admin-announcements/hooks/useDebouncedValue'
import { HBars, Legend } from '../components/Charts'
import { ActionModal } from '../components/ActionModal'
import { CaseStatusChip, Chip, RoleChip } from '../components/Chips'
import { SummaryCards } from '../components/SummaryCards'
import { UnavailableSection } from '../components/Placeholder'
import {
  ACTION_LABEL,
  actionOptions,
  PAGE_SIZE,
  repeatActionsForArea,
  ROLE_OPTIONS,
  SEARCH_MAX,
} from '../config/operationsConfig'
import {
  exportRepeatCsv,
  getRepeatCancellations,
  isRequestCancelled,
  recordRepeatAction,
} from '../services/operationsService'
import { downloadBlob } from '../utils/download'
import { parseApiError } from '../utils/errors'
import { formatDateTime, formatNumber, shortId } from '../utils/format'
import styles from '../operations.module.css'

const ROLES = new Set(ROLE_OPTIONS.map((o) => o.value))

function readParams(params) {
  const role = ROLES.has(params.get('role') ?? '') ? params.get('role') ?? '' : ''
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  return { q: (params.get('q') ?? '').slice(0, SEARCH_MAX), role, page }
}

function noteForAction(action) {
  if (action === 'SUSPEND') {
    return 'Suspension stays until an admin reactivates the account. There is no timed suspension and no account ban.'
  }
  if (action === 'RECOMMEND_SUSPENSION') {
    return 'Recorded for an Admin to review — this does not suspend the account.'
  }
  return null
}

export default function RepeatCancellationsPage() {
  const { area, isViewOnly } = useAreaAccess() ?? {}
  const canEdit = !isViewOnly
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const { q, role, page } = readParams(params)
  const [search, setSearch] = useState(q)
  const debounced = useDebouncedValue(search.trim(), 300)
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, section: null, error: null })
  const [exporting, setExporting] = useState(false)
  const [acting, setActing] = useState(null)

  const update = (changes) => {
    const next = { q, role, page, ...changes }
    const query = {}
    if (next.q) query.q = next.q
    if (next.role) query.role = next.role
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

  const requestKey = `${q}|${role}|${page}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    getRepeatCancellations({ search: q || undefined, role: role || undefined, page: page - 1, size: PAGE_SIZE }, controller.signal)
      .then((section) => setResult({ key: requestKey, section, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, section: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [q, role, page, requestKey])

  const exportCsv = async () => {
    setExporting(true)
    try {
      const { blob, filename } = await exportRepeatCsv({ search: q || undefined, role: role || undefined })
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
  const filtered = Boolean(q || role)
  const actions = actionOptions(repeatActionsForArea(area))

  const columns = useMemo(
    () => [
      { key: 'userId', header: 'User', render: (v) => <span className={styles.refId} title={v}>{shortId(v)}</span> },
      { key: 'role', header: 'Role', render: (v) => <RoleChip role={v} /> },
      { key: 'count', header: 'Cancellations', render: (v) => formatNumber(v) },
      { key: 'lastCancelledAt', header: 'Last cancelled', render: (v) => formatDateTime(v) },
      { key: 'caseStatus', header: 'Status', render: (v) => <CaseStatusChip status={v} /> },
    ],
    [],
  )

  const rowActions = canEdit
    ? (r) => (
        <Button variant="outline" size="small" onClick={() => setActing(r)}>
          Take action
        </Button>
      )
    : undefined

  const submitAction = async (action, note) => {
    try {
      await recordRepeatAction(acting.userId, { action, note })
    } catch (err) {
      if (parseApiError(err).code === 'CONCURRENT_MODIFICATION') setReloadKey((k) => k + 1)
      throw err
    }
    setActing(null)
    toast.success(`${ACTION_LABEL[action] ?? 'Action'} recorded for ${shortId(acting.userId)}.`)
    setReloadKey((k) => k + 1)
  }

  const thresholdChip = data ? (
    <Chip variant="neutral" plain>
      Threshold: &gt;{data.thresholdMinCount} cancellations / {data.thresholdDays}d
    </Chip>
  ) : null

  return (
    <AdminPage
      title="Repeat cancellations"
      subtitle="Monitor and act on accounts with excessive cancellation rates."
      breadcrumbs={['Operations', 'Repeat cancellations']}
      actions={
        <>
          {thresholdChip}
          <Button variant="outline" onClick={exportCsv} loading={exporting} disabled={exporting} icon={<Icon name="fileText" size={15} />}>
            Export log
          </Button>
        </>
      }
      plain
    >
      {!section || section.available ? (
        <>
          <SummaryCards
            loading={loading}
            cards={[
              { label: 'Flagged users', value: data?.summary?.flagged, icon: 'alertCircle', tone: 'danger', foot: 'Exceeded threshold in window' },
              { label: 'Customers', value: data?.summary?.customers, icon: 'users', foot: 'Flagged customers' },
              { label: 'Providers', value: data?.summary?.providers, icon: 'shield', foot: 'Flagged providers' },
            ]}
          />

          <section className={styles.card} aria-labelledby="repeat-top">
            <div className={styles.cardHead}>
              <div>
                <h2 id="repeat-top" className={styles.cardTitle}>
                  Cancellations in the window
                </h2>
                <p className={styles.cardSubtitle}>Top 5 highest cancellation counts</p>
              </div>
              <Legend items={[{ label: 'Customer', tone: 'danger' }, { label: 'Provider', tone: 'dark' }]} />
            </div>
            {loading ? (
              <div className={styles.chartSkeleton} aria-hidden="true" />
            ) : (data?.top5 ?? []).length > 0 ? (
              <HBars
                summary="Top 5 users by cancellations in the window"
                rows={data.top5.map((r) => ({
                  label: shortId(r.userId),
                  sublabel: <RoleChip role={r.role} />,
                  value: r.count,
                  tone: r.role === 'PROVIDER' ? 'dark' : 'danger',
                }))}
              />
            ) : (
              <p className={styles.subtle}>No flagged users in this window.</p>
            )}
          </section>

          {canEdit && area === 'ADMIN' && (data?.suspensionRecommendations ?? []).length > 0 && (
            <section className={styles.card} aria-labelledby="repeat-reco">
              <div className={styles.cardHead}>
                <div>
                  <h2 id="repeat-reco" className={styles.cardTitle}>
                    Pending suspension recommendations
                  </h2>
                  <p className={styles.cardSubtitle}>Recommended by Support for an Admin decision</p>
                </div>
              </div>
              <ul className={styles.recoList}>
                {data.suspensionRecommendations.map((rec) => (
                  <li key={rec.userId} className={styles.recoItem}>
                    <div className={styles.recoHead}>
                      <span>{shortId(rec.userId)}</span>
                      <span className={styles.subtle}>
                        by {shortId(rec.recommendedByActorId)} · {formatDateTime(rec.recommendedAt)}
                      </span>
                    </div>
                    <p className={styles.recoNote}>{rec.note}</p>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className={styles.card} aria-label="Flagged accounts">
            <div className={styles.filterBar}>
              <label className={styles.searchBox}>
                <Icon name="eye" size={15} />
                <span className="fb-sr-only">Search accounts</span>
                <input
                  type="search"
                  value={search}
                  maxLength={SEARCH_MAX}
                  placeholder="Search accounts…"
                  onChange={(event) => setSearch(event.target.value)}
                />
              </label>
              <span className="fb-sr-only" id="repeat-role-label">
                Role
              </span>
              <select
                aria-labelledby="repeat-role-label"
                className={styles.filterSelect}
                value={role}
                onChange={(event) => update({ role: event.target.value, page: 1 })}
              >
                {ROLE_OPTIONS.map((o) => (
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
                title="Repeat cancellations could not be loaded"
                message={result.error.message}
                onRetry={() => setReloadKey((k) => k + 1)}
              />
            ) : (
              <DataTable
                columns={columns}
                data={data?.rows?.content ?? []}
                actions={rowActions}
                loading={loading}
                loadingContent={<SkeletonTable rows={PAGE_SIZE} columns={5} />}
                emptyContent={
                  <EmptyState
                    variant={filtered ? 'search' : 'default'}
                    title={filtered ? 'No accounts match' : 'No flagged accounts'}
                    message={filtered ? 'Try another search or clear the filters.' : 'Accounts over the threshold will appear here.'}
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
                  Showing {formatNumber(from)} to {formatNumber(to)} of {formatNumber(total)} flagged accounts
                </p>
                <Pagination currentPage={page} totalPages={data?.rows?.totalPages ?? 1} onPageChange={(next) => update({ page: next })} disabled={loading} />
              </div>
            )}
          </section>
        </>
      ) : (
        <section className={styles.card}>
          <UnavailableSection reason={section.reason} icon="xCircle" title="Repeat cancellations are not available" />
        </section>
      )}

      {acting && (
        <ActionModal
          isOpen
          onClose={() => setActing(null)}
          title={`Take action: ${shortId(acting.userId)}`}
          facts={[
            { label: 'User', value: shortId(acting.userId) },
            { label: 'Role', value: <RoleChip role={acting.role} /> },
            { label: 'Cancellations', value: formatNumber(acting.count) },
          ]}
          actions={actions}
          onSubmit={submitAction}
          submitLabel="Apply"
          noteForAction={noteForAction}
        />
      )}
    </AdminPage>
  )
}
