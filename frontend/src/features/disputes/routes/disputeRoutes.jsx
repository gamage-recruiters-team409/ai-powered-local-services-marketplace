import { ProviderDisputeShell } from '../components/ProviderDisputeShell'
import { DisputeRoleGuard } from '../guards/DisputeRoleGuard'
import { ProviderDisputePage } from '../pages/ProviderDisputePage'

/**
 * Dispute screens for the booking parties (WP09).
 * - /customer/disputes/:disputeId  is a customer account route (routes/customer/customerRoutes.jsx): customer
 *   guards and the Customer Common Layout with the account sidebar.
 * - /provider/disputes/:disputeId  PROVIDER only, inside the shared ProviderLayout
 */
export const disputeRoutes = [
  {
    path: '/provider/disputes/:disputeId',
    element: (
      <DisputeRoleGuard role="PROVIDER">
        <ProviderDisputeShell>
          <ProviderDisputePage />
        </ProviderDisputeShell>
      </DisputeRoleGuard>
    ),
  },
]
