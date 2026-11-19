import { Outlet } from 'react-router-dom'

import {
  RequireAuth,
  RequirePasswordChanged,
  RequireRole,
  ROLES,
} from '../../features/auth'

const CUSTOMER_ONLY = [ROLES.CUSTOMER]

/**
 * Guard order agreed with WP01:
 * RequireAuth → RequirePasswordChanged → RequireRole(CUSTOMER).
 */
export function CustomerGuards() {
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RequireRole roles={CUSTOMER_ONLY}>
          <Outlet />
        </RequireRole>
      </RequirePasswordChanged>
    </RequireAuth>
  )
}
