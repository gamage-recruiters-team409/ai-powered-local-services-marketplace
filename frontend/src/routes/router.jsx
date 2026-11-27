import { createBrowserRouter } from 'react-router-dom'

import { disputeRoutes } from '../features/disputes/routes/disputeRoutes'
import { adminRoutes } from '../features/disputes-admin/routes/adminRoutes'
import { authRoutes } from '../features/auth'
import PublicLandingPage from '../features/listings/landing/pages/PublicLandingPage'
import { listingRoutes } from '../features/listings/routes/listingRoutes'
import { providerRoutes } from '../features/provider-onboarding/routes/providerRoutes'
import { paymentsEarningsRoutes } from '../features/payments-earnings/routes/paymentsEarningsRoutes'
import ProviderProfilePage from '../features/search-discovery/pages/ProviderProfilePage'
import SearchResultsPage from '../features/search-discovery/pages/SearchResultsPage'
import { customerRoutes } from './customer/customerRoutes'
import { NotFoundPage } from './NotFoundPage'

/**
 * App router. Each feature adds its own route object here.
 * Keep the catch-all '*' route last.
 */
export const router = createBrowserRouter([
  // WP03 – Public / Guest Common UI & Landing Page
  { path: '/', element: <PublicLandingPage /> },
  adminRoutes,
  authRoutes,
  providerRoutes,
  // WP03 – Provider service listings management & 4-step wizard
  ...listingRoutes,
  // WP04 – Search & Discovery routes
  { path: '/search', element: <SearchResultsPage /> },
  { path: '/providers/:providerId', element: <ProviderProfilePage /> },
  // WP09 – Dispute screens for customers and providers
  ...disputeRoutes,
  // WP07 - Provider earnings and payouts
  ...paymentsEarningsRoutes,
  // WP05 – Customer area (Customer Common Layout + customer guards)
  customerRoutes,
  { path: '*', element: <NotFoundPage /> },
])
