import { CASE_STATUS_CHIP, ROLE_LABEL, URGENCY_CHIP } from '../config/operationsConfig'
import { formatDays, formatDelay } from '../utils/format'
import styles from '../operations.module.css'

export function Chip({ variant = 'neutral', plain = false, children }) {
  return <span className={`${styles.chip} ${styles[`chip_${variant}`]} ${plain ? styles.chip_plain : ''}`}>{children}</span>
}

export function CaseStatusChip({ status }) {
  const chip = CASE_STATUS_CHIP[status] ?? { label: status ?? 'Open', variant: 'neutral' }
  return <Chip variant={chip.variant}>{chip.label}</Chip>
}

export function UrgencyChip({ urgency }) {
  const chip = URGENCY_CHIP[urgency] ?? { label: urgency ?? '—', variant: 'neutral' }
  return <Chip variant={chip.variant}>{chip.label}</Chip>
}

export function DelayChip({ minutesLate }) {
  return (
    <Chip variant="danger" plain>
      {formatDelay(minutesLate)}
    </Chip>
  )
}

export function DaysOverdueChip({ days }) {
  const variant = days >= 3 ? 'danger' : days >= 1 ? 'warning' : 'neutral'
  return (
    <Chip variant={variant} plain>
      {formatDays(days)}
    </Chip>
  )
}

export function RoleChip({ role }) {
  return (
    <Chip variant="primary" plain>
      {ROLE_LABEL[role] ?? role ?? '—'}
    </Chip>
  )
}

/** Only shown when the API flags the row as a repeat offender. */
export function RepeatChip() {
  return (
    <Chip variant="warning" plain>
      Repeat
    </Chip>
  )
}

/** Small tag marking rows surfaced from NO_SHOW disputes. */
export function SourceTag({ children = 'No-show dispute' }) {
  return <span className={styles.sourceTag}>{children}</span>
}

export function ActionRequiredChip({ count }) {
  if (!count) return null
  return (
    <span className={styles.actionRequired}>
      <Chip variant="danger" plain>
        {count} Action Required
      </Chip>
    </span>
  )
}
