import { createBrowserRouter } from 'react-router-dom'

import { disputeRoutes } from '../features/disputes/routes/disputeRoutes'
import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
import ProviderProfilePage from '../features/search-discovery/pages/ProviderProfilePage'
import SearchResultsPage from '../features/search-discovery/pages/SearchResultsPage'
import { NotFoundPage } from './NotFoundPage'

/**
 * App router. Each feature adds its own route object here.
 * Keep the catch-all '*' route last.
 */
export const router = createBrowserRouter([
  adminRoutes,
  // WP04 – Search & Discovery routes
  { path: '/search', element: <SearchResultsPage /> },
  { path: '/providers/:providerId', element: <ProviderProfilePage /> },
  // WP09 – Dispute screens for customers and providers
  ...disputeRoutes,
  { path: '*', element: <NotFoundPage /> },
])
