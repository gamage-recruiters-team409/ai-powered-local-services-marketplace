import { useEffect, useState } from 'react'

import { Button } from '../../../shared/components/forms'
import { completionApi, completionErrorMessage } from '../api'
import CompletionIcon from './CompletionIcons'
import CompletionStatusBadge from './CompletionStatusBadge'
import CompletionStepper from './CompletionStepper'
import ConfirmCompletionModal from './ConfirmCompletionModal'
import PhotoThumb from './PhotoThumb'
import RaiseIssueModal, { IssueSubmittedModal } from './RaiseIssueModal'
import ReportNoShowModal from './ReportNoShowModal'
import ResolvedDisputeNotice from './ResolvedDisputeNotice'
import {
  DISPUTE_OUTCOME_STATUSES,
  autoConfirmRemainingMs,
  buildIssueReason,
  formatDuration,
  formatSlot,
  formatTime,
} from '../utils/completionUtils'
import '../styles/completion.css'

const MINUTE_MS = 60 * 1000

/**
 * Customer view of the "Service Completion" card on the booking details page (WP08).
 *
 * The booking details page (WP05, Disura) passes the booking; this card owns the customer flow:
 * Confirm Completion (S04), Raise Issue (S05) and Report No-Show (S06).
 * Remount it with a new `key` if the booking is reloaded from outside.
 *
 * Props
 * - booking: { id, ref, title, providerName, slotStart, slotEnd, status }
 * - api: completion API (defaults to the real or mock API, see api/index.js)
 * - dispute: from the dispute module (WP09)
 *     open:     { id, url }
 *     resolved: { id, status: 'RESOLVED', resolvedAt, outcome, canAppeal, url }
 * - disputeUrlFor(disputeId): builds the Dispute Details link after a new issue / no-show (WP09)
 * - hasReview: true when the customer already reviewed this booking
 * - onLeaveReview(), onViewReview(): open the review pages (S07)
 * - onBookingStatusChange(status)
 * - now: current time (tests only)
 */
export default function CustomerCompletionSection({
  booking,
  api = completionApi,
  dispute = null,
  disputeUrlFor,
  hasReview = false,
  onLeaveReview,
  onViewReview,
  onBookingStatusChange,
  now: fixedNow,
}) {
  const [status, setStatus] = useState(booking.status)
  const [initialStatus] = useState(booking.status)
  const needsCompletion = initialStatus !== 'CONFIRMED'

  const [load, setLoad] = useState({ loading: needsCompletion, completion: null, error: null })
  const [modal, setModal] = useState(null) // 'confirm' | 'issue' | 'issueDone' | 'noShow' | null
  const [action, setAction] = useState({ loading: false, error: null })
  const [newDispute, setNewDispute] = useState(null) // { id, kind: 'ISSUE' | 'NO_SHOW' }
  const [tick, setTick] = useState(() => new Date())
  const now = fixedNow ?? tick

  // Load the completion record once (none exists before the provider starts the service).
  useEffect(() => {
    if (!needsCompletion) {
      return undefined
    }
    let active = true
    api.getCompletion(booking.id)
      .then((completion) => active && setLoad({ loading: false, completion, error: null }))
      .catch((error) => {
        if (!active) {
          return
        }
        // A disputed no-show has no completion record; that is not an error for this card.
        const missing = error?.response?.status === 404
        setLoad({ loading: false, completion: null, error: missing ? null : completionErrorMessage(error) })
      })
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
  const provider = booking.providerName ?? 'The provider'
  const evidenceSupported = Boolean(api.supportsEvidence)

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

  const uploadEvidence = async (files) => {
    if (!evidenceSupported || !files?.length) {
      return []
    }
    const urls = []
    for (const file of files) {
      urls.push(await api.uploadEvidence(file))
    }
    return urls
  }

  const handleConfirm = async () => {
    setAction({ loading: true, error: null })
    try {
      const result = await api.confirmCompletion(booking.id)
      setLoad({ loading: false, completion: result, error: null })
      setAction({ loading: false, error: null })
      setModal(null)
      changeStatus('COMPLETED')
    } catch (error) {
      setAction({ loading: false, error: completionErrorMessage(error) })
    }
  }

  const handleRaiseIssue = async ({ reason, description, files }) => {
    setAction({ loading: true, error: null })
    try {
      const evidenceUrls = await uploadEvidence(files)
      const result = await api.raiseIssue(booking.id, { reason: buildIssueReason(reason, description), evidenceUrls })
      setLoad({ loading: false, completion: result, error: null })
      setNewDispute({ id: result?.disputeId ?? null, kind: 'ISSUE' })
      setAction({ loading: false, error: null })
      setModal('issueDone')
      changeStatus('DISPUTED')
    } catch (error) {
      setAction({ loading: false, error: error?.response ? completionErrorMessage(error) : error.message })
    }
  }

  const handleReportNoShow = async ({ note, files }) => {
    setAction({ loading: true, error: null })
    try {
      const evidenceUrls = await uploadEvidence(files)
      await api.reportNoShow(booking.id, { note, evidenceUrls })
      setNewDispute({ id: null, kind: 'NO_SHOW' })
      setAction({ loading: false, error: null })
      setModal(null)
      changeStatus('DISPUTED')
    } catch (error) {
      setAction({ loading: false, error: error?.response ? completionErrorMessage(error) : error.message })
    }
  }

  const activeDisputeId = dispute?.id ?? newDispute?.id ?? completion?.disputeId ?? null
  const activeDisputeUrl = dispute?.url ?? (activeDisputeId && disputeUrlFor ? disputeUrlFor(activeDisputeId) : null)

  return (
    <section className={`sc-card${status === 'COMPLETED' ? ' sc-card--success' : ''}`} aria-labelledby="sc-heading-customer">
      <header className="sc-card__header">
        <span className="sc-card__icon"><CompletionIcon name="checkCircle" size={22} /></span>
        <h2 id="sc-heading-customer" className="sc-card__title">Service Completion</h2>
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
                Your service is scheduled for <strong>{formatSlot(booking.slotStart, booking.slotEnd, now)}</strong>.
                {' '}{provider} will start the service when they arrive.
              </p>
              <div className="sc-footer-row">
                <p className="sc-muted">Provider didn&apos;t turn up? You can report it 2 hours after the start time.</p>
                <Button variant="outline" icon={<CompletionIcon name="flag" />} onClick={() => openModal('noShow')}>
                  Report No-Show
                </Button>
              </div>
            </>
          )}

          {status === 'IN_PROGRESS' && (
            <p className="sc-callout sc-callout--info">
              <CompletionIcon name="clock" />
              <span>
                <strong>{provider} started the service{completion?.startedAt ? ` at ${formatTime(completion.startedAt)}` : ''}.</strong>
                {' '}You&apos;ll be asked to confirm once it is marked as completed.
              </span>
            </p>
          )}

          {status === 'COMPLETION_PENDING' && completion && (
            <>
              <p className="sc-callout sc-callout--warning">
                <CompletionIcon name="clock" />
                <span>
                  {provider} marked the service as completed. Please check the work and confirm.{' '}
                  <strong>Auto-confirms in {formatDuration(autoConfirmRemainingMs(completion.markedCompletedAt, now))}</strong>
                  {' '}if there is no response.
                </span>
              </p>
              <ProviderEvidence completion={completion} />
              <div className="sc-footer-row">
                <p className="sc-muted">Something not right? You can raise an issue before confirming.</p>
                <div className="sc-actions">
                  <Button variant="outline" icon={<CompletionIcon name="flag" />} onClick={() => openModal('issue')}>
                    Raise Issue
                  </Button>
                  <Button icon={<CompletionIcon name="checkCircle" />} onClick={() => openModal('confirm')}>
                    Confirm Completion
                  </Button>
                </div>
              </div>
            </>
          )}

          {status === 'DISPUTED' && (
            <>
              <p className="sc-callout sc-callout--error">
                <CompletionIcon name="alert" />
                <span>
                  <strong>
                    {newDispute?.kind === 'NO_SHOW' ? 'Your no-show report was submitted' : 'A dispute is open for this booking'}
                    {activeDisputeId ? ` (${activeDisputeId})` : ''}.
                  </strong>
                  {' '}The provider&apos;s payment is held until it is resolved. We&apos;ll notify you about the outcome.
                </span>
              </p>
              {activeDisputeUrl && (
                <div className="sc-actions">
                  <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={activeDisputeUrl}>
                    View dispute <CompletionIcon name="chevronRight" size={16} />
                  </a>
                </div>
              )}
            </>
          )}

          {DISPUTE_OUTCOME_STATUSES.includes(status) && dispute?.status === 'RESOLVED' && (
            <ResolvedDisputeNotice dispute={dispute} />
          )}

          {status === 'COMPLETED' && dispute?.status !== 'RESOLVED' && (
            <div className="sc-result">
              <span className="sc-result__icon sc-result__icon--success"><CompletionIcon name="checkCircle" size={36} /></span>
              <h3 className="sc-result__title">Thanks! Your booking is completed.</h3>
              {completion?.confirmationType === 'AUTO' && (
                <p className="sc-muted">It was confirmed automatically 48 hours after the provider marked it completed.</p>
              )}
              {hasReview ? (
                onViewReview && (
                  <Button variant="outline" icon={<CompletionIcon name="star" />} onClick={onViewReview}>View your review</Button>
                )
              ) : (
                <>
                  <p className="sc-muted">Tell others how it went – it helps the provider and other customers.</p>
                  {onLeaveReview && (
                    <Button icon={<CompletionIcon name="star" />} onClick={onLeaveReview}>Leave a review</Button>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      )}

      {modal === 'confirm' && (
        <ConfirmCompletionModal
          isOpen
          onClose={closeModal}
          onConfirm={handleConfirm}
          loading={action.loading}
          error={action.error}
        />
      )}
      {modal === 'issue' && (
        <RaiseIssueModal
          isOpen
          booking={booking}
          allowEvidence={evidenceSupported}
          onClose={closeModal}
          onSubmit={handleRaiseIssue}
          loading={action.loading}
          error={action.error}
        />
      )}
      {modal === 'issueDone' && (
        <IssueSubmittedModal
          isOpen
          onClose={closeModal}
          disputeId={activeDisputeId}
          disputeUrl={activeDisputeUrl}
        />
      )}
      {modal === 'noShow' && (
        <ReportNoShowModal
          isOpen
          booking={booking}
          role="CUSTOMER"
          allowEvidence={evidenceSupported}
          onClose={closeModal}
          onSubmit={handleReportNoShow}
          loading={action.loading}
          error={action.error}
          now={now}
        />
      )}
    </section>
  )
}

function ProviderEvidence({ completion }) {
  const photos = completion.photoUrls ?? []
  return (
    <div className="sc-evidence">
      <h3 className="sc-subheading">Provider&apos;s note</h3>
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
