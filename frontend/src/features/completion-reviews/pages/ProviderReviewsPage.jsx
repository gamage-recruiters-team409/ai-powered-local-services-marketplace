import { useCallback, useEffect, useMemo, useState } from 'react'

import { Badge, Button } from '../../../shared/components/forms'
import { EmptyState } from '../../../shared/components/feedback'
import { ProviderPage } from '../../../shared/layouts/provider'

import CompletionIcon from '../components/CompletionIcons'
import ReplyForm from '../components/ReplyForm'
import { StarRatingDisplay } from '../components/StarRating'
import { reviewApi, reviewErrorMessage } from '../api'
import { formatShortDate } from '../utils/completionUtils'
import {
  RATING_FILTERS,
  REVIEW_STATUS,
  SORT_OPTIONS,
  filterProviderReviews,
  initials,
  needsReply,
  notRepliedCount,
  ratingDistribution,
  reviewStatusBadge,
} from '../utils/reviewUtils'
import '../styles/completion.css'
import '../styles/reviews.css'

const EMPTY_SUMMARY = { averageRating: 0, reviewCount: 0, ratingCounts: {} }

/**
 * S11 – Reviews_Provider_MyReviews (/provider/reviews), inside the Provider Common Layout.
 * Stat cards, rating distribution (published reviews only), filters and review cards
 * with the inline S12 reply form. Providers cannot delete reviews (BR-012).
 */
export default function ProviderReviewsPage({ api = reviewApi }) {
  const [state, setState] = useState({ loading: true, error: null, reviews: [], summary: EMPTY_SUMMARY })
  const [filters, setFilters] = useState({ rating: 'all', sort: 'newest', notReplied: false })
  const [replyingTo, setReplyingTo] = useState(null)
  const [replyError, setReplyError] = useState(null)
  const [flash, setFlash] = useState(null)

  const load = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const reviews = await api.getProviderReviews()
      // The summary endpoint needs the provider ID; every review carries it.
      const summary = reviews.length > 0 ? await api.getRatingSummary(reviews[0].providerId) : EMPTY_SUMMARY
      setState({ loading: false, error: null, reviews, summary: summary ?? EMPTY_SUMMARY })
    } catch (error) {
      setState((prev) => ({ ...prev, loading: false, error: reviewErrorMessage(error) }))
    }
  }, [api])

  useEffect(() => {
    load()
  }, [load])

  const visible = useMemo(() => filterProviderReviews(state.reviews, filters), [state.reviews, filters])

  const submitReply = async (review, text) => {
    setReplyError(null)
    try {
      const updated = await api.replyToReview(review.id, { text })
      setState((prev) => ({
        ...prev,
        reviews: prev.reviews.map((r) => (r.id === review.id ? { ...r, ...updated } : r)),
      }))
      setReplyingTo(null)
      setFlash('Your reply has been posted.')
    } catch (error) {
      setReplyError(reviewErrorMessage(error))
    }
  }

  return (
    <ProviderPage title="My Reviews" subtitle="See what customers say about your services and reply to them." plain>
      <div className="rv-provider">
        {state.loading && <p className="rv-muted">Loading your reviews…</p>}

        {!state.loading && state.error && (
          <div className="rv-error rv-error--box" role="alert">
            <p>{state.error}</p>
            <Button variant="outline" size="small" onClick={load}>
              Try again
            </Button>
          </div>
        )}

        {!state.loading && !state.error && (
          <>
            <div className="rv-stats">
              <StatCard label="Average rating" value={state.summary.averageRating.toFixed(1)} />
              <StatCard label="Total reviews" value={state.summary.reviewCount} />
              <StatCard
                label="Not replied"
                value={notRepliedCount(state.reviews)}
                hint={notRepliedCount(state.reviews) > 0 ? 'Reply to build trust' : null}
              />
            </div>

            <RatingSummary summary={state.summary} />

            {flash && (
              <p className="rv-flash" role="status">
                <CompletionIcon name="checkCircle" size={18} />
                {flash}
              </p>
            )}

            <div className="rv-filters" role="group" aria-label="Filter reviews">
              <label className="rv-chip">
                <span className="rv-visually-hidden">Rating</span>
                <select
                  value={filters.rating}
                  onChange={(event) => setFilters((f) => ({ ...f, rating: event.target.value }))}
                >
                  {RATING_FILTERS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="rv-chip">
                <span className="rv-visually-hidden">Sort</span>
                <select
                  value={filters.sort}
                  onChange={(event) => setFilters((f) => ({ ...f, sort: event.target.value }))}
                >
                  {SORT_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className={filters.notReplied ? 'rv-chip rv-chip--on' : 'rv-chip'}
                aria-pressed={filters.notReplied}
                onClick={() => setFilters((f) => ({ ...f, notReplied: !f.notReplied }))}
              >
                Not replied
              </button>
            </div>

            {state.reviews.length === 0 ? (
              <EmptyState
                title="No reviews yet"
                message="Reviews from customers appear here after their bookings are completed."
                compact
              />
            ) : visible.length === 0 ? (
              <EmptyState variant="search" title="No matching reviews" message="Try a different filter." compact />
            ) : (
              <ul className="rv-list rv-list--panel">
                {visible.map((review) => (
                  <ProviderReviewCard
                    key={review.id}
                    review={review}
                    replying={replyingTo === review.id}
                    replyError={replyingTo === review.id ? replyError : null}
                    onReply={() => {
                      setFlash(null)
                      setReplyError(null)
                      setReplyingTo(review.id)
                    }}
                    onCancelReply={() => setReplyingTo(null)}
                    onSubmitReply={(text) => submitReply(review, text)}
                  />
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </ProviderPage>
  )
}

function StatCard({ label, value, hint }) {
  return (
    <div className="rv-stat">
      <p className="rv-stat-label">{label}</p>
      <p className="rv-stat-value">{value}</p>
      {hint && <p className="rv-stat-hint">{hint}</p>}
    </div>
  )
}

function RatingSummary({ summary }) {
  const rows = ratingDistribution(summary)
  return (
    <section className="rv-summary" aria-label="Rating summary">
      <div className="rv-summary-score">
        <p className="rv-summary-average">{summary.averageRating.toFixed(1)}</p>
        <StarRatingDisplay value={Math.round(summary.averageRating)} size={14} />
        <p className="rv-muted">
          {summary.reviewCount} review{summary.reviewCount === 1 ? '' : 's'}
        </p>
      </div>
      <ul className="rv-bars">
        {rows.map((row) => (
          <li key={row.stars} className="rv-bar-row">
            <span className="rv-bar-label">{row.stars} ★</span>
            <span
              className="rv-bar"
              role="meter"
              aria-label={`${row.stars} star reviews`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={row.percent}
              aria-valuetext={`${row.count} reviews, ${row.percent}%`}
            >
              <span className="rv-bar-fill" style={{ width: `${row.percent}%` }} />
            </span>
            <span className="rv-bar-percent">{row.percent}%</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function ProviderReviewCard({ review, replying, replyError, onReply, onCancelReply, onSubmitReply }) {
  const removed = review.status === REVIEW_STATUS.REMOVED
  const name = review.customerName ?? 'Customer'
  const meta = [formatShortDate(review.createdAt), review.serviceTitle].filter(Boolean).join(' · ')
  const badge = reviewStatusBadge(review.status)

  return (
    <li className={removed ? 'rv-pcard rv-card--removed' : 'rv-pcard'}>
      <div className="rv-card-head">
        <span className="rv-avatar" aria-hidden="true">
          {initials(review.customerName)}
        </span>
        <div className="rv-booking-text">
          <p className="rv-booking-title">
            {name}
            {review.edited && <span className="rv-edited">Edited</span>}
          </p>
          <p className="rv-booking-meta">{meta}</p>
        </div>
        {review.status === REVIEW_STATUS.PUBLISHED ? (
          <Badge variant="success" size="small" icon={<CompletionIcon name="check" size={12} />}>
            Verified booking
          </Badge>
        ) : (
          <Badge variant={badge.variant} size="small">
            {badge.label}
          </Badge>
        )}
      </div>

      <div className="rv-provider-body">
        <StarRatingDisplay value={review.rating} />
        {removed ? (
          <p className="rv-muted">Removed by a moderator. It is not shown to customers.</p>
        ) : (
          review.comment && <p className="rv-comment">{review.comment}</p>
        )}

        {review.providerReply && !removed && (
          <div className="rv-reply">
            <p className="rv-reply-title">
              <CompletionIcon name="message" size={16} /> Your reply
            </p>
            <p>{review.providerReply.text}</p>
          </div>
        )}

        {replying ? (
          <ReplyForm reviewId={review.id} onCancel={onCancelReply} onSubmit={onSubmitReply} serverError={replyError} />
        ) : (
          needsReply(review) && (
            <Button
              variant="outline"
              size="small"
              className="rv-reply-btn"
              icon={<CompletionIcon name="message" size={14} />}
              onClick={onReply}
              aria-label={`Reply to ${name}`}
            >
              Reply
            </Button>
          )
        )}
      </div>
    </li>
  )
}
