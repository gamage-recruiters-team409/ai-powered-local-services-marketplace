import { useState } from 'react'
import { Navigate, useLocation, useParams } from 'react-router-dom'

import { InlineMessage } from '../../../shared/components/feedback'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import {
  BookingCard,
  Breadcrumb,
  DeadlineBanner,
  DisputeTimeline,
  IssueCard,
  ResponseView,
  StatusBadge,
} from '../components/DisputeParts'
import { DisputeAccessDenied, DisputeErrorState, DisputeNotFound, DisputeSkeleton } from '../components/DisputeStates'
import { ProviderResponseForm } from '../components/ProviderResponseForm'
import { DISPUTE_STATUS, responderRoleOf } from '../config/disputeStatus'
import { useDispute } from '../hooks/useDispute'
import { useNow } from '../hooks/useNow'
import { getTimeLeft } from '../utils/countdown'
import { disputeTitle } from '../utils/format'
import styles from '../disputes.module.css'

const MY_BOOKINGS_PATH = '/provider/bookings'
const ACTIVE_STATUSES = new Set([DISPUTE_STATUS.OPEN, DISPUTE_STATUS.AWAITING_RESPONSE, DISPUTE_STATUS.UNDER_REVIEW])

/** Provider "Response dispute", rendered inside the shared ProviderLayout. */
export function ProviderDisputePage() {
  const { disputeId } = useParams()
  const location = useLocation()
  const { status, dispute, error, reload, replace } = useDispute(disputeId)
  const now = useNow()

  if (status === 'loading') {
    return <DisputeSkeleton />
  }
  if (status === 'error') {
    if (error.kind === 'unauthorized') {
      return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
    }
    if (error.kind === 'forbidden') {
      return <DisputeAccessDenied />
    }
    if (error.kind === 'not-found') {
      return <DisputeNotFound backTo={MY_BOOKINGS_PATH} />
    }
    return <DisputeErrorState message={error.message} onRetry={reload} />
  }
  return <ProviderDisputeView key={dispute.id} dispute={dispute} now={now} onUpdated={replace} onReload={reload} />
}

/** Why the form is not available, or null when the provider can respond now. */
function closedReason(dispute, now) {
  if (dispute.response) {
    return 'You have already responded. An admin is reviewing both sides.'
  }
  if (dispute.status !== DISPUTE_STATUS.AWAITING_RESPONSE) {
    return 'Response window closed. This dispute is no longer waiting for a response.'
  }
  if (getTimeLeft(dispute.responseDueAt, now)?.overdue !== false) {
    return 'Response window closed. The 72-hour deadline has passed.'
  }
  return null
}

function ProviderDisputeView({ dispute, now, onUpdated, onReload }) {
  const [justSubmitted, setJustSubmitted] = useState(false)
  const responderRole = responderRoleOf(dispute)
  const providerResponds = responderRole === 'PROVIDER'
  const title = disputeTitle(dispute)
  const reason = closedReason(dispute, now)

  return (
    <div className={styles.page}>
      <Breadcrumb items={[
        { label: 'Dashboard', to: '/provider' },
        { label: 'My Bookings', to: MY_BOOKINGS_PATH },
        { label: title },
      ]}
      />

      <div className={styles.titleGroup}>
        <h1 className={styles.title}>{title}</h1>
        <StatusBadge status={dispute.status} viewerIsResponder={providerResponds} responderRole={responderRole} />
      </div>

      <DeadlineBanner
        dispute={dispute}
        now={now}
        waitingText={providerResponds ? 'Please respond within 72 hours.' : 'The customer has 72 hours to respond.'}
      />

      {ACTIVE_STATUSES.has(dispute.status) && (
        <div className={`${styles.banner} ${styles.bannerInfo}`}>
          <span>The payout for this booking is on hold until the dispute is resolved.</span>
        </div>
      )}

      <div aria-live="polite">
        {justSubmitted && (
          <InlineMessage variant="success">
            Your response was submitted. An admin will now review both sides.
          </InlineMessage>
        )}
      </div>

      <div className={styles.grid}>
        <div className={styles.column}>
          <BookingCard dispute={dispute} title="Booking" />
          <IssueCard
            dispute={dispute}
            title={providerResponds ? 'Issue reported by the customer' : 'Your issue'}
          />
          <DisputeTimeline
            dispute={dispute}
            waitingTitle={providerResponds ? 'Waiting for your response' : "Waiting for the customer's response"}
          />
        </div>
        <div className={styles.column}>
          {providerResponds && dispute.response && <ResponseView response={dispute.response} title="Your response" />}
          {providerResponds && !dispute.response && (
            <ProviderResponseForm
              disputeId={dispute.id}
              canRespond={reason === null}
              closedMessage={reason}
              onSubmitted={(updated) => {
                setJustSubmitted(true)
                onUpdated(updated)
              }}
              onWindowClosed={onReload}
            />
          )}
          {!providerResponds && (
            <>
              <ResponseView response={dispute.response} title="Customer's response" />
              {!dispute.response && (
                <InlineMessage variant="info">You raised this dispute. The customer is asked to respond.</InlineMessage>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
