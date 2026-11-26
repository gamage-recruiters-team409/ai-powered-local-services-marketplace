
import { useAuth } from '../../auth'
import { ProviderLayout } from '../../../shared/layouts/provider'

export default function ProviderLayoutRoute() {
  const { user, logout } = useAuth()

  return (
    <ProviderLayout
      user={{
        name: user?.fullName || user?.name || 'Provider',
        email: user?.email || '',
      }}
      onLogout={logout}
    />
  )
}
