import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'

import ReviewBookingCard from '../components/ReviewBookingCard'
import ReviewForm from '../components/ReviewForm'
import ReviewsPageShell from '../components/ReviewsPageShell'
import { reviewApi, reviewErrorMessage } from '../api'
import { EDIT_WINDOW_HOURS, REVIEW_STATUS } from '../utils/reviewUtils'

/**
 * S07 – Reviews_Customer_WriteReview (/customer/reviews/new/:bookingId).
 * Opened from S04 "Leave a review" or S09 "To review". The booking summary comes through
 * router state when available; otherwise it is looked up (WP05), or only the reference is shown.
 */
export default function WriteReviewPage({ api = reviewApi }) {
  const { bookingId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [booking, setBooking] = useState(location.state?.booking ?? null)
  const [serverError, setServerError] = useState(null)

  useEffect(() => {
    if (booking) {
      return undefined
    }
    let active = true
    api.getBookingSummary(bookingId).then((summary) => {
      if (active && summary) {
        setBooking(summary)
      }
    }).catch(() => {})
    return () => {
      active = false
    }
  }, [api, bookingId, booking])

  const submit = async (payload) => {
    setServerError(null)
    try {
      const review = await api.createReview({ bookingId, ...payload })
      const flash =
        review?.status === REVIEW_STATUS.AWAITING_MODERATION
          ? 'Thanks! Your review is awaiting moderation.'
          : 'Thanks! Your review has been published.'
      navigate('/customer/reviews', { state: { flash, tab: 'reviews' } })
    } catch (error) {
      setServerError(reviewErrorMessage(error))
    }
  }

  return (
    <ReviewsPageShell
      breadcrumb="Leave a review"
      title="Leave a review"
      subtitle="Your honest feedback helps other customers choose with confidence."
    >
      <div className="rv-card rv-form-card">
        <ReviewBookingCard booking={booking} bookingId={bookingId} />
        <ReviewForm
          notice={`Please don't include phone numbers, emails or links. You can edit your review within ${EDIT_WINDOW_HOURS} hours.`}
          submitLabel="Submit review"
          cancelLabel="Not now"
          onCancel={() => navigate('/customer/reviews')}
          onSubmit={submit}
          serverError={serverError}
        />
      </div>
    </ReviewsPageShell>
  )
}
