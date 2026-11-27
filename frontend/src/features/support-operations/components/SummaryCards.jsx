import { Icon } from '../../../shared/layouts/admin'
import { formatNumber } from '../utils/format'
import styles from '../operations.module.css'

/**
 * One metric card. {@code available === false} shows the reason instead of a number (never a fake zero).
 *
 * @param {{ label, value, available?, reason?, icon?, tone?, foot?, loading? }} props
 */
export function SummaryCard({ label, value, available = true, reason, icon = 'clock', tone = 'primary', foot, loading }) {
  const danger = tone === 'danger'
  return (
    <article className={styles.summaryCard}>
      <div className={styles.summaryTop}>
        <p className={styles.summaryLabel}>{label}</p>
        <span className={`${styles.summaryIcon} ${danger ? styles.summaryIcon_danger : ''}`}>
          <Icon name={icon} size={18} />
        </span>
      </div>
      {loading ? (
        <span className={styles.summarySkeleton} aria-hidden="true" />
      ) : available ? (
        <p className={`${styles.summaryValue} ${danger ? styles.summaryValue_danger : ''}`}>{formatNumber(value)}</p>
      ) : (
        <p className={styles.summaryFoot}>{reason || 'Not available yet.'}</p>
      )}
      {available && foot && !loading && <p className={styles.summaryFoot}>{foot}</p>}
    </article>
  )
}

export function SummaryCards({ cards, loading, columns }) {
  return (
    <div className={`${styles.summaryGrid} ${columns === 4 ? styles.summaryGrid4 : ''}`}>
      {cards.map((card) => (
        <SummaryCard key={card.label} {...card} loading={loading} />
      ))}
    </div>
  )
}
