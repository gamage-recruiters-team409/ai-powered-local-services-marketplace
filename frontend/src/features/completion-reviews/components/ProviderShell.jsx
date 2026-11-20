import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { ProviderLayout } from '../../../shared/layouts/provider'
// Temporary: session helpers live in the admin feature until a shared auth client exists (WP01).
import { endSession, LOGIN_PATH } from '../../disputes-admin/services/authBridge'

/**
 * WP08 provider pages inside the shared Provider Common Layout (Hiba, unchanged):
 * header, sidebar ("Reviews" is already in providerNavConfig), mobile bottom nav and footer.
 */
export default function ProviderShell({ children }) {
  const navigate = useNavigate()
  const logout = useCallback(() => {
    endSession()
    navigate(LOGIN_PATH, { replace: true })
  }, [navigate])

  return <ProviderLayout onLogout={logout}>{children}</ProviderLayout>
}
