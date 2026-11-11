import { Link } from 'react-router-dom'

import { AdminPage, cx } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'
import { getAdminHomePath } from '../access/accessRules'
import { useAdminAccess } from '../access/useAdminAccess'

export function AdminNotFoundPage() {
  const { access } = useAdminAccess()
  return (
    <AdminPage title="Page not found" breadcrumbs={['Not found']}>
      <div className={styles.state}>
        <p className={styles.stateText}>This admin page doesn&apos;t exist.</p>
        <div className={styles.actions}>
          <Link className={cx(styles.button, styles.buttonPrimary)} to={getAdminHomePath(access)}>
            Go to my admin home
          </Link>
        </div>
      </div>
    </AdminPage>
  )
}
