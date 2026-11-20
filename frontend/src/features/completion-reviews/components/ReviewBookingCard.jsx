import { Badge } from '../../../shared/components/forms'

import CompletionIcon from './CompletionIcons'
import { formatShortDate } from '../utils/completionUtils'

/**
 * Booking summary at the top of S07/S08: title, provider and completion date.
 * When the booking details are not available yet (WP05), only the booking reference is shown.
 */
export default function ReviewBookingCard({ booking, bookingId }) {
  const title = booking?.title ?? `Booking ${booking?.ref ?? bookingId ?? ''}`.trim()
  const meta = [booking?.providerName, booking?.completedAt ? `Completed ${formatShortDate(booking.completedAt)}` : null]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="rv-booking">
      <span className="rv-booking-thumb" aria-hidden="true">
        <CompletionIcon name="image" size={20} />
      </span>
      <div className="rv-booking-text">
        <p className="rv-booking-title">{title}</p>
        {meta && <p className="rv-booking-meta">{meta}</p>}
      </div>
      <Badge variant="success" size="small">
        Completed
      </Badge>
    </div>
  )
}
