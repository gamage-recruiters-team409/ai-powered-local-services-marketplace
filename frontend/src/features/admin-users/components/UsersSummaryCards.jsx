import { formatNumber } from '../utils/format'
import { UsersIcon } from './UsersIcon'
import styles from '../adminUsers.module.css'

const CARDS = [
  { key: 'customers', label: 'Customers', icon: 'user', tone: 'primary' },
  { key: 'providers', label: 'Providers', icon: 'briefcase', tone: 'primary' },
  { key: 'suspended', label: 'Suspended', icon: 'ban', tone: 'danger' },
]

/**
 * The three header cards. A number is shown only when the API says it is available; the weekly line only when the
 * API returns it. Nothing is invented (no "healthy acquisition" or trend lines without data).
 */
export function UsersSummaryCards({ summary, loading }) {
  return (
    <div className={styles.summaryGrid}>
      {CARDS.map((card) => {
        const section = summary?.[card.key]
        const available = Boolean(section?.available)
        const weekly = section?.data?.joinedLast7Days
        return (
          <article key={card.key} className={styles.summaryCard} aria-busy={loading || undefined}>
            <div className={styles.summaryTop}>
              <h2 className={styles.summaryLabel}>{card.label}</h2>
              <span className={`${styles.summaryIcon} ${styles[`summaryIcon_${card.tone}`]}`} aria-hidden="true">
                <UsersIcon name={card.icon} size={17} />
              </span>
            </div>
            {loading ? (
              <span className={styles.summarySkeleton} aria-hidden="true" />
            ) : available ? (
              <p className={styles.summaryValue}>{formatNumber(section.data.total)}</p>
            ) : (
              <p className={styles.summaryValue}>
                <span aria-hidden="true">—</span>
                <span className={styles.notAvailable}>Not available yet</span>
              </p>
            )}
            {available && weekly?.available && (
              <p className={styles.summaryTrend}>
                <UsersIcon name="arrowRight" size={12} /> +{formatNumber(weekly.data)} this week
              </p>
            )}
          </article>
        )
      })}
    </div>
  )
}
