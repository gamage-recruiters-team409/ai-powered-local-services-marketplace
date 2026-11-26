import { Link, useParams } from 'react-router-dom'

import { InlineMessage, SkeletonCard, useToast } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { EvidenceSection } from '../../disputes/components/EvidenceSection'
import { formatDateTime, shortReference } from '../../disputes/utils/format'
import disputeStyles from '../../disputes/disputes.module.css'
import { StatusChip } from '../components/AdminDisputeChips'
import { Panel, PlaceholderPanel, SettlementPanel } from '../components/CasePanels'
import { DecisionForm } from '../components/DecisionForm'
import {
  ACTION_BLOCKED,
  ADMIN_DISPUTES_BASE,
  appealPath,
  categoryName,
  decisionText,
  STATUS,
} from '../config/adminDisputeConfig'
import { useAdminDispute } from '../hooks/useAdminDispute'
import { resolveAdminDispute } from '../services/adminDisputesService'
import styles from '../adminDisputes.module.css'

function roleWord(role) {
  if (role === 'CUSTOMER') return 'Customer'
  if (role === 'PROVIDER') return 'Provider'
  if (role) return 'Admin'
  return 'System'
}

/** Admin dispute case (/admin/admin/disputes/:disputeId). SUPER_ADMIN sees everything read-only. */
export function AdminDisputeCasePage() {
  const { disputeId } = useParams()
  const { status, dispute, error, reload, replace } = useAdminDispute(disputeId)
  const crumbs = [{ label: 'Admin', to: '/admin/admin' }, { label: 'Disputes', to: ADMIN_DISPUTES_BASE }]

  if (status === 'loading') {
    return (
      <AdminPage title="Dispute case" breadcrumbs={[...crumbs, 'Case']} plain>
        <div role="status" aria-label="Loading the case"><SkeletonCard /><SkeletonCard /></div>
      </AdminPage>
    )
  }
  if (status === 'error') {
    return (
      <AdminPage title="Dispute case" breadcrumbs={[...crumbs, 'Case']}>
        <div className={styles.stateBox}>
          <InlineMessage variant="error">{error.message}</InlineMessage>
          {error.kind === 'not-found'
            ? <Link to={ADMIN_DISPUTES_BASE} className={styles.rowLink}>Back to disputes</Link>
            : <Button variant="secondary" onClick={reload}>Try again</Button>}
        </div>
      </AdminPage>
    )
  }
  return <CaseView key={dispute.id} dispute={dispute} crumbs={crumbs} onUpdated={replace} />
}

function CaseView({ dispute, crumbs, onUpdated }) {
  const toast = useToast()
  const { canEdit } = useAreaAccess() ?? { canEdit: false }
  const ref = shortReference('D', dispute.id)
  const actions = dispute.actions ?? { canResolve: false, canDecideAppeal: false, reasonIfNot: 'VIEW_ONLY' }
  const canResolve = canEdit && actions.canResolve
  const blocked = !canEdit ? ACTION_BLOCKED.VIEW_ONLY : ACTION_BLOCKED[actions.reasonIfNot]
  const raiser = dispute.raisedByRole === 'PROVIDER' ? dispute.providerName : dispute.customerName
  const responder = dispute.raisedByRole === 'PROVIDER' ? 'CUSTOMER' : 'PROVIDER'

  async function resolve(body) {
    const updated = await resolveAdminDispute(dispute.id, body)
    toast.success('Decision saved. The parties can now appeal within 48 hours.')
    onUpdated(updated)
  }

  return (
    <AdminPage
      title={`Case ${ref}`}
      subtitle={`${categoryName(dispute.category)} · raised by the ${roleWord(dispute.raisedByRole).toLowerCase()}`}
      breadcrumbs={[...crumbs, ref]}
      actions={dispute.appeal && (
        <Link to={appealPath(dispute.id)} className={styles.primaryLink}>
          {dispute.status === 'APPEALED' ? 'Review appeal' : 'View appeal'}
        </Link>
      )}
      plain
    >
      <section className={`${styles.card} ${styles.caseHeader}`} aria-label="Case details">
        <div className={styles.caseHeaderTop}>
          <StatusChip status={dispute.status} />
          {!canEdit && <span className={styles.viewOnlyTag}>View only</span>}
        </div>
        <dl className={styles.facts}>
          <div><dt>Case id</dt><dd><code>{dispute.id}</code></dd></div>
          <div><dt>Booking</dt><dd>{shortReference('B', dispute.bookingId)}</dd></div>
          <div><dt>Category</dt><dd>{categoryName(dispute.category)}</dd></div>
          <div><dt>Raised by</dt><dd>{roleWord(dispute.raisedByRole)}{raiser ? ` · ${raiser}` : ''}</dd></div>
          <div><dt>Customer</dt><dd>{dispute.customerName ?? dispute.customerId}</dd></div>
          <div><dt>Provider</dt><dd>{dispute.providerName ?? dispute.providerId}</dd></div>
          <div><dt>Raised</dt><dd>{formatDateTime(dispute.createdAt)}</dd></div>
          <div><dt>Response deadline</dt><dd>{formatDateTime(dispute.responseDueAt) || '–'}</dd></div>
          {dispute.appealDeadline && <div><dt>Appeal deadline</dt><dd>{formatDateTime(dispute.appealDeadline)}</dd></div>}
          {dispute.finalizedAt && <div><dt>Final since</dt><dd>{formatDateTime(dispute.finalizedAt)}</dd></div>}
        </dl>
      </section>

      <div className={styles.caseGrid}>
        <div className={styles.column}>
          <Panel title="Statements" icon="message">
            <article className={styles.statement}>
              <h3 className={styles.statementTitle}>
                {roleWord(dispute.raisedByRole)} (raised the dispute)
                <span className={styles.muted}> · {formatDateTime(dispute.createdAt)}</span>
              </h3>
              <p className={styles.quote}>{dispute.description}</p>
            </article>
            <article className={styles.statement}>
              <h3 className={styles.statementTitle}>
                {roleWord(responder)} (response)
                {dispute.response?.respondedAt && <span className={styles.muted}> · {formatDateTime(dispute.response.respondedAt)}</span>}
              </h3>
              {dispute.response
                ? <p className={styles.quote}>{dispute.response.text}</p>
                : <p className={styles.muted}>No response yet.</p>}
            </article>
          </Panel>

          <div className={disputeStyles.page}>
            <EvidenceSection dispute={dispute} viewerRole={null} canUpload={false} />
          </div>

          <Panel title="Status history" icon="clock">
            <ol className={styles.timeline}>
              {(dispute.statusHistory ?? []).map((entry, index) => (
                <li key={`${index}-${entry.timestamp}`} className={styles.timelineItem}>
                  <span className={styles.timelineDot} aria-hidden="true" />
                  <div>
                    <p className={styles.timelineTitle}>{STATUS[entry.toStatus]?.label ?? entry.toStatus}</p>
                    <p className={styles.muted}>
                      {roleWord(entry.actorRole)} · {formatDateTime(entry.timestamp)}
                      {entry.reason && ` · ${entry.reason}`}
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          </Panel>
        </div>

        <div className={styles.column}>
          <Panel title="Decision" icon="shield">
            {dispute.decision ? (
              <div className={styles.decisionSummary}>
                <p className={styles.decisionText}>{decisionText(dispute.decision)}</p>
                <p className={styles.muted}>
                  Decided {formatDateTime(dispute.decision.decidedAt)} by admin <code>{dispute.decision.decidedBy}</code>
                </p>
                {dispute.decision.note && <p className={styles.note}><strong>Internal note: </strong>{dispute.decision.note}</p>}
                {dispute.finalDecision && dispute.appealStatus === 'CHANGED' && (
                  <p className={styles.reason}>Changed on appeal to: {decisionText(dispute.finalDecision)}</p>
                )}
              </div>
            ) : canResolve ? (
              <DecisionForm mode="resolve" onSubmit={resolve} />
            ) : (
              <p className={styles.blockedNote}>{blocked ?? 'No decision yet.'}</p>
            )}
          </Panel>

          {dispute.appeal && (
            <Panel title="Appeal" icon="flag">
              <p className={styles.muted}>
                Filed by the {roleWord(dispute.appeal.appealedByRole).toLowerCase()} on {formatDateTime(dispute.appeal.filedAt)}
              </p>
              <p className={styles.quote}>{dispute.appeal.grounds}</p>
              <Link to={appealPath(dispute.id)} className={styles.rowLink}>
                {dispute.status === 'APPEALED' ? 'Open the appeal review' : 'View the appeal review'}
              </Link>
            </Panel>
          )}

          <SettlementPanel settlement={dispute.settlement} />

          <PlaceholderPanel title="Chat history" icon="message" text="Chat history will appear when messaging is connected." />
          <PlaceholderPanel title="AI summary" icon="star" text="AI summary will appear when AI-005 is connected." />
        </div>
      </div>
    </AdminPage>
  )
}
