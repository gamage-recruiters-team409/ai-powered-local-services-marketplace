import { createBrowserRouter } from 'react-router-dom'

import { disputeRoutes } from '../features/disputes/routes/disputeRoutes'
import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
import { completionRoutes } from '../features/completion-reviews/routes/completionRoutes'
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
  // WP09 – Dispute screens for customers and providers
  ...disputeRoutes,
  // WP08 – Service completion preview (development only)
  ...completionRoutes,
  { path: '*', element: <NotFoundPage /> },
])