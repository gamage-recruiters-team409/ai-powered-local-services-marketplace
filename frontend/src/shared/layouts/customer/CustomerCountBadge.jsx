import styles from './CustomerLayout.module.css'
import { cx, normalizeCount } from './customerLayoutUtils'

/**
 * Amber count badge with a dark number (agreed with WP06).
 * Renders nothing when the count is 0 or invalid.
 */
export function CustomerCountBadge({
  count = 0,
  variant = 'inline',
  label,
}) {
  const safeCount = normalizeCount(count)

  if (safeCount === 0) {
    return null
  }

  return (
    <span
      className={cx(
        styles.countBadge,
        variant === 'corner' && styles.countBadgeCorner,
      )}
      aria-label={label}
      aria-hidden={label ? undefined : 'true'}
    >
      {safeCount > 99 ? '99+' : safeCount}
    </span>
  )
}
