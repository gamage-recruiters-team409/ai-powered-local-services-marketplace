import { useId } from 'react'
import { useNavigate } from 'react-router-dom'

import styles from './ProviderLayout.module.css'
import { ProviderIcon } from './ProviderIcon'

export function ProviderPage({
  title,
  subtitle,
  showBack = true,
  onBack,
  plain = false,
  children,
}) {
  const titleId = useId()
  const navigate = useNavigate()

  const handleBack = () => {
    if (onBack) {
      onBack()
      return
    }

    navigate(-1)
  }

  return (
    <section
      className={styles.page}
      aria-labelledby={titleId}
    >
      <div className={styles.pageHeader}>
        {showBack && (
          <button
            type="button"
            className={styles.mobileBackButton}
            aria-label="Go back"
            onClick={handleBack}
          >
            <ProviderIcon name="back" size={20} />
          </button>
        )}

        <div className={styles.pageHeading}>
          <h1
            id={titleId}
            className={styles.pageTitle}
          >
            {title}
          </h1>

          {subtitle && (
            <p className={styles.pageSubtitle}>
              {subtitle}
            </p>
          )}
        </div>
      </div>

      {plain ? (
        children
      ) : (
        <div className={styles.contentPanel}>
          {children}
        </div>
      )}
    </section>
  )
}