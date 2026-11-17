import { Navigate, useLocation, useParams } from 'react-router-dom'

import { InlineMessage } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import {
  BookingCard,
  Breadcrumb,
  DeadlineBanner,
  DisputeTimeline,
  IssueCard,
  ResponseView,
  StatusBadge,
  WhatHappensNext,
} from '../components/DisputeParts'
import { DisputeIcon } from '../components/DisputeIcon'
import { DisputeAccessDenied, DisputeErrorState, DisputeNotFound, DisputeSkeleton } from '../components/DisputeStates'
import { responderRoleOf } from '../config/disputeStatus'
import { useDispute } from '../hooks/useDispute'
import { useNow } from '../hooks/useNow'
import { isResponseOverdue } from '../utils/countdown'
import { disputeTitle } from '../utils/format'
import styles from '../disputes.module.css'

const MY_BOOKINGS_PATH = '/customer/bookings'

/**
 * Customer "View dispute" (read-only). The route wraps it in CustomerDisputeShell until the customer layout
 * (WP03) is in the repository.
 */
export function CustomerDisputePage() {
  const { disputeId } = useParams()
  const location = useLocation()
  const { status, dispute, error, reload } = useDispute(disputeId)
  const now = useNow()

  let content
  if (status === 'loading') {
    content = <DisputeSkeleton />
  } else if (status === 'error') {
    if (error.kind === 'unauthorized') {
      return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
    }
    if (error.kind === 'forbidden') {
      content = <DisputeAccessDenied />
    } else if (error.kind === 'not-found') {
      content = <DisputeNotFound backTo={MY_BOOKINGS_PATH} />
    } else {
      content = <DisputeErrorState message={error.message} onRetry={reload} />
    }
  } else {
    content = <CustomerDisputeView dispute={dispute} now={now} />
  }

  return content
}

function CustomerDisputeView({ dispute, now }) {
  const responderRole = responderRoleOf(dispute)
  const customerResponds = responderRole === 'CUSTOMER'
  const title = disputeTitle(dispute)
  const overdue = isResponseOverdue(dispute, now)

  return (
    <div className={styles.page}>
      <Breadcrumb items={[
        { label: 'Home', to: '/' },
        { label: 'My Bookings', to: MY_BOOKINGS_PATH },
        { label: title },
      ]}
      />

      <div className={styles.titleRow}>
        <div className={styles.titleGroup}>
          <h1 className={styles.title}>{title}</h1>
          <StatusBadge
            status={dispute.status}
            viewerIsResponder={customerResponds}
            responderRole={responderRole}
            overdue={overdue}
          />
        </div>
        <Button
          variant="secondary"
          className={styles.noPrint}
          icon={<DisputeIcon name="print" size={16} />}
          onClick={() => window.print()}
        >
          Print case summary
        </Button>
      </div>

      <DeadlineBanner
        dispute={dispute}
        now={now}
        showStage
        waitingText={customerResponds ? 'Please respond within 72 hours.' : 'The provider has 72 hours to respond.'}
      />

      {customerResponds && dispute.status === 'AWAITING_RESPONSE' && (
        <InlineMessage variant="info">
          The provider raised this dispute. Responding as a customer will be available soon.
        </InlineMessage>
      )}

      <div className={styles.grid}>
        <div className={styles.column}>
          <BookingCard dispute={dispute} />
          <IssueCard dispute={dispute} title={customerResponds ? 'Issue reported by the provider' : 'Your issue'} />
          <ResponseView
            response={dispute.response}
            title={customerResponds ? 'Your response' : "Provider's response"}
          />
          <DisputeTimeline
            dispute={dispute}
            waitingTitle={customerResponds ? 'Waiting for your response' : "Waiting for the provider's response"}
            overdue={overdue}
          />
        </div>
        <div className={styles.column}>
          <WhatHappensNext status={dispute.status} />
        </div>
      </div>
    </div>
  )
}
