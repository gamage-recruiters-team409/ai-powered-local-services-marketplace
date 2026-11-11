import { createBrowserRouter } from 'react-router-dom'

import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
import { NotFoundPage } from './NotFoundPage'

/**
 * App router. Each feature adds its own route object here.
 * Keep the catch-all '*' route last.
 */
export const router = createBrowserRouter([
  adminRoutes,
  { path: '*', element: <NotFoundPage /> },
])
