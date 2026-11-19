import { useId } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerIcon } from './CustomerIcon'

function Breadcrumbs({ items }) {
  if (!items?.length) {
    return null
  }

  return (
    <nav aria-label="Breadcrumb" className={styles.breadcrumbs}>
      <ol>
        {items.map((item, index) => {
          const last = index === items.length - 1

          return (
            <li key={`${item.label}-${index}`}>
              {last || !item.to ? (
                <span aria-current={last ? 'page' : undefined}>{item.label}</span>
              ) : (
                <Link to={item.to}>{item.label}</Link>
              )}

              {!last && (
                <CustomerIcon name="chevronRight" size={14} className={styles.breadcrumbSeparator} />
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

/**
 * Standard page frame inside the customer layout:
 * breadcrumbs, page title, optional actions (e.g. a primary button) and the page content.
 * On mobile the breadcrumbs are replaced by a back button.
 */
export function CustomerPage({
  title,
  breadcrumbs = [],
  actions = null,
  showBack = true,
  onBack,
  children,
}) {
  const titleId = useId()
  const navigate = useNavigate()

  const handleBack = () => {
    if (typeof onBack === 'function') {
      onBack()
      return
    }

    navigate(-1)
  }

  return (
    <section className={styles.page} aria-labelledby={titleId}>
      <Breadcrumbs items={breadcrumbs} />

      <div className={styles.pageHeader}>
        {showBack && (
          <button
            type="button"
            className={styles.mobileBackButton}
            aria-label="Go back"
            onClick={handleBack}
          >
            <CustomerIcon name="back" size={20} />
          </button>
        )}

        <h1 id={titleId} className={styles.pageTitle}>
          {title}
        </h1>

        {actions && <div className={styles.pageActions}>{actions}</div>}
      </div>

      {children}
    </section>
  )
}
