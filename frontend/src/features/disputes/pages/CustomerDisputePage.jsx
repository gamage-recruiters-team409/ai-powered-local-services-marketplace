import { useState } from 'react'
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
import { DisputeResponseForm } from '../components/DisputeResponseForm'
import { DisputeAccessDenied, DisputeErrorState, DisputeNotFound, DisputeSkeleton } from '../components/DisputeStates'
import { responderRoleOf } from '../config/disputeStatus'
import { useDispute } from '../hooks/useDispute'
import { useNow } from '../hooks/useNow'
import { isResponseOverdue } from '../utils/countdown'
import { disputeTitle } from '../utils/format'
import { responseClosedReason } from '../utils/responseState'
import styles from '../disputes.module.css'

const MY_BOOKINGS_PATH = '/customer/bookings'

/**
 * Customer "View dispute" (read-only). Rendered inside the Customer Common Layout with the account sidebar
 * (CUSTOMER_ACCOUNT_ROUTES); the customer guards handle sign-in and the CUSTOMER role.
 */
export function CustomerDisputePage() {
  const { disputeId } = useParams()
  const location = useLocation()
  const { status, dispute, error, reload, replace, refresh } = useDispute(disputeId)
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
    content = (
      <CustomerDisputeView key={dispute.id} dispute={dispute} now={now} onUpdated={replace} onReload={refresh} />
    )
  }

  return content
}

/**
 * Read-only when the customer raised the dispute. When the provider raised it, the customer is the responding party
 * and gets the shared response form (same rules, validation and error handling as the provider screen).
 */
function CustomerDisputeView({ dispute, now, onUpdated, onReload }) {
  const [justSubmitted, setJustSubmitted] = useState(false)
  const responderRole = responderRoleOf(dispute)
  const customerResponds = responderRole === 'CUSTOMER'
  const title = disputeTitle(dispute)
  const overdue = isResponseOverdue(dispute, now)
  const reason = responseClosedReason(dispute, now)

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

      <div aria-live="polite">
        {justSubmitted && (
          <InlineMessage variant="success">
            Your response was submitted. An admin will now review both sides.
          </InlineMessage>
        )}
      </div>

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
          {customerResponds && !dispute.response && (
            <DisputeResponseForm
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
          <WhatHappensNext status={dispute.status} viewerResponds={customerResponds} />
        </div>
      </div>
    </div>
  )
}
