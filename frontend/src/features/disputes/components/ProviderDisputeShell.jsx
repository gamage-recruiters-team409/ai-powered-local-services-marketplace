import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { ProviderLayout } from '../../../shared/layouts/provider'
import { endSession, LOGIN_PATH } from '../../disputes-admin/services/authBridge'

/** The shared ProviderLayout (unchanged) around a dispute page, with the existing logout behaviour. */
export function ProviderDisputeShell({ children }) {
  const navigate = useNavigate()
  const logout = useCallback(() => {
    endSession()
    navigate(LOGIN_PATH, { replace: true })
  }, [navigate])

  return <ProviderLayout onLogout={logout}>{children}</ProviderLayout>
}
