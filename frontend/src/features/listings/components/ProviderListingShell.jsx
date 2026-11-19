import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { ProviderLayout } from '../../../shared/layouts/provider'
import { endSession, LOGIN_PATH } from '../../disputes-admin/services/authBridge'

const DEMO_PROVIDER_USER = {
  name: 'Nimal Perera',
  email: 'provider@localserve.lk',
}

/**
 * Wraps WP03 Provider Listing screens inside the shared WP02 ProviderLayout
 * and WP06 ToastProvider so sidebar, mobile bottom nav, and toasts work uniformly.
 */
export function ProviderListingShell({ children }) {
  const navigate = useNavigate()

  const handleLogout = useCallback(() => {
    endSession()
    navigate(LOGIN_PATH, { replace: true })
  }, [navigate])

  return (
    <ToastProvider>
      <ProviderLayout user={DEMO_PROVIDER_USER} onLogout={handleLogout}>
        {children}
      </ProviderLayout>
    </ToastProvider>
  )
}
