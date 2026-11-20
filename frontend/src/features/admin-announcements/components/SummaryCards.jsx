import { AnnIcon } from './AnnIcon'
import styles from '../announcements.module.css'

const CARDS = [
  { key: 'active', label: 'Active', icon: 'megaphone', tone: 'green', hint: 'Published and pinned to the header' },
  { key: 'scheduled', label: 'Scheduled', icon: 'clock', tone: 'amber', hint: 'Waiting for their publish time' },
  { key: 'total', label: 'Total notices', icon: 'archive', tone: 'grey', hint: 'All announcements' },
]

/** Active / Scheduled / Total notices from GET /summary. Shows "–" while loading or when it failed. */
export function SummaryCards({ summary }) {
  return (
    <ul className={styles.summaryCards} aria-label="Announcement summary">
      {CARDS.map((card) => (
        <li key={card.key} className={styles.summaryCard} title={card.hint}>
          <span className={`${styles.summaryIcon} ${styles[`tone_${card.tone}`]}`}>
            <AnnIcon name={card.icon} size={16} />
          </span>
          <span className={styles.summaryText}>
            <span className={styles.summaryLabel}>{card.label}</span>
            <strong className={styles.summaryValue} data-testid={`summary-${card.key}`}>
              {summary ? summary[card.key] : '–'}
            </strong>
          </span>
        </li>
      ))}
    </ul>
  )
}
