import { Icon } from '../../../shared/layouts/admin'
import { UNAVAILABLE_FALLBACK } from '../config/operationsConfig'
import styles from '../operations.module.css'

/**
 * Neutral placeholder for a section whose source is unavailable (available === false). Shows only the reason — never a
 * number, a zero or a fake chart.
 */
export function UnavailableSection({ reason, icon = 'barChart', title }) {
  return (
    <div className={styles.placeholder}>
      <Icon name={icon} size={22} />
      {title && <strong>{title}</strong>}
      <p>{reason || UNAVAILABLE_FALLBACK}</p>
    </div>
  )
}
