import { AdminPage } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'

/** Stand-in for nav entries whose real page has not been plugged in yet (no `page` in the config). */
export function AreaPlaceholderPage({ entry }) {
  const breadcrumbs = [entry.section, entry.group?.label, entry.label].filter(Boolean)
  return (
    <AdminPage title={entry.label} subtitle={entry.description} breadcrumbs={breadcrumbs}>
      <div className={styles.placeholder}>Page content</div>
    </AdminPage>
  )
}
