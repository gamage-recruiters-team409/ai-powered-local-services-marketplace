import { useId } from 'react'

import { Skeleton } from '../../../shared/components/feedback'
import { UsersIcon } from './UsersIcon'
import styles from '../adminUsers.module.css'

/** A titled card section of the account page. */
export function Panel({ title, subtitle, icon, count, action, children, className = '' }) {
  const id = useId()
  return (
    <section className={`${styles.panel} ${className}`} aria-labelledby={id}>
      <header className={styles.panelHead}>
        <div className={styles.panelHeading}>
          {icon && (
            <span className={styles.panelIcon} aria-hidden="true">
              <UsersIcon name={icon} size={15} />
            </span>
          )}
          <div>
            <h2 id={id} className={styles.panelTitle}>
              {title}
              {count !== undefined && count !== null && <span className={styles.countBadge}>{count}</span>}
            </h2>
            {subtitle && <p className={styles.panelSubtitle}>{subtitle}</p>}
          </div>
        </div>
        {action}
      </header>
      <div className={styles.panelBody}>{children}</div>
    </section>
  )
}

/**
 * Placeholder for a section whose data source is not connected (or failed). Never shows numbers.
 * reason: NOT_CONNECTED | SOURCE_ERROR
 */
export function Unavailable({ what, service, reason }) {
  const failed = reason === 'SOURCE_ERROR'
  return (
    <div className={styles.unavailable} role="note">
      <span className={styles.unavailableIcon} aria-hidden="true">
        <UsersIcon name={failed ? 'alertTriangle' : 'inbox'} size={18} />
      </span>
      <p>
        {failed
          ? `${what} could not be loaded right now. Try again later.`
          : `${what} will appear when ${service} is connected.`}
      </p>
    </div>
  )
}

export function PanelSkeleton({ rows = 3 }) {
  return (
    <div className={styles.panelSkeleton} aria-hidden="true">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={14} width={`${90 - i * 15}%`} />
      ))}
    </div>
  )
}
