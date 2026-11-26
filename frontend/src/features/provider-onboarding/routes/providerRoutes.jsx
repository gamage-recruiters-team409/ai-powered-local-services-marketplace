import {
  RequireAuth,
  RequirePasswordChanged,
  RequireRole,
  ROLES,
} from '../../auth'

import ProviderLayoutRoute from './ProviderLayoutRoute'
import ProviderProfilePage from '../pages/ProviderProfilePage'
import ProviderVerificationDocumentsPage from '../pages/ProviderVerificationDocumentsPage'

/** Only WP02-owned Provider pages are declared here. The shared layout is untouched. */
export const providerRoutes = {
  element: <RequireAuth />,
  children: [
    {
      element: <RequirePasswordChanged />,
      children: [
        {
          element: <RequireRole roles={[ROLES.PROVIDER]} />,
          children: [
            {
              element: <ProviderLayoutRoute />,
              children: [
                { path: '/provider/profile', element: <ProviderProfilePage /> },
                { path: '/provider/verification', element: <ProviderVerificationDocumentsPage /> },
              ],
            },
          ],
        },
      ],
    },
  ],
}
