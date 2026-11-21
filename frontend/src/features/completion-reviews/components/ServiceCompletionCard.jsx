import { useNavigate } from 'react-router-dom'

import CustomerCompletionSection from './CustomerCompletionSection'
import ServiceCompletionSection from './ServiceCompletionSection'
import { COMPLETION_CARD_STATUSES } from '../utils/completionCard'

const DISPUTE_PATH = {
  CUSTOMER: (id) => `/customer/disputes/${encodeURIComponent(id)}`,
  PROVIDER: (id) => `/provider/disputes/${encodeURIComponent(id)}`,
}

/**
 * WP08 Service Completion card for the WP05 Booking Details pages
 * (/customer/bookings/:bookingId and /provider/bookings/:bookingId), placed below the booking summary
 * and above the timeline.
 *
 * Props (required): bookingId, status (booking status), slotStart, role: 'CUSTOMER' | 'PROVIDER'.
 * Optional:
 * - slotEnd: shows the "service overdue" hint to the provider
 * - onStatusChange(status): called after start / mark completed / confirm / raise issue / no-show,
 *   so the page can refresh its summary and timeline
 * - hasReview (customer): shows "View your review" instead of "Leave a review"
 * - dispute: { id, url, status, reason } when the booking page already has the dispute (WP09)
 *
 * Renders nothing before CONFIRMED (pending, declined, expired, cancelled), so it can be mounted
 * without extra checks. Review and dispute links go to the existing WP08 / WP09 routes.
 */
export default function ServiceCompletionCard({
  bookingId,
  status,
  slotStart,
  slotEnd = null,
  role,
  onStatusChange,
  hasReview = false,
  dispute = null,
}) {
  const navigate = useNavigate()

  if (!bookingId || !COMPLETION_CARD_STATUSES.includes(status)) {
    return null
  }

  const booking = { id: bookingId, status, slotStart, slotEnd }
  const disputeUrlFor = DISPUTE_PATH[role] ?? DISPUTE_PATH.CUSTOMER

  if (role === 'PROVIDER') {
    return (
      <ServiceCompletionSection
        booking={booking}
        dispute={dispute ? { url: dispute.id ? disputeUrlFor(dispute.id) : undefined, ...dispute } : null}
        onBookingStatusChange={onStatusChange}
        onReplyToReview={() => navigate('/provider/reviews')}
      />
    )
  }

  if (role === 'CUSTOMER') {
    return (
      <CustomerCompletionSection
        booking={booking}
        dispute={dispute}
        disputeUrlFor={disputeUrlFor}
        hasReview={hasReview}
        onLeaveReview={() => navigate(`/customer/reviews/new/${encodeURIComponent(bookingId)}`)}
        onViewReview={() => navigate('/customer/reviews')}
        onBookingStatusChange={onStatusChange}
      />
    )
  }

  return null
}
