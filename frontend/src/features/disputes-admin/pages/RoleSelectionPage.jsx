import { AdminPage } from '../../../shared/layouts/admin'
import { RoleAreaCards } from '../components/RoleAreaCards'

export function RoleSelectionPage() {
  return (
    <AdminPage
      title="Choose a role area"
      subtitle="Open your own area to work in it, or another area in view-only mode. You can switch at any time from your profile menu."
      breadcrumbs={['Role selection']}
      plain
    >
      <RoleAreaCards />
    </AdminPage>
  )
}
