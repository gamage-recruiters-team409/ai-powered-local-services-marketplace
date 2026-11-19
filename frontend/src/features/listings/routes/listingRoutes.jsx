import { ProviderListingGuard } from '../components/ProviderListingShell'
import { CreateEditListingWizardPage } from '../pages/CreateEditListingWizardPage'
import { ProviderListingsDashboardPage } from '../pages/ProviderListingsDashboardPage'

/**
 * Provider service listing management routes (WP03).
 * Mounted inside WP02's shared ProviderLayout and protected by WP01 ProviderListingGuard.
 */
export const listingRoutes = [
  {
    path: '/provider/listings',
    element: (
      <ProviderListingGuard>
        <ProviderListingsDashboardPage />
      </ProviderListingGuard>
    ),
  },
  {
    path: '/provider/listings/new',
    element: (
      <ProviderListingGuard>
        <CreateEditListingWizardPage />
      </ProviderListingGuard>
    ),
  },
  {
    path: '/provider/listings/:listingId/edit',
    element: (
      <ProviderListingGuard>
        <CreateEditListingWizardPage />
      </ProviderListingGuard>
    ),
  },
]
