import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { Badge, Button } from '../../../shared/components/forms'
import { EmptyState } from '../../../shared/components/feedback'

import CompletionIcon from '../components/CompletionIcons'
import ReviewsPageShell from '../components/ReviewsPageShell'
import { StarRatingDisplay } from '../components/StarRating'
import { reviewApi, reviewErrorMessage } from '../api'
import { formatDateTime, formatShortDate } from '../utils/completionUtils'
import {
  REVIEW_STATUS,
  canEditReview,
  editBlockedReason,
  editableUntil,
  reviewStatusBadge,
  tabCounts,
} from '../utils/reviewUtils'

const TABS = [
  { id: 'to-review', label: 'To review' },
  { id: 'reviews', label: 'My reviews' },
]

/**
 * S09 – Reviews_Customer_MyReviews (/customer/reviews).
 * Tab 1 "To review": completed bookings without a review (needs WP05 My Bookings for the real API).
 * Tab 2 "My reviews": the customer's reviews with status, Edit (48 h, before a provider reply) and the reply.
 */
export default function MyReviewsPage({ api = reviewApi, now: fixedNow }) {
  const navigate = useNavigate()
  const location = useLocation()
  const flash = location.state?.flash ?? null

  const [state, setState] = useState({ loading: true, error: null, toReview: [], reviews: [], bookings: {} })
  const [tab, setTab] = useState(location.state?.tab ?? null)

  const load = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const [reviews, toReview] = await Promise.all([api.getMyReviews(), api.getBookingsToReview()])
      const summaries = await Promise.all(reviews.map((review) => api.getBookingSummary(review.bookingId)))
      const bookings = {}
      reviews.forEach((review, i) => {
        bookings[review.bookingId] = summaries[i]
      })
      setState({ loading: false, error: null, toReview, reviews, bookings })
    } catch (error) {
      setState((prev) => ({ ...prev, loading: false, error: reviewErrorMessage(error) }))
    }
  }, [api])

  useEffect(() => {
    load()
  }, [load])

  const counts = tabCounts(state.toReview, state.reviews)
  // Default tab: "To review" when something is waiting, otherwise "My reviews".
  const activeTab = tab ?? (counts.toReview > 0 ? 'to-review' : 'reviews')
  const now = fixedNow ?? new Date()

  const onTabKeyDown = (event) => {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const next = activeTab === 'to-review' ? 'reviews' : 'to-review'
      setTab(next)
      document.getElementById(`rv-tab-${next}`)?.focus()
    }
  }

  return (
    <ReviewsPageShell title="My Reviews" subtitle="Review completed bookings and manage the reviews you've written.">
      {flash && (
        <p className="rv-flash" role="status">
          <CompletionIcon name="checkCircle" size={18} />
          {flash}
        </p>
      )}

      <div className="rv-tabs" role="tablist" aria-label="Reviews">
        {TABS.map((t) => (
          <button
            key={t.id}
            id={`rv-tab-${t.id}`}
            type="button"
            role="tab"
            aria-selected={activeTab === t.id}
            aria-controls={`rv-panel-${t.id}`}
            tabIndex={activeTab === t.id ? 0 : -1}
            className={activeTab === t.id ? 'rv-tab rv-tab--active' : 'rv-tab'}
            onClick={() => setTab(t.id)}
            onKeyDown={onTabKeyDown}
          >
            {t.label}
            <span className="rv-tab-count">{t.id === 'to-review' ? counts.toReview : counts.reviews}</span>
          </button>
        ))}
      </div>

      <div id={`rv-panel-${activeTab}`} role="tabpanel" aria-labelledby={`rv-tab-${activeTab}`} className="rv-panel">
        {state.loading && <p className="rv-muted">Loading your reviews…</p>}

        {!state.loading && state.error && (
          <div className="rv-error rv-error--box" role="alert">
            <p>{state.error}</p>
            <Button variant="outline" size="small" onClick={load}>
              Try again
            </Button>
          </div>
        )}

        {!state.loading && !state.error && activeTab === 'to-review' && (
          <ToReviewList
            bookings={state.toReview}
            supported={api.supportsToReview !== false}
            onLeaveReview={(booking) =>
              navigate(`/customer/reviews/new/${encodeURIComponent(booking.id)}`, { state: { booking } })
            }
          />
        )}

        {!state.loading && !state.error && activeTab === 'reviews' && (
          <ReviewList
            reviews={state.reviews}
            bookings={state.bookings}
            now={now}
            onEdit={(review) =>
              navigate(`/customer/reviews/${encodeURIComponent(review.id)}/edit`, {
                state: { review, booking: state.bookings[review.bookingId] },
              })
            }
            onBrowse={() => navigate('/search')}
          />
        )}
      </div>
    </ReviewsPageShell>
  )
}

function ToReviewList({ bookings, supported, onLeaveReview }) {
  if (!supported) {
    return (
      <div className="rv-note">
        <CompletionIcon name="info" size={18} />
        <p>Open a completed booking from My Bookings and choose “Leave a review”.</p>
      </div>
    )
  }
  if (bookings.length === 0) {
    return (
      <EmptyState
        title="You're all caught up"
        message="Completed bookings waiting for your review will appear here for 7 days."
        compact
      />
    )
  }
  return (
    <>
      <div className="rv-note rv-note--success">
        <CompletionIcon name="star" size={18} />
        <p>Your review helps other customers and gives the provider useful feedback.</p>
      </div>
      <ul className="rv-list">
        {bookings.map((booking) => (
          <li key={booking.id} className="rv-card rv-card--row">
            <span className="rv-booking-thumb" aria-hidden="true">
              <CompletionIcon name="image" size={20} />
            </span>
            <div className="rv-booking-text">
              <p className="rv-booking-title">{booking.title ?? `Booking ${booking.ref ?? booking.id}`}</p>
              <p className="rv-booking-meta">
                {[booking.providerName, booking.completedAt ? `Completed ${formatShortDate(booking.completedAt)}` : null]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            </div>
            <Button size="small" icon={<CompletionIcon name="star" size={16} />} onClick={() => onLeaveReview(booking)}>
              Leave a review
            </Button>
          </li>
        ))}
      </ul>
    </>
  )
}

function ReviewList({ reviews, bookings, now, onEdit, onBrowse }) {
  if (reviews.length === 0) {
    return (
      <EmptyState
        title="No reviews yet"
        message="After a booking is completed, you can rate the provider here."
        actionLabel="Browse services"
        onAction={onBrowse}
        compact
      />
    )
  }
  return (
    <ul className="rv-list">
      {reviews.map((review) => (
        <ReviewCard
          key={review.id}
          review={review}
          booking={bookings[review.bookingId]}
          now={now}
          onEdit={() => onEdit(review)}
        />
      ))}
    </ul>
  )
}

function ReviewCard({ review, booking, now, onEdit }) {
  const badge = reviewStatusBadge(review.status)
  const removed = review.status === REVIEW_STATUS.REMOVED
  const editable = canEditReview(review, now)
  const blocked = editBlockedReason(review, now)
  const title = booking?.title ?? `Booking ${booking?.ref ?? review.bookingId}`
  const meta = [booking?.providerName, `Posted ${formatShortDate(review.createdAt)}`].filter(Boolean).join(' · ')

  return (
    <li className={removed ? 'rv-card rv-card--removed' : 'rv-card'}>
      <div className="rv-card-head">
        <span className="rv-booking-thumb" aria-hidden="true">
          <CompletionIcon name="image" size={20} />
        </span>
        <div className="rv-booking-text">
          <p className="rv-booking-title">{title}</p>
          <p className="rv-booking-meta">{meta}</p>
        </div>
        <Badge variant={badge.variant} size="small">
          {badge.label}
        </Badge>
      </div>

      <div className="rv-card-body">
        <StarRatingDisplay value={review.rating} />
        {review.edited && <span className="rv-edited">Edited</span>}
        {removed ? (
          <>
            <p className="rv-muted">This review is no longer visible.</p>
            <p className="rv-removed">Removed by a moderator.</p>
          </>
        ) : (
          review.comment && <p className="rv-comment">“{review.comment}”</p>
        )}
        {!removed && review.status === REVIEW_STATUS.AWAITING_MODERATION && (
          <p className="rv-muted">Waiting for a moderator check.</p>
        )}
        {review.providerReply && !removed && (
          <div className="rv-reply">
            <p className="rv-reply-title">
              <CompletionIcon name="message" size={16} /> Provider reply
            </p>
            <p>{review.providerReply.text}</p>
          </div>
        )}
      </div>

      {!removed && (editable || blocked) && (
        <div className="rv-card-foot">
          {editable ? (
            <>
              <span className="rv-muted rv-foot-text">
                <CompletionIcon name="clock" size={16} /> Editable until {formatDateTime(editableUntil(review))}
              </span>
              <Button variant="outline" size="small" onClick={onEdit} aria-label={`Edit review for ${title}`}>
                Edit
              </Button>
            </>
          ) : (
            <span className="rv-muted rv-foot-text">{blocked}</span>
          )}
        </div>
      )}
    </li>
  )
}
