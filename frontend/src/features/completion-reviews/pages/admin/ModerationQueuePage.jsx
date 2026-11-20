import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { Badge, DataTable, Pagination } from '../../../../shared/components/forms'
import { EmptyState } from '../../../../shared/components/feedback'
import { AdminPage } from '../../../../shared/layouts/admin'

import { StarRatingDisplay } from '../../components/StarRating'
import { moderationApi } from '../../api'
import { formatShortDate } from '../../utils/completionUtils'
import { QUEUE_FILTERS, excerpt, flagLabel, moderationErrorMessage, queueSource } from '../../utils/moderationUtils'

const PAGE_SIZE = 10

function SourceBadges({ review }) {
  if (queueSource(review) === 'REPORTED') {
    return <Badge variant="error" size="small">{review.openReportCount} {review.openReportCount === 1 ? 'report' : 'reports'}</Badge>
  }
  return (
    <span className="rm-badges">
      {(review.moderationFlags?.length ? review.moderationFlags : ['HELD']).map((flag) => (
        <Badge key={flag} variant="warning" size="small">{flag === 'HELD' ? 'Held' : flagLabel(flag)}</Badge>
      ))}
    </span>
  )
}

const COLUMNS = [
  {
    key: 'comment',
    header: 'Review',
    render: (_, review) => (
      <div className="rm-review-cell">
        <StarRatingDisplay value={review.rating} size={14} />
        <span className="rm-excerpt">{excerpt(review.comment) || <em>No comment</em>}</span>
        <span className="rm-muted">by {review.customerName ?? 'Customer'}</span>
      </div>
    ),
  },
  { key: 'providerName', header: 'Provider', render: (value) => value ?? '—' },
  { key: 'source', header: 'Why', render: (_, review) => <SourceBadges review={review} /> },
  { key: 'createdAt', header: 'Submitted', render: (value) => formatShortDate(value) },
  {
    key: 'id',
    header: <span className="sr-only">Open</span>,
    render: (value, review) => (
      <Link className="rm-open-link" to={value} aria-label={`Review by ${review.customerName ?? 'customer'}`}>
        Review ›
      </Link>
    ),
  },
]

/**
 * S14 – Reviews_Admin_ModerationQueue (/admin/moderator/moderation/reviews).
 * Held reviews (automatic checks) and published reviews with open user reports, oldest first.
 */
export default function ModerationQueuePage({ api = moderationApi }) {
  const [params, setParams] = useSearchParams()
  const filter = QUEUE_FILTERS.some((f) => f.value === params.get('filter')) ? params.get('filter') : 'ALL'
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const requestKey = `${filter}:${page}`
  // Result of the last finished request; loading = no result yet for the current filter and page
  const [result, setResult] = useState({ key: null, data: null, error: null })

  useEffect(() => {
    let active = true
    api
      .getQueue({ filter, page, size: PAGE_SIZE })
      .then((data) => active && setResult({ key: requestKey, data, error: null }))
      .catch((error) => active && setResult({ key: requestKey, data: null, error: moderationErrorMessage(error) }))
    return () => {
      active = false
    }
  }, [api, filter, page, requestKey])

  const loading = result.key !== requestKey
  const state = { loading, error: loading ? null : result.error, data: loading ? null : result.data }

  const change = (next) => setParams(Object.fromEntries(Object.entries({ filter, page: 0, ...next }).filter(([, v]) => v !== 'ALL' && v !== 0)))
  const total = state.data?.totalElements ?? 0

  return (
    <AdminPage
      title="Review moderation"
      subtitle="Reviews held by the automatic checks and published reviews reported by users. A moderator makes the final decision."
      breadcrumbs={['Moderation', 'Review moderation']}
      plain
    >
      <div className="rm-tabs" role="tablist" aria-label="Queue filter">
        {QUEUE_FILTERS.map((option) => (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={filter === option.value}
            className={filter === option.value ? 'rm-tab rm-tab--active' : 'rm-tab'}
            onClick={() => change({ filter: option.value })}
          >
            {option.label}
            {filter === option.value && !state.loading && state.data && <span className="rm-tab-count">{total}</span>}
          </button>
        ))}
      </div>

      <DataTable
        columns={COLUMNS}
        data={state.data?.content ?? []}
        getRowId={(review) => review.id}
        loading={state.loading}
        error={state.error}
        emptyContent={<EmptyState title="All caught up!" message="There are no reviews waiting for a decision." />}
        pagination={
          (state.data?.totalPages ?? 0) > 1 ? (
            <Pagination currentPage={page + 1} totalPages={state.data.totalPages} onPageChange={(next) => change({ page: next - 1 })} />
          ) : null
        }
      />
    </AdminPage>
  )
}
