import { AdminPage } from '../../../shared/layouts/admin'
import { RoleAreaCards } from '../components/RoleAreaCards'

export function RoleSelectionPage() {
  return (
    <AdminPage
      title="Choose a role area"
      subtitle="Open an area your role can use. Locked areas aren't available to your role. You can switch at any time from your profile menu."
      breadcrumbs={['Role selection']}
      plain
    >
      <RoleAreaCards />
    </AdminPage>
  )
}
