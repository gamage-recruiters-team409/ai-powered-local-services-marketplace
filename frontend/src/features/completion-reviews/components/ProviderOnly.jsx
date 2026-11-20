import { RequireAuth, RequirePasswordChanged, RequireRole, ROLES } from '../../auth'

const PROVIDER_ONLY = [ROLES.PROVIDER]

/** Same guard order as the customer area: RequireAuth → RequirePasswordChanged → RequireRole(PROVIDER). */
export default function ProviderOnly({ children }) {
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RequireRole roles={PROVIDER_ONLY}>{children}</RequireRole>
      </RequirePasswordChanged>
    </RequireAuth>
  )
}
