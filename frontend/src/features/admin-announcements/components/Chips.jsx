import { Badge } from '../../../shared/components/forms'
import { AUDIENCE_META, STATUS_META } from '../config/announcementConfig'
import { AnnIcon } from './AnnIcon'
import styles from '../announcements.module.css'

export function StatusChip({ status }) {
  const meta = STATUS_META[status] ?? { label: status ?? 'Unknown', tone: 'neutral' }
  return (
    <Badge variant={meta.tone} size="small" className={styles.chip} data-status={status}>
      <span className={styles.chipDot} aria-hidden="true" />
      {meta.label}
    </Badge>
  )
}

export function AudienceChip({ audience }) {
  const meta = AUDIENCE_META[audience] ?? { label: audience ?? 'Unknown', icon: 'users' }
  return (
    <Badge
      variant="neutral"
      size="small"
      className={`${styles.chip} ${styles.audienceChip}`}
      data-audience={audience}
      icon={<AnnIcon name={meta.icon} size={12} />}
    >
      {meta.label}
    </Badge>
  )
}

/** Small neutral chip for delivery options (Push alert, Pinned until ...). */
export function InfoChip({ icon, children }) {
  return (
    <span className={styles.infoChip}>
      {icon && <AnnIcon name={icon} size={13} />}
      {children}
    </span>
  )
}
