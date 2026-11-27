import { SETTLEMENT_STATE, STATUS } from '../config/adminDisputeConfig'
import styles from '../adminDisputes.module.css'

/** Dispute status chip (same colours as the party screens: amber waiting, blue in review, green decided). */
export function StatusChip({ status }) {
  const config = STATUS[status] ?? { label: status ?? 'Unknown', tone: 'grey' }
  return (
    <span className={`${styles.chip} ${styles[`tone_${config.tone}`]}`} data-status={status}>
      <span className={styles.chipDot} aria-hidden="true" />
      {config.label}
    </span>
  )
}

/** Settlement chip for final cases ("–" when there is no settlement yet). */
export function SettlementChip({ state }) {
  if (!state) {
    return <span className={styles.muted} aria-label="No settlement">–</span>
  }
  const config = SETTLEMENT_STATE[state] ?? { label: state, tone: 'grey' }
  return (
    <span className={`${styles.chip} ${styles[`tone_${config.tone}`]}`} data-settlement={state}>
      {config.label}
    </span>
  )
}
