import { getStatusBadge } from '../../disputes/config/disputeStatus'
import {
  ADMIN_ACTION_META,
  BOOKING_STATUS_META,
  LISTING_STATUS_META,
  ROLE_LABEL,
  STATUS_META,
} from '../config/usersConfig'
import { initials } from '../utils/format'
import { UsersIcon } from './UsersIcon'
import styles from '../adminUsers.module.css'

/** tone: success | danger | warning | info | neutral | primary */
export function Chip({ tone = 'neutral', icon, dot = false, children, title }) {
  return (
    <span className={`${styles.chip} ${styles[`tone_${tone}`] ?? ''}`} title={title}>
      {dot && <span className={styles.chipDot} aria-hidden="true" />}
      {icon && <UsersIcon name={icon} size={12} />}
      {children}
    </span>
  )
}

export function UserStatusChip({ status }) {
  const meta = STATUS_META[status] ?? { label: status ?? 'Unknown', tone: 'neutral' }
  return (
    <Chip tone={meta.tone} dot>
      {meta.label}
    </Chip>
  )
}

export function RoleChip({ role }) {
  return (
    <Chip tone={role === 'PROVIDER' ? 'info' : 'neutral'} icon={role === 'PROVIDER' ? 'briefcase' : 'user'}>
      {ROLE_LABEL[role] ?? role}
    </Chip>
  )
}

export function ProChip() {
  return <span className={styles.proChip}>PRO</span>
}

export function VerifiedChip() {
  return (
    <Chip tone="success" icon="checkCircle">
      Verified
    </Chip>
  )
}

export function ListingStatusChip({ status }) {
  const meta = LISTING_STATUS_META[status] ?? { label: status ?? 'Unknown', tone: 'neutral' }
  return <Chip tone={meta.tone}>{meta.label}</Chip>
}

export function BookingStatusChip({ status }) {
  const meta = BOOKING_STATUS_META[status] ?? { label: status ?? 'Unknown', tone: 'neutral' }
  return <Chip tone={meta.tone}>{meta.label}</Chip>
}

/** Same labels and colours as the dispute module; APPEALED is a proposed status it does not map yet. */
export function DisputeStatusChip({ status }) {
  const badge = status === 'APPEALED' ? { label: 'Appealed', tone: 'info' } : getStatusBadge(status)
  return (
    <Chip tone={badge.tone} dot>
      {badge.label}
    </Chip>
  )
}

export function AdminActionChip({ action }) {
  const meta = ADMIN_ACTION_META[action] ?? ADMIN_ACTION_META.STATUS_CHANGED
  return <Chip tone={meta.tone}>{meta.label}</Chip>
}

export function Avatar({ name, size = 'md', tone }) {
  return (
    <span className={`${styles.avatar} ${styles[`avatar_${size}`]} ${tone ? styles[`avatarTone_${tone}`] : ''}`} aria-hidden="true">
      {initials(name)}
    </span>
  )
}
