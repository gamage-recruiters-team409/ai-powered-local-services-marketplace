import { Link } from 'react-router-dom'

import { Alert } from '../../../shared/components/feedback'
import { AdminPage } from '../../../shared/layouts/admin'
import { announcementPaths } from '../config/announcementConfig'
import { AnnIcon } from './AnnIcon'
import styles from '../announcements.module.css'

/** Shown instead of the form on /new and /:id/edit for read-only admins (the backend returns 403 anyway). */
export function ViewOnlyAccess({ title }) {
  return (
    <AdminPage title={title} breadcrumbs={['Management', { label: 'Announcements', to: announcementPaths.list() }, title]} plain>
      <Alert variant="info" title="You have view-only access">
        Your role can view announcements but cannot create, edit, publish or delete them.
      </Alert>
      <Link to={announcementPaths.list()} className={styles.backLink}>
        <AnnIcon name="arrowLeft" size={15} /> Back to announcements
      </Link>
    </AdminPage>
  )
}
