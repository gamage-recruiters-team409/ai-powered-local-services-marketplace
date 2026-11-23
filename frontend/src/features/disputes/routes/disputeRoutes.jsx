import { Outlet } from 'react-router-dom'

import { ProviderDisputeShell } from '../components/ProviderDisputeShell'
import { DisputeRoleGuard } from '../guards/DisputeRoleGuard'
import { MyDisputesPage } from '../pages/MyDisputesPage'
import { ProviderDisputePage } from '../pages/ProviderDisputePage'
import { ProviderRaiseDisputePage } from '../pages/ProviderRaiseDisputePage'

/**
 * Dispute screens for the booking parties (WP09).
 * - /customer/disputes/:disputeId  is a customer account route (routes/customer/customerRoutes.jsx): customer
 *   guards and the Customer Common Layout with the account sidebar.
 * - /provider/disputes              My Disputes (PROVIDER only), inside the shared ProviderLayout
 * - /provider/disputes/new?bookingId=...  Raise a dispute (linked from the provider's booking screen)
 * - /provider/disputes/:disputeId   Dispute detail (the WP08 "View dispute" link lands here)
 * The provider layout has no route group of its own yet, so this group provides the guard and the layout.
 */
export const disputeRoutes = [
  {
    element: (
      <DisputeRoleGuard role="PROVIDER">
        <ProviderDisputeShell>
          <Outlet />
        </ProviderDisputeShell>
      </DisputeRoleGuard>
    ),
    children: [
      { path: '/provider/disputes', element: <MyDisputesPage role="PROVIDER" /> },
      { path: '/provider/disputes/new', element: <ProviderRaiseDisputePage /> },
      { path: '/provider/disputes/:disputeId', element: <ProviderDisputePage /> },
    ],
  },
]
