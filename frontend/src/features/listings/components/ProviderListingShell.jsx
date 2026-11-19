import { useCallback, useContext, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { ProviderLayout } from '../../../shared/layouts/provider'
import tokenStore from '../../../shared/services/tokenStore'
import {
  AuthContext,
  RequireAuth,
  RequirePasswordChanged,
  RequireRole,
  ROLES,
} from '../../auth'

const LOGIN_PATH = '/login'
const PROVIDER_ONLY = [ROLES.PROVIDER]

const FALLBACK_PROVIDER_USER = {
  name: 'Service Provider',
  email: 'provider@marketplace.local',
}

/**
 * Wraps WP03 Provider Listing screens inside the shared WP02 ProviderLayout
 * and WP06 ToastProvider, binding the authenticated provider identity and logout
 * from WP01 AuthContext.
 */
export function ProviderListingShell({ children }) {
  const navigate = useNavigate()
  const auth = useContext(AuthContext)

  const providerUser = useMemo(() => {
    const currentUser = auth?.user
    if (!currentUser) {
      return FALLBACK_PROVIDER_USER
    }
    const displayName =
      currentUser.name ||
      (currentUser.email ? currentUser.email.split('@')[0] : '') ||
      FALLBACK_PROVIDER_USER.name
    return {
      name: displayName,
      email: currentUser.email || FALLBACK_PROVIDER_USER.email,
    }
  }, [auth?.user])

  const handleLogout = useCallback(async () => {
    try {
      if (typeof auth?.logout === 'function') {
        await auth.logout()
      } else {
        tokenStore.clear()
      }
    } finally {
      navigate(LOGIN_PATH, { replace: true })
    }
  }, [auth, navigate])

  return (
    <ToastProvider>
      <ProviderLayout user={providerUser} onLogout={handleLogout}>
        {children}
      </ProviderLayout>
    </ToastProvider>
  )
}

/**
 * Guard order agreed with WP01:
 * RequireAuth -> RequirePasswordChanged -> RequireRole(PROVIDER).
 */
export function ProviderListingGuard({ children }) {
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RequireRole roles={PROVIDER_ONLY}>
          <ProviderListingShell>{children}</ProviderListingShell>
        </RequireRole>
      </RequirePasswordChanged>
    </RequireAuth>
  )
}
