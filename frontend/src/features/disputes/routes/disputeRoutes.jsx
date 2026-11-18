import { CustomerDisputeShell } from '../components/CustomerDisputeShell'
import { ProviderDisputeShell } from '../components/ProviderDisputeShell'
import { DisputeRoleGuard } from '../guards/DisputeRoleGuard'
import { CustomerDisputePage } from '../pages/CustomerDisputePage'
import { ProviderDisputePage } from '../pages/ProviderDisputePage'

/**
 * Dispute screens for the booking parties (WP09).
 * - /customer/disputes/:disputeId  CUSTOMER only (no customer layout in the repo yet)
 * - /provider/disputes/:disputeId  PROVIDER only, inside the shared ProviderLayout
 */
export const disputeRoutes = [
  {
    path: '/customer/disputes/:disputeId',
    element: (
      <CustomerDisputeShell>
        <DisputeRoleGuard role="CUSTOMER">
          <CustomerDisputePage />
        </DisputeRoleGuard>
      </CustomerDisputeShell>
    ),
  },
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
