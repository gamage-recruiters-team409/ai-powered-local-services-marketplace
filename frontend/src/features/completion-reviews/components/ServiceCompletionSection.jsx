import { useEffect, useState } from 'react'

import { Button } from '../../../shared/components/forms'
import { completionApi, completionErrorMessage } from '../api'
import CompletionDetailsModal from './CompletionDetailsModal'
import CompletionIcon from './CompletionIcons'
import CompletionStatusBadge from './CompletionStatusBadge'
import CompletionStepper from './CompletionStepper'
import MarkCompletedModal from './MarkCompletedModal'
import PhotoThumb from './PhotoThumb'
import StartServiceModal from './StartServiceModal'
import {
  DISPUTE_OUTCOME_STATUSES,
  PAYOUT_RELEASE_HOURS,
  autoConfirmRemainingMs,
  formatDateTime,
  formatDuration,
  formatShortDate,
  formatSlot,
  formatTime,
  hoursSinceSlotEnded,
  payoutReleaseDate,
  shortName,
} from '../utils/completionUtils'
import '../styles/completion.css'

const MINUTE_MS = 60 * 1000

/**
 * Provider view of the "Service Completion" card on the booking details page (WP08).
 *
 * The booking details page (WP05) passes the booking; this card owns the completion
 * flow: Start Service (S01), Mark Completed (S02) and Completion details (S03).
 * Remount it with a new `key` if the booking is reloaded from outside.
 *
 * Props
 * - booking: { id, ref, title, customerName, providerName, slotStart, slotEnd, status }
 * - api: completion API (defaults to the real or mock API, see api/index.js)
 * - dispute: from the dispute module (WP09)
 *     open:     { id, reason, url }
 *     resolved: { id, status: 'RESOLVED', resolvedAt, outcome, canAppeal, url }
 *     `url` is the Dispute Details screen; "Open appeal" links there (appeal reviewed by a different admin).
 * - earnings: { amountLabel, payoutStatus: 'PENDING' | 'RELEASED' | 'ON_HOLD', url } (WP07)
 * - review: { customerName, rating, comment, status } shown when the booking is COMPLETED
 * - onBookingStatusChange(status), onReportNoShow(), onReplyToReview()
 * - now: current time (tests only)
 */
export default function ServiceCompletionSection({
  booking,
  api = completionApi,
  dispute = null,
  earnings = null,
  review = null,
  onBookingStatusChange,
  onReportNoShow,
  onReplyToReview,
  now: fixedNow,
}) {
  const [status, setStatus] = useState(booking.status)
  const [initialStatus] = useState(booking.status)
  const needsCompletion = initialStatus !== 'CONFIRMED'

  const [load, setLoad] = useState({ loading: needsCompletion, completion: null, error: null })
  const [modal, setModal] = useState(null) // 'start' | 'mark' | 'details' | null
  const [action, setAction] = useState({ loading: false, error: null })
  const [justStarted, setJustStarted] = useState(false)
  const [tick, setTick] = useState(() => new Date())
  const now = fixedNow ?? tick

  // Load the completion record once (none exists before the service starts).
  useEffect(() => {
    if (!needsCompletion) {
      return undefined
    }
    let active = true
    api.getCompletion(booking.id)
      .then((completion) => active && setLoad({ loading: false, completion, error: null }))
      .catch((error) => active && setLoad({ loading: false, completion: null, error: completionErrorMessage(error) }))
    return () => {
      active = false
    }
  }, [api, booking.id, needsCompletion])

  // Refresh countdowns once a minute.
  useEffect(() => {
    if (fixedNow) {
      return undefined
    }
    const timer = setInterval(() => setTick(new Date()), MINUTE_MS)
    return () => clearInterval(timer)
  }, [fixedNow])

  const completion = load.completion
  const customer = shortName(booking.customerName)

  const changeStatus = (next) => {
    setStatus(next)
    onBookingStatusChange?.(next)
  }

  const openModal = (name) => {
    setAction({ loading: false, error: null })
    setModal(name)
  }

  const closeModal = () => {
    if (!action.loading) {
      setModal(null)
    }
  }

  const handleStart = async () => {
    setAction({ loading: true, error: null })
    try {
      const result = await api.startService(booking.id)
      setLoad({ loading: false, completion: result, error: null })
      setJustStarted(true)
      setAction({ loading: false, error: null })
      setModal(null)
      changeStatus('IN_PROGRESS')
    } catch (error) {
      setAction({ loading: false, error: completionErrorMessage(error) })
    }
  }

  const handleMarkCompleted = async ({ completionNote, files }) => {
    setAction({ loading: true, error: null })
    try {
      const photoUrls = []
      for (const file of files) {
        photoUrls.push(await api.uploadCompletionPhoto(file))
      }
      const result = await api.markCompleted(booking.id, { completionNote, photoUrls })
      setLoad({ loading: false, completion: result, error: null })
      setJustStarted(false)
      setAction({ loading: false, error: null })
      setModal(null)
      changeStatus('COMPLETION_PENDING')
    } catch (error) {
      setAction({ loading: false, error: error?.response ? completionErrorMessage(error) : error.message })
    }
  }

  return (
    <section className="sc-card" aria-labelledby="sc-heading">
      <header className="sc-card__header">
        <span className="sc-card__icon"><CompletionIcon name="checkCircle" size={22} /></span>
        <h2 id="sc-heading" className="sc-card__title">Service Completion</h2>
        <CompletionStatusBadge status={status} />
      </header>

      <CompletionStepper status={status} />

      {load.loading && <p className="sc-muted" role="status">Loading completion details…</p>}
      {load.error && (
        <p className="sc-callout sc-callout--error" role="alert">
          <CompletionIcon name="alert" />
          <span>{load.error}</span>
        </p>
      )}

      {!load.loading && !load.error && (
        <div className="sc-card__body">
          {status === 'CONFIRMED' && (
            <>
              <p className="sc-text">
                The service is scheduled for <strong>{formatSlot(booking.slotStart, booking.slotEnd, now)}</strong>.
                {' '}Start the service when you arrive at the location.
              </p>
              <div className="sc-actions">
                <Button icon={<CompletionIcon name="play" />} onClick={() => openModal('start')}>Start Service</Button>
                {onReportNoShow && (
                  <Button variant="outline" icon={<CompletionIcon name="flag" />} onClick={onReportNoShow}>
                    Report No-Show
                  </Button>
                )}
              </div>
            </>
          )}

          {status === 'IN_PROGRESS' && (
            <InProgressBody
              completion={completion}
              booking={booking}
              customer={customer}
              justStarted={justStarted}
              now={now}
              onMarkCompleted={() => openModal('mark')}
            />
          )}

          {status === 'COMPLETION_PENDING' && completion && (
            <>
              <p className="sc-callout sc-callout--warning">
                <CompletionIcon name="clock" />
                <span>
                  Waiting for {customer} to confirm.{' '}
                  <strong>Auto-confirms in {formatDuration(autoConfirmRemainingMs(completion.markedCompletedAt, now))}</strong>
                  {' '}if there is no response.
                </span>
              </p>
              <CompletionEvidence completion={completion} />
              <p className="sc-callout sc-callout--info">
                <CompletionIcon name="wallet" />
                <span>
                  Earnings: <strong>Pending</strong> – released {PAYOUT_RELEASE_HOURS} hours after completion if no dispute is raised.
                </span>
              </p>
            </>
          )}

          {status === 'DISPUTED' && (
            <>
              <p className="sc-callout sc-callout--error">
                <CompletionIcon name="alert" />
                <span>
                  <strong>A dispute is open for this booking{dispute?.id ? ` (${dispute.id})` : ''}.</strong>
                  {' '}Your payout is on hold until it is resolved.
                </span>
              </p>
              {dispute?.reason && (
                <p className="sc-muted">
                  Reason given by the customer: “{dispute.reason}” Respond within 72 hours.
                </p>
              )}
              {dispute?.url && (
                <div className="sc-actions">
                  <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={dispute.url}>
                    View dispute <CompletionIcon name="chevronRight" size={16} />
                  </a>
                </div>
              )}
            </>
          )}

          {DISPUTE_OUTCOME_STATUSES.includes(status) && dispute?.status === 'RESOLVED' && (
            <ResolvedDisputeBody dispute={dispute} />
          )}

          {status === 'COMPLETED' && completion && dispute?.status !== 'RESOLVED' && (
            <CompletedBody
              completion={completion}
              booking={booking}
              earnings={earnings}
              review={review}
              onReplyToReview={onReplyToReview}
              onViewDetails={() => openModal('details')}
            />
          )}
        </div>
      )}

      {modal === 'start' && (
        <StartServiceModal
          isOpen
          booking={booking}
          onClose={closeModal}
          onConfirm={handleStart}
          loading={action.loading}
          error={action.error}
        />
      )}
      {modal === 'mark' && (
        <MarkCompletedModal
          isOpen
          booking={booking}
          onClose={closeModal}
          onSubmit={handleMarkCompleted}
          loading={action.loading}
          error={action.error}
        />
      )}
      {modal === 'details' && (
        <CompletionDetailsModal isOpen booking={booking} completion={completion} onClose={closeModal} />
      )}
    </section>
  )
}

function ResolvedDisputeBody({ dispute }) {
  const resolvedOn = dispute.resolvedAt ? ` on ${formatDateTime(dispute.resolvedAt).replace(' · ', ' at ')}` : ''
  return (
    <>
      <p className="sc-callout sc-callout--info">
        <CompletionIcon name="info" />
        <span>
          <strong>The dispute for this booking{dispute.id ? ` (${dispute.id})` : ''} was resolved{resolvedOn}.</strong>
          {dispute.outcome ? ` Decision: ${dispute.outcome}` : ''}
        </span>
      </p>
      {dispute.canAppeal && (
        <p className="sc-muted">
          If you disagree with the decision, you can open an appeal. It will be reviewed by a different admin.
        </p>
      )}
      {dispute.url && (
        <div className="sc-actions">
          {dispute.canAppeal && (
            <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={dispute.url}>
              Open appeal <CompletionIcon name="chevronRight" size={16} />
            </a>
          )}
          {!dispute.canAppeal && (
            <a className="shared-btn shared-btn--outline shared-btn--medium sc-link-btn" href={dispute.url}>
              View dispute <CompletionIcon name="chevronRight" size={16} />
            </a>
          )}
        </div>
      )}
    </>
  )
}

function InProgressBody({ completion, booking, customer, justStarted, now, onMarkCompleted }) {
  const startedAt = completion?.startedAt
  const overdueHours = hoursSinceSlotEnded(booking.slotEnd, now)

  return (
    <>
      {justStarted && (
        <p className="sc-callout sc-callout--success" role="status">
          <CompletionIcon name="checkCircle" />
          <span>Service started at {formatTime(startedAt)}. {customer} has been notified.</span>
        </p>
      )}
      {!justStarted && overdueHours > 0 && (
        <p className="sc-callout sc-callout--warning">
          <CompletionIcon name="clock" />
          <span>
            <strong>The booking time ended {overdueHours} hour{overdueHours === 1 ? '' : 's'} ago.</strong>
            {' '}Please mark the service as completed.
          </span>
        </p>
      )}
      <p className="sc-text">
        {justStarted || !startedAt ? 'The service is in progress.' : `Started at ${formatTime(startedAt)}.`}
        {' '}When the work is finished, mark it as completed.
      </p>
      <div className="sc-actions">
        <Button icon={<CompletionIcon name="checkCircle" />} onClick={onMarkCompleted}>Mark Completed</Button>
        {justStarted && <span className="sc-muted">The customer will confirm completion next.</span>}
      </div>
    </>
  )
}

function CompletionEvidence({ completion }) {
  const photos = completion.photoUrls ?? []
  return (
    <div className="sc-evidence">
      <h3 className="sc-subheading">Your completion note</h3>
      <p className="sc-text">{completion.completionNote ? `“${completion.completionNote}”` : 'No note was added.'}</p>
      {photos.length > 0 && (
        <>
          <h3 className="sc-subheading">Photos</h3>
          <div className="sc-photo-grid">
            {photos.map((url, index) => <PhotoThumb key={url} url={url} index={index} />)}
          </div>
        </>
      )}
    </div>
  )
}

const PAYOUT_LABELS = {
  PENDING: { badge: 'Payout pending', value: 'Pending release', tone: 'blue' },
  ON_HOLD: { badge: 'Payout on hold', value: 'On hold', tone: 'amber' },
  RELEASED: { badge: 'Paid out', value: 'Released', tone: 'green' },
}

function CompletedBody({ completion, booking, earnings, review, onReplyToReview, onViewDetails }) {
  const auto = completion.confirmationType === 'AUTO'
  const release = payoutReleaseDate(completion.confirmedAt)
  const payout = PAYOUT_LABELS[earnings?.payoutStatus] ?? PAYOUT_LABELS.PENDING
  const [date, time] = formatDateTime(completion.confirmedAt).split(' · ')

  return (
    <>
      <p className="sc-callout sc-callout--success">
        <CompletionIcon name="checkCircle" />
        <span>
          <strong>
            {auto
              ? `Confirmed automatically on ${date} at ${time}`
              : `${booking.customerName ?? 'The customer'} confirmed the service on ${date} at ${time}`}.
          </strong>
          {' '}This booking is completed.
        </span>
      </p>

      {(earnings || review) && (
        <div className="sc-panels">
          {earnings && (
            <div className="sc-panel">
              <div className="sc-panel__header">
                <CompletionIcon name="wallet" />
                <h3 className="sc-panel__title">Earnings</h3>
                <span className={`sc-badge sc-badge--${payout.tone}`}><span className="sc-badge__dot" aria-hidden="true" />{payout.badge}</span>
              </div>
              <dl className="sc-rows">
                <div><dt>Booking amount</dt><dd><strong>{earnings.amountLabel}</strong></dd></div>
                <div><dt>Payout status</dt><dd className={`sc-tone-${payout.tone}`}>{payout.value}</dd></div>
              </dl>
              {release && earnings.payoutStatus !== 'RELEASED' && (
                <p className="sc-muted sc-panel__foot">
                  Released on <strong>{formatShortDate(release)}</strong> if no dispute is raised within {PAYOUT_RELEASE_HOURS} hours.
                </p>
              )}
            </div>
          )}
          {review && (
            <div className="sc-panel">
              <div className="sc-panel__header">
                <span className="sc-star" aria-hidden="true">☆</span>
                <h3 className="sc-panel__title">Customer review</h3>
                {review.status === 'PUBLISHED' && (
                  <span className="sc-badge sc-badge--green"><span className="sc-badge__dot" aria-hidden="true" />Published</span>
                )}
              </div>
              <p className="sc-review__name">{review.customerName}</p>
              <p className="sc-review__stars" aria-label={`${review.rating} out of 5 stars`}>
                {'★'.repeat(review.rating)}<span className="sc-review__stars--empty">{'★'.repeat(5 - review.rating)}</span>
              </p>
              {review.comment && <p className="sc-text">“{review.comment}”</p>}
              {onReplyToReview && (
                <Button variant="outline" size="small" onClick={onReplyToReview}>Reply to review</Button>
              )}
            </div>
          )}
        </div>
      )}

      <div className="sc-footer-row">
        <p className="sc-muted">Completion note and photos are kept with this booking.</p>
        <div className="sc-actions">
          <Button variant="outline" icon={<CompletionIcon name="image" />} onClick={onViewDetails}>
            View completion details
          </Button>
          {earnings?.url && (
            <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={earnings.url}>
              <CompletionIcon name="wallet" /> View earnings
            </a>
          )}
        </div>
      </div>
    </>
  )
}
