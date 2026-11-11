import { Link, useNavigate } from 'react-router-dom'

import { AdminPage, cx, Icon } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'
import { ADMIN_ROLES, areaPath, roleLabel } from '../config/adminAreas'
import { getAdminHomePath } from '../access/accessRules'
import { useAdminAccess } from '../access/useAdminAccess'
import { endSession, LOGIN_PATH } from '../services/authBridge'

/** Shown inside the layout when the user opens an area that is in neither canEdit nor canView. */
export function AccessDeniedPage({ area }) {
  const { access } = useAdminAccess()
  const navigate = useNavigate()
  const isSuperAdminArea = area === ADMIN_ROLES.SUPER_ADMIN

  const signInAsSuperAdmin = () => {
    endSession()
    navigate(LOGIN_PATH, { replace: true, state: { from: areaPath(ADMIN_ROLES.SUPER_ADMIN) } })
  }

  return (
    <AdminPage title="Access denied" breadcrumbs={['Access denied']}>
      <div className={styles.state} role="alert">
        <span className={cx(styles.stateIcon, styles.stateIconDanger)}>
          <Icon name="lock" size={22} />
        </span>
        <p className={styles.stateText}>
          Your role ({roleLabel(access.role)}) can&apos;t open the {roleLabel(area)} area.
          {isSuperAdminArea && ' The Super Admin area needs a Super Admin sign-in.'}
        </p>
        <div className={styles.actions}>
          <Link className={cx(styles.button, styles.buttonPrimary)} to={getAdminHomePath(access)}>
            Go to my admin home
          </Link>
          {isSuperAdminArea && (
            <button type="button" className={styles.button} onClick={signInAsSuperAdmin}>
              Sign in as Super Admin
            </button>
          )}
        </div>
      </div>
    </AdminPage>
  )
}
