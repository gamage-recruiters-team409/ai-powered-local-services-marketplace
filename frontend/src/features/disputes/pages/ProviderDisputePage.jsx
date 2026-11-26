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
import { AppealSection } from '../components/AppealSection'
import { DisputeIcon } from '../components/DisputeIcon'
import { DisputeResponseForm } from '../components/DisputeResponseForm'
import { EvidenceSection } from '../components/EvidenceSection'
import { DISPUTE_STATUS, responderRoleOf } from '../config/disputeStatus'
import { useDispute } from '../hooks/useDispute'
import { useNow } from '../hooks/useNow'
import { isResponseOverdue } from '../utils/countdown'
import { EVIDENCE_OPEN_STATUSES } from '../utils/evidence'
import { disputeTitle } from '../utils/format'
import { responseClosedReason } from '../utils/responseState'
import styles from '../disputes.module.css'

const MY_DISPUTES_PATH = '/provider/disputes'
const ACTIVE_STATUSES = new Set([DISPUTE_STATUS.OPEN, DISPUTE_STATUS.AWAITING_RESPONSE, DISPUTE_STATUS.UNDER_REVIEW])

/** Provider "Response dispute", rendered inside the shared ProviderLayout. */
export function ProviderDisputePage() {
  const { disputeId } = useParams()
  const location = useLocation()
  const { status, dispute, error, reload, replace, refresh } = useDispute(disputeId)
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
      return <DisputeNotFound backTo={MY_DISPUTES_PATH} />
    }
    return <DisputeErrorState message={error.message} onRetry={reload} />
  }
  return <ProviderDisputeView key={dispute.id} dispute={dispute} now={now} onUpdated={replace} onReload={refresh} />
}

function ProviderDisputeView({ dispute, now, onUpdated, onReload }) {
  const location = useLocation()
  const justRaised = location.state?.justRaised === true
  const raisedFailedUploads = location.state?.failedUploads ?? 0
  const [justSubmitted, setJustSubmitted] = useState(null)
  const responderRole = responderRoleOf(dispute)
  const providerResponds = responderRole === 'PROVIDER'
  const title = disputeTitle(dispute)
  const reason = responseClosedReason(dispute, now)
  const overdue = isResponseOverdue(dispute, now)

  return (
    <div className={styles.page}>
      <Breadcrumb items={[
        { label: 'Dashboard', to: '/provider' },
        { label: 'My Disputes', to: MY_DISPUTES_PATH },
        { label: title },
      ]}
      />

      <div className={styles.titleGroup}>
        <h1 className={styles.title}>{title}</h1>
        <StatusBadge
          status={dispute.status}
          viewerIsResponder={providerResponds}
          responderRole={responderRole}
          overdue={overdue}
        />
      </div>

      <DeadlineBanner
        dispute={dispute}
        now={now}
        waitingText={providerResponds ? 'Please respond within 72 hours.' : 'The customer has 72 hours to respond.'}
      />

      {ACTIVE_STATUSES.has(dispute.status) && (
        <div className={`${styles.banner} ${styles.bannerInfo}`}>
          <span className={styles.bannerText}>
            <DisputeIcon name="info" />
            Payment handling for this booking will follow the dispute outcome.
          </span>
        </div>
      )}

      <div aria-live="polite">
        {justRaised && (
          <InlineMessage variant={raisedFailedUploads > 0 ? 'warning' : 'success'}>
            Your dispute was submitted. The customer has 72 hours to respond.
            {raisedFailedUploads > 0
              && ` ${raisedFailedUploads} file(s) could not be uploaded; you can add them below.`}
          </InlineMessage>
        )}
        {justSubmitted && (
          <InlineMessage variant={justSubmitted.failedUploads > 0 ? 'warning' : 'success'}>
            Your response was submitted. An admin will now review both sides.
            {justSubmitted.failedUploads > 0
              && ` ${justSubmitted.failedUploads} file(s) could not be uploaded; you can add them below.`}
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
            overdue={overdue}
          />
          <EvidenceSection
            dispute={dispute}
            viewerRole="PROVIDER"
            canUpload={EVIDENCE_OPEN_STATUSES.has(dispute.status)}
            onUploaded={(uploaded) => onUpdated({ ...dispute, evidenceFiles: [...(dispute.evidenceFiles ?? []), ...uploaded] })}
          />
        </div>
        <div className={styles.column}>
          <AppealSection dispute={dispute} now={now} viewerRole="PROVIDER" onUpdated={onUpdated} onReload={onReload} />
          {providerResponds && dispute.response && <ResponseView response={dispute.response} title="Your response" />}
          {providerResponds && !dispute.response && (
            <DisputeResponseForm
              disputeId={dispute.id}
              canRespond={reason === null}
              closedMessage={reason}
              onSubmitted={(updated, result) => {
                setJustSubmitted({ failedUploads: result?.failedUploads ?? 0 })
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
