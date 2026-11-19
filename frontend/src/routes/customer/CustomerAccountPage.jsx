import { useAuth } from '../../features/auth'
import {
  CustomerAccountMenu,
  CustomerPage,
} from '../../shared/layouts/customer'

/** The "Account" tab (mobile) – every account menu item from the nav config. */
export function CustomerAccountPage() {
  const { user, logout } = useAuth()

  return (
    <CustomerPage
      title="Account"
      showBack={false}
      breadcrumbs={[{ label: 'Home', to: '/' }, { label: 'Account' }]}
    >
      <CustomerAccountMenu
        user={{ name: user?.name ?? '', email: user?.email ?? '' }}
        onLogout={() => logout()}
      />
    </CustomerPage>
  )
}
