import { RequireAuth, RequirePasswordChanged, useAuth } from '../../auth'
import { DisputeAccessDenied } from '../components/DisputeStates'
import { isDisputeFixtureMode } from '../services/disputesApi'

/**
 * Lets only signed-in users with `role` (CUSTOMER or PROVIDER) see a dispute screen.
 *
 * Built on the shared WP01 guards (RequireAuth -> RequirePasswordChanged), so it waits until the session is restored
 * after a page reload before deciding anything; then checks the role. A signed-in user with another role sees
 * "Access denied" (not a redirect to a page that may not exist). The backend checks the role and dispute membership
 * again on every request; this guard only decides what to render. Mock mode (DEV only) skips sign-in so the screens
 * can be previewed.
 */
export function DisputeRoleGuard({ role, children }) {
  if (isDisputeFixtureMode()) {
    return children
  }
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RoleCheck role={role}>{children}</RoleCheck>
      </RequirePasswordChanged>
    </RequireAuth>
  )
}

function RoleCheck({ role, children }) {
  const { hasRole } = useAuth()
  return hasRole([role]) ? children : <DisputeAccessDenied />
}
