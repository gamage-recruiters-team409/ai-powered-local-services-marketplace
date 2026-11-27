import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'

import { EmptyState, SkeletonTable } from '../../../shared/components/feedback'
import { Pagination } from '../../../shared/components/forms'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import { DisputeErrorState } from '../components/DisputeStates'
import { StatusBadge } from '../components/DisputeParts'
import { categoryLabel, DISPUTE_LIMITS, DISPUTE_STATUS, MY_DISPUTE_TABS, responderRoleOf } from '../config/disputeStatus'
import { fetchMyDisputes, isRequestCancelled } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'
import { formatDateTime, shortReference } from '../utils/format'
import styles from '../disputes.module.css'

const BASE_PATH = Object.freeze({ CUSTOMER: '/customer/disputes', PROVIDER: '/provider/disputes' })

function tabOf(id) {
  return MY_DISPUTE_TABS.find((tab) => tab.id === id) ?? MY_DISPUTE_TABS[0]
}

function pageOf(value) {
  const page = Number.parseInt(value ?? '1', 10)
  return Number.isFinite(page) && page >= 1 ? page : 1
}

/** The deadline that matters for this row, if any. */
function deadlineOf(dispute) {
  if (dispute.status === DISPUTE_STATUS.AWAITING_RESPONSE && dispute.responseDueAt) {
    return { label: 'Response due', at: dispute.responseDueAt }
  }
  if (dispute.status === DISPUTE_STATUS.RESOLVED && dispute.appealDeadline) {
    return { label: 'Appeal until', at: dispute.appealDeadline }
  }
  return null
}

/**
 * "My Disputes" for a customer or a provider (GET /disputes/my). Tab and page live in the URL (?tab=open&page=2),
 * so Back and refresh keep the view. Providers also get "Raise a dispute".
 */
export function MyDisputesPage({ role }) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const tab = tabOf(params.get('tab'))
  const page = pageOf(params.get('page'))
  const basePath = BASE_PATH[role]
  const [attempt, setAttempt] = useState(0)
  const requestKey = `${tab.id}#${page}#${attempt}`
  const [result, setResult] = useState({ key: null, data: null, error: null })

  useEffect(() => {
    const controller = new AbortController()
    fetchMyDisputes({ statuses: tab.statuses, page: page - 1, size: DISPUTE_LIMITS.PAGE_SIZE }, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key: requestKey, data, error: null })
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isRequestCancelled(error)) {
          setResult({ key: requestKey, data: null, error: parseApiError(error) })
        }
      })
    return () => controller.abort()
  }, [requestKey, tab.statuses, page])

  const loading = result.key !== requestKey
  if (!loading && result.error?.kind === 'unauthorized') {
    return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
  }

  function selectTab(id) {
    setParams(id === 'all' ? {} : { tab: id })
  }

  function goToPage(next) {
    const nextParams = {}
    if (tab.id !== 'all') nextParams.tab = tab.id
    if (next > 1) nextParams.page = String(next)
    setParams(nextParams)
  }

  const rows = result.data?.content ?? []
  const totalPages = Math.max(1, result.data?.totalPages ?? 1)

  return (
    <div className={styles.page}>
      <div className={styles.titleRow}>
        <div>
          <h1 className={styles.title}>My Disputes</h1>
          <p className={styles.muted}>
            {role === 'PROVIDER'
              ? 'Disputes raised by you or by your customers.'
              : 'Disputes you raised or were asked to respond to.'}
          </p>
        </div>
        {role === 'PROVIDER' && (
          <Link to={`${basePath}/new`} className={styles.primaryLink}>Raise a dispute</Link>
        )}
      </div>

      <div className={styles.tabs} role="tablist" aria-label="Filter disputes by status">
        {MY_DISPUTE_TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={item.id === tab.id}
            className={`${styles.tab} ${item.id === tab.id ? styles.tabActive : ''}`}
            onClick={() => selectTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <section className={styles.card} aria-live="polite" aria-busy={loading}>
        {loading && <SkeletonTable rows={4} />}
        {!loading && result.error && (
          <DisputeErrorState message={result.error.message} onRetry={() => setAttempt((value) => value + 1)} />
        )}
        {!loading && !result.error && rows.length === 0 && (
          <EmptyState
            title={tab.id === 'all' ? 'You have no disputes' : 'No disputes here'}
            message={tab.id === 'all'
              ? 'If something goes wrong with a booking, you can raise a dispute from the booking.'
              : 'No disputes match this filter.'}
            compact
          />
        )}
        {!loading && !result.error && rows.length > 0 && (
          <>
            <table className={styles.table}>
              <caption className={styles.srOnly}>Your disputes, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Case</th>
                  <th scope="col">Booking</th>
                  <th scope="col">Category</th>
                  <th scope="col">Raised by</th>
                  <th scope="col">Status</th>
                  <th scope="col">Raised</th>
                  <th scope="col">Deadline</th>
                  <th scope="col"><span className={styles.srOnly}>Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((dispute) => {
                  const responderRole = responderRoleOf(dispute)
                  const deadline = deadlineOf(dispute)
                  return (
                    <tr key={dispute.id}>
                      <td data-label="Case" className={styles.strong}>{shortReference('D', dispute.id)}</td>
                      <td data-label="Booking">{shortReference('B', dispute.bookingId)}</td>
                      <td data-label="Category">{categoryLabel(dispute.category)}</td>
                      <td data-label="Raised by">
                        {dispute.raisedByRole === role ? 'You' : (dispute.raisedByRole === 'CUSTOMER' ? 'Customer' : 'Provider')}
                      </td>
                      <td data-label="Status">
                        <StatusBadge
                          status={dispute.status}
                          viewerIsResponder={responderRole === role}
                          responderRole={responderRole}
                        />
                      </td>
                      <td data-label="Raised">{formatDateTime(dispute.createdAt)}</td>
                      <td data-label="Deadline">
                        {deadline ? <span>{deadline.label}: {formatDateTime(deadline.at)}</span> : <span aria-hidden="true">—</span>}
                      </td>
                      <td>
                        <Link
                          to={`${basePath}/${encodeURIComponent(dispute.id)}`}
                          className={styles.rowLink}
                          aria-label={`View dispute ${shortReference('D', dispute.id)}`}
                        >
                          View
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {totalPages > 1 && (
              <div className={styles.paginationRow}>
                <Pagination currentPage={page} totalPages={totalPages} onPageChange={goToPage} />
              </div>
            )}
          </>
        )}
      </section>
    </div>
  )
}
