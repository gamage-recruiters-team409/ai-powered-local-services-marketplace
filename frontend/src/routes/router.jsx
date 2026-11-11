import { createBrowserRouter } from 'react-router-dom'

import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
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
  { path: '*', element: <NotFoundPage /> },
])
