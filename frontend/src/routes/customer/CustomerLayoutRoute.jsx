import { useCallback } from 'react'

import { useAuth } from '../../features/auth'
import { CustomerLayout } from '../../shared/layouts/customer'

/**
 * Connects the Customer Common Layout to the signed-in user:
 * name and email for the header and sidebar, and logout.
 * After logout the guards send the user to the login page.
 */
export function CustomerLayoutRoute({ showAccountNav = false }) {
  const { user, logout } = useAuth()

  const handleLogout = useCallback(() => {
    logout()
  }, [logout])

  return (
    <CustomerLayout
      user={{
        name: user?.name ?? '',
        email: user?.email ?? '',
        avatarUrl: user?.profileImageUrl ?? null,
      }}
      showAccountNav={showAccountNav}
      onLogout={handleLogout}
    />
  )
}
