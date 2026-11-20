import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'

import { Button } from '../../../shared/components/forms'

import CompletionIcon from '../components/CompletionIcons'
import ReviewBookingCard from '../components/ReviewBookingCard'
import ReviewForm from '../components/ReviewForm'
import ReviewsPageShell from '../components/ReviewsPageShell'
import { reviewApi, reviewErrorMessage } from '../api'
import { formatDateTime } from '../utils/completionUtils'
import { canEditReview, editBlockedReason, editableUntil } from '../utils/reviewUtils'

/**
 * S08 – Reviews_Customer_EditReview (/customer/reviews/:reviewId/edit).
 * Opened from S09 "Edit". The review comes through router state; on a direct visit it is found
 * in the customer's own reviews (there is no single-review endpoint).
 */
export default function EditReviewPage({ api = reviewApi, now: fixedNow }) {
  const { reviewId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [review, setReview] = useState(location.state?.review ?? null)
  const [booking, setBooking] = useState(location.state?.booking ?? null)
  const [loadError, setLoadError] = useState(null)
  const [serverError, setServerError] = useState(null)

  useEffect(() => {
    if (review) {
      return undefined
    }
    let active = true
    api
      .getMyReviews()
      .then(async (reviews) => {
        const found = reviews.find((r) => r.id === reviewId)
        if (!active) {
          return
        }
        if (!found) {
          setLoadError('This review could not be found.')
          return
        }
        setReview(found)
        const summary = await api.getBookingSummary(found.bookingId)
        if (active && summary) {
          setBooking(summary)
        }
      })
      .catch((error) => active && setLoadError(reviewErrorMessage(error)))
    return () => {
      active = false
    }
  }, [api, reviewId, review])

  const save = async (payload) => {
    setServerError(null)
    try {
      await api.updateReview(reviewId, payload)
      navigate('/customer/reviews', { state: { flash: 'Your changes have been saved.', tab: 'reviews' } })
    } catch (error) {
      setServerError(reviewErrorMessage(error))
    }
  }

  const now = fixedNow ?? new Date()
  const back = () => navigate('/customer/reviews', { state: { tab: 'reviews' } })

  let content
  if (loadError) {
    content = <BlockedNotice message={loadError} onBack={back} />
  } else if (!review) {
    content = <p className="rv-muted">Loading your review…</p>
  } else if (!canEditReview(review, now)) {
    content = <BlockedNotice message={editBlockedReason(review, now) ?? 'This review can no longer be edited.'} onBack={back} />
  } else {
    content = (
      <div className="rv-card rv-form-card">
        <ReviewBookingCard booking={booking} bookingId={review.bookingId} />
        <ReviewForm
          initialRating={review.rating}
          initialComment={review.comment ?? ''}
          notice={`Editable until ${formatDateTime(editableUntil(review))}. Your edited review is shown with an "Edited" label.`}
          submitLabel="Save changes"
          cancelLabel="Cancel"
          onCancel={back}
          onSubmit={save}
          serverError={serverError}
        />
      </div>
    )
  }

  return (
    <ReviewsPageShell
      breadcrumb="Edit your review"
      title="Edit your review"
      subtitle="Your honest feedback helps other customers choose with confidence."
    >
      {content}
    </ReviewsPageShell>
  )
}

function BlockedNotice({ message, onBack }) {
  return (
    <div className="rv-card">
      <div className="rv-note">
        <CompletionIcon name="info" size={18} />
        <p>{message}</p>
      </div>
      <div className="rv-actions">
        <Button variant="outline" onClick={onBack}>
          Back to My Reviews
        </Button>
      </div>
    </div>
  )
}
