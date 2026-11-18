import { createBrowserRouter } from 'react-router-dom'

import { disputeRoutes } from '../features/disputes/routes/disputeRoutes'
import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
import { authRoutes } from '../features/auth'
import SearchResultsPage from '../features/search-discovery/pages/SearchResultsPage'
import { NotFoundPage } from './NotFoundPage'

/**
 * App router. Each feature adds its own route object here.
 * Keep the catch-all '*' route last.
 */
export const router = createBrowserRouter([
  adminRoutes,
  authRoutes,
  // WP04 – Search & Discovery routes
  { path: '/search', element: <SearchResultsPage /> },
  // WP09 – Dispute screens for customers and providers
  ...disputeRoutes,
  { path: '*', element: <NotFoundPage /> },
])
