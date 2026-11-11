import styles from './AdminLayout.module.css'
import { Icon } from './Icon'

export function ViewOnlyBanner({ areaLabel }) {
  return (
    <div className={styles.banner} role="status">
      <Icon name="eye" size={18} />
      <p>
        <strong>View only.</strong> You can view the {areaLabel} area, but actions are disabled for your role.
      </p>
    </div>
  )
}
