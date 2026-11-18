import { Link } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerCountBadge } from './CustomerCountBadge'
import { CustomerIcon } from './CustomerIcon'
import { normalizeCount } from './customerLayoutUtils'

/**
 * Square header icon link with a corner count badge (Messages / Notifications in the design).
 * Pass it to CustomerLayout as `messagesAction` or `notificationsAction`.
 */
export function CustomerHeaderIconLink({
  to,
  icon,
  label,
  count = 0,
}) {
  const safeCount = normalizeCount(count)
  const accessibleLabel = safeCount > 0 ? `${label}, ${safeCount} unread` : label

  return (
    <Link
      to={to}
      className={styles.headerIconButton}
      aria-label={accessibleLabel}
      title={accessibleLabel}
    >
      <CustomerIcon name={icon} size={20} />
      <CustomerCountBadge count={safeCount} variant="corner" />
    </Link>
  )
}
