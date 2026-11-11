import { Navigate } from 'react-router-dom'

import { getAdminHomePath } from '../access/accessRules'
import { useAdminAccess } from '../access/useAdminAccess'

/** /admin -> Super Admin dashboard for SUPER_ADMIN, role selection for other admins. */
export function AdminIndexRedirect() {
  const { access } = useAdminAccess()
  return <Navigate to={getAdminHomePath(access)} replace />
}
