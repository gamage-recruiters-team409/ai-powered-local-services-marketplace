import { Icon } from '../../../shared/layouts/admin'
import { reasonText, SETTLEMENT_STATE } from '../config/adminDisputeConfig'
import { formatDateTime } from '../../disputes/utils/format'
import { SettlementChip } from './AdminDisputeChips'
import styles from '../adminDisputes.module.css'

/** A titled card on the case page. */
export function Panel({ title, icon, aside, children, id }) {
  const titleId = id ?? `panel-${title.replace(/\s+/g, '-').toLowerCase()}`
  return (
    <section className={styles.panel} aria-labelledby={titleId}>
      <div className={styles.panelHeader}>
        <h2 id={titleId} className={styles.panelTitle}>
          {icon && <span className={styles.panelIcon} aria-hidden="true"><Icon name={icon} size={16} /></span>}
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

function StepRow({ label, state }) {
  return (
    <div className={styles.stepRow}>
      <span>{label}</span>
      <SettlementChip state={state ?? 'PENDING'} />
    </div>
  )
}

/**
 * Read-only settlement progress for a final case: finance first, then the booking step; the overall state and its
 * reason in plain words. Shown only to admins.
 */
export function SettlementPanel({ settlement }) {
  if (!settlement) {
    return null
  }
  const overall = SETTLEMENT_STATE[settlement.state]
  const reason = reasonText(settlement.manualReviewReason) ?? reasonText(settlement.notAppliedReason)
  return (
    <Panel title="Settlement" icon="creditCard" aside={<SettlementChip state={settlement.state} />}>
      <p className={styles.muted}>{overall?.text}</p>
      {reason && <p className={styles.reason}>{reason}</p>}
      <div className={styles.steps}>
        <StepRow label="1. Finance (payout, refund, penalty)" state={settlement.financeState} />
        <StepRow label="2. Booking status" state={settlement.bookingState} />
      </div>
      <dl className={styles.facts}>
        <div><dt>Failed attempts</dt><dd>{settlement.attempts ?? 0}</dd></div>
        {settlement.lastAttemptAt && <div><dt>Last attempt</dt><dd>{formatDateTime(settlement.lastAttemptAt)}</dd></div>}
        {settlement.nextAttemptAt && <div><dt>Next retry</dt><dd>{formatDateTime(settlement.nextAttemptAt)}</dd></div>}
        {settlement.lastErrorCode && <div><dt>Last error</dt><dd><code>{settlement.lastErrorCode}</code></dd></div>}
      </dl>
    </Panel>
  )
}

/** Neat placeholder card for a part that is not connected yet (no fake data). */
export function PlaceholderPanel({ title, icon, text }) {
  return (
    <Panel title={title} icon={icon} aside={<span className={styles.soonTag}>Not connected</span>}>
      <div className={styles.placeholder}>
        <p>{text}</p>
      </div>
    </Panel>
  )
}
