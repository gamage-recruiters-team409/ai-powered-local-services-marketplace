import { ProviderPage } from '../../../shared/layouts/provider'

import ProviderOnly from '../components/ProviderOnly'
import ProviderShell from '../components/ProviderShell'
import CompletionPreviewPage from '../pages/CompletionPreviewPage'

/**
 * WP08 routes registered in the app router.
 * - Provider reviews (S11 + S12 reply) inside the shared Provider Common Layout;
 *   "Reviews" in providerNavConfig already points to /provider/reviews.
 * - Development previews of the completion sections (npm run dev only). In the app they are placed
 *   inside the booking details pages (WP05) when those are available.
 * The customer review pages (S07–S09) are registered in CUSTOMER_ACCOUNT_ROUTES
 * (routes/customer/customerRoutes.jsx), so they get the customer guards and the account sidebar.
 */
export const completionRoutes = [
  {
    path: '/provider/reviews',
    lazy: async () => {
      const { default: ProviderReviewsPage } = await import('../pages/ProviderReviewsPage')
      return {
        Component: () => (
          <ProviderOnly>
            <ProviderShell>
              <ProviderReviewsPage />
            </ProviderShell>
          </ProviderOnly>
        ),
      }
    },
  },
  ...(import.meta.env.DEV
    ? [
        { path: '/dev/completion-preview', element: <CompletionPreviewPage /> },
        {
          path: '/dev/provider/completion-preview',
          element: (
            <ProviderShell>
              <ProviderPage title="Booking details (preview)" subtitle="Service completion section – mock data" plain>
                <CompletionPreviewPage fixedRole="PROVIDER" />
              </ProviderPage>
            </ProviderShell>
          ),
        },
      ]
    : []),
]
