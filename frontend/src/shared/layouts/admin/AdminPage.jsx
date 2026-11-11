import { useId } from 'react'
import { Link } from 'react-router-dom'

import styles from './AdminLayout.module.css'

/**
 * Standard admin page frame: breadcrumbs, title, subtitle, optional actions, content card.
 *
 * @param {object} props
 * @param {string} props.title
 * @param {string} [props.subtitle]
 * @param {Array<string|{label: string, to?: string}>} [props.breadcrumbs]
 * @param {*}      [props.actions]  buttons shown next to the title
 * @param {boolean} [props.plain]   true = render children without the white card
 */
export function AdminPage({ title, subtitle, breadcrumbs = [], actions, plain = false, children }) {
  const titleId = useId()
  const crumbs = breadcrumbs.map((crumb) => (typeof crumb === 'string' ? { label: crumb } : crumb))

  return (
    <section className={styles.page} aria-labelledby={titleId}>
      {crumbs.length > 0 && (
        <nav aria-label="Breadcrumb" className={styles.breadcrumbs}>
          <ol>
            {crumbs.map((crumb, index) => {
              const isLast = index === crumbs.length - 1
              return (
                <li key={`${crumb.label}-${index}`} aria-current={isLast ? 'page' : undefined}>
                  {crumb.to && !isLast ? <Link to={crumb.to}>{crumb.label}</Link> : crumb.label}
                </li>
              )
            })}
          </ol>
        </nav>
      )}

      <div className={styles.pageHeader}>
        <div>
          <h1 id={titleId} className={styles.pageTitle}>
            {title}
          </h1>
          {subtitle && <p className={styles.pageSubtitle}>{subtitle}</p>}
        </div>
        {actions && <div className={styles.pageActions}>{actions}</div>}
      </div>

      {plain ? children : <div className={styles.card}>{children}</div>}
    </section>
  )
}
