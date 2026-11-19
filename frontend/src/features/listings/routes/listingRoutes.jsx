import { ProviderListingShell } from '../components/ProviderListingShell'
import { CreateEditListingWizardPage } from '../pages/CreateEditListingWizardPage'
import { ProviderListingsDashboardPage } from '../pages/ProviderListingsDashboardPage'

/**
 * Provider service listing management routes (WP03).
 * Mounted inside WP02's shared ProviderLayout via ProviderListingShell.
 */
export const listingRoutes = [
  {
    path: '/provider/listings',
    element: (
      <ProviderListingShell>
        <ProviderListingsDashboardPage />
      </ProviderListingShell>
    ),
  },
  {
    path: '/provider/listings/new',
    element: (
      <ProviderListingShell>
        <CreateEditListingWizardPage />
      </ProviderListingShell>
    ),
  },
  {
    path: '/provider/listings/:listingId/edit',
    element: (
      <ProviderListingShell>
        <CreateEditListingWizardPage />
      </ProviderListingShell>
    ),
  },
]
