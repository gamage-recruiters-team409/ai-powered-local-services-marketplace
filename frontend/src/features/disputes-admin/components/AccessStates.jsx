import { cx, Icon } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'

/** Full-screen wrapper for states shown before the admin layout can render. */
export function StatePage({ children }) {
  return (
    <main className={styles.statePage}>
      <div className={styles.stateBox}>{children}</div>
    </main>
  )
}

export function LoadingState({ label = 'Loading admin access…' }) {
  return (
    <div className={styles.state} role="status" aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <p className={styles.stateText}>{label}</p>
    </div>
  )
}

export function ErrorState({ onRetry }) {
  return (
    <div className={styles.state} role="alert">
      <span className={cx(styles.stateIcon, styles.stateIconDanger)}>
        <Icon name="alertCircle" size={24} />
      </span>
      <h1 className={styles.stateTitle}>Couldn&apos;t load admin access</h1>
      <p className={styles.stateText}>
        Check your connection and try again. If it keeps failing, contact the platform team.
      </p>
      <div className={styles.actions}>
        <button type="button" className={cx(styles.button, styles.buttonPrimary)} onClick={onRetry}>
          Try again
        </button>
      </div>
    </div>
  )
}

/** 403 from /admin/access: signed in, but not an admin account. */
export function NoAdminAccessState({ onSignOut }) {
  return (
    <div className={styles.state} role="alert">
      <span className={cx(styles.stateIcon, styles.stateIconDanger)}>
        <Icon name="lock" size={22} />
      </span>
      <h1 className={styles.stateTitle}>Access denied</h1>
      <p className={styles.stateText}>This account doesn&apos;t have access to the admin portal.</p>
      <div className={styles.actions}>
        <button type="button" className={cx(styles.button, styles.buttonPrimary)} onClick={onSignOut}>
          Sign in with another account
        </button>
      </div>
    </div>
  )
}

/** 200 from /admin/access but no editable or viewable areas. */
export function EmptyAccessState({ onSignOut }) {
  return (
    <div className={styles.state} role="status">
      <span className={styles.stateIcon}>
        <Icon name="lock" size={22} />
      </span>
      <h1 className={styles.stateTitle}>No admin areas assigned</h1>
      <p className={styles.stateText}>
        You&apos;re signed in, but no admin areas are available to your account. Contact a Super Admin.
      </p>
      <div className={styles.actions}>
        <button type="button" className={styles.button} onClick={onSignOut}>
          Log out
        </button>
      </div>
    </div>
  )
}
