import { Link, useParams } from 'react-router-dom'

import { InlineMessage, SkeletonCard, useToast } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { formatTimeLeft, getTimeLeft } from '../../disputes/utils/countdown'
import { formatDateTime, shortReference } from '../../disputes/utils/format'
import { StatusChip } from '../components/AdminDisputeChips'
import { Panel } from '../components/CasePanels'
import { DecisionForm } from '../components/DecisionForm'
import { ACTION_BLOCKED, ADMIN_DISPUTES_BASE, casePath, decisionText } from '../config/adminDisputeConfig'
import { useAdminDispute } from '../hooks/useAdminDispute'
import { decideAdminAppeal } from '../services/adminDisputesService'
import styles from '../adminDisputes.module.css'

/**
 * Appeal review (/admin/admin/disputes/:disputeId/appeal): the original decision, the appeal, the review deadline and
 * the UPHOLD / CHANGE form. The admin who made the original decision sees "A different admin must review this appeal"
 * and no form (the backend also answers 403 APPEAL_REVIEWER_MUST_DIFFER). SUPER_ADMIN is read-only.
 */
export function AdminAppealReviewPage() {
  const { disputeId } = useParams()
  const { status, dispute, error, reload, replace } = useAdminDispute(disputeId)
  const crumbs = [{ label: 'Admin', to: '/admin/admin' }, { label: 'Disputes', to: ADMIN_DISPUTES_BASE }]

  if (status === 'loading') {
    return (
      <AdminPage title="Appeal review" breadcrumbs={[...crumbs, 'Appeal']} plain>
        <div role="status" aria-label="Loading the appeal"><SkeletonCard /></div>
      </AdminPage>
    )
  }
  if (status === 'error') {
    return (
      <AdminPage title="Appeal review" breadcrumbs={[...crumbs, 'Appeal']}>
        <div className={styles.stateBox}>
          <InlineMessage variant="error">{error.message}</InlineMessage>
          <Button variant="secondary" onClick={reload}>Try again</Button>
        </div>
      </AdminPage>
    )
  }
  return <AppealView key={dispute.id} dispute={dispute} crumbs={crumbs} onUpdated={replace} />
}

function AppealView({ dispute, crumbs, onUpdated }) {
  const toast = useToast()
  const { canEdit } = useAreaAccess() ?? { canEdit: false }
  const ref = shortReference('D', dispute.id)
  const appeal = dispute.appeal
  const actions = dispute.actions ?? { canDecideAppeal: false, reasonIfNot: 'VIEW_ONLY' }
  const canDecide = canEdit && actions.canDecideAppeal
  const sameAdmin = actions.reasonIfNot === 'SAME_ADMIN_AS_DECISION'
  const sla = appeal?.reviewDueAt ? getTimeLeft(appeal.reviewDueAt) : null

  async function decide(body) {
    const updated = await decideAdminAppeal(dispute.id, body)
    toast.success('Appeal decided. The dispute is now final.')
    onUpdated(updated)
  }

  return (
    <AdminPage
      title={`Appeal review · ${ref}`}
      subtitle="A different admin reviews the appeal; the result is final."
      breadcrumbs={[...crumbs, { label: ref, to: casePath(dispute.id) }, 'Appeal']}
      actions={<Link to={casePath(dispute.id)} className={styles.secondaryLink}>Back to case</Link>}
      plain
    >
      {!appeal ? (
        <div className={styles.card}>
          <p className={styles.blockedNote}>No appeal has been filed for this dispute.</p>
        </div>
      ) : (
        <div className={styles.caseGrid}>
          <div className={styles.column}>
            <Panel title="Original decision" icon="shield" aside={<StatusChip status={dispute.status} />}>
              <p className={styles.decisionText}>{decisionText(dispute.decision)}</p>
              {dispute.decision && (
                <p className={styles.muted}>
                  Decided {formatDateTime(dispute.decision.decidedAt)} by admin <code>{dispute.decision.decidedBy}</code>
                </p>
              )}
              {dispute.decision?.note && <p className={styles.note}><strong>Internal note: </strong>{dispute.decision.note}</p>}
            </Panel>
            <Panel title="Appeal" icon="flag">
              <p className={styles.muted}>
                Filed by the {appeal.appealedByRole === 'PROVIDER' ? 'provider' : 'customer'} on {formatDateTime(appeal.filedAt)}
              </p>
              <p className={styles.quote}>{appeal.grounds}</p>
              <dl className={styles.facts}>
                <div><dt>Review due</dt><dd>{formatDateTime(appeal.reviewDueAt) || '–'}</dd></div>
                {dispute.status === 'APPEALED' && sla && (
                  <div><dt>Time left</dt><dd className={sla.overdue ? styles.overdue : ''}>{formatTimeLeft(sla)}</dd></div>
                )}
              </dl>
            </Panel>
          </div>
          <div className={styles.column}>
            <Panel title="Your review" icon="check">
              {appeal.verdict ? (
                <div className={styles.decisionSummary}>
                  <p className={styles.decisionText}>
                    {appeal.verdict === 'UPHOLD' ? 'Decision upheld' : `Decision changed: ${decisionText(dispute.finalDecision)}`}
                  </p>
                  <p className={styles.muted}>
                    Reviewed {formatDateTime(appeal.reviewedAt)} by admin <code>{appeal.reviewedBy}</code>. This decision is final.
                  </p>
                  {appeal.reviewNote && <p className={styles.note}><strong>Internal note: </strong>{appeal.reviewNote}</p>}
                </div>
              ) : canDecide ? (
                <DecisionForm mode="appeal" onSubmit={decide} />
              ) : (
                <p className={styles.blockedNote} role="note">
                  {sameAdmin ? ACTION_BLOCKED.SAME_ADMIN_AS_DECISION : (!canEdit ? ACTION_BLOCKED.VIEW_ONLY
                    : ACTION_BLOCKED[actions.reasonIfNot] ?? 'This appeal cannot be decided right now.')}
                </p>
              )}
            </Panel>
          </div>
        </div>
      )}
    </AdminPage>
  )
}
