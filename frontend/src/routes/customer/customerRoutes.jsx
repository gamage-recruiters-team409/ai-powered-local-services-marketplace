import { Navigate } from 'react-router-dom'

import { CUSTOMER_ACCOUNT_PATH } from '../../shared/layouts/customer'
import DeactivatePage from '../../features/customer-account/pages/DeactivatePage'
import FavoritesPage from '../../features/customer-account/pages/FavoritesPage'
import PaymentsPage from '../../features/customer-account/pages/PaymentsPage'
import ProfilePage from '../../features/customer-account/pages/ProfilePage'
import SavedAddressesPage from '../../features/customer-account/pages/SavedAddressesPage'
import SettingsPage from '../../features/customer-account/pages/SettingsPage'
import { CustomerDisputePage } from '../../features/disputes/pages/CustomerDisputePage'
import { MyDisputesPage } from '../../features/disputes/pages/MyDisputesPage'
import { CustomerGuards } from './CustomerGuards'
import { CustomerLayoutRoute } from './CustomerLayoutRoute'

/*
 * Customer area routes (WP05 shared UI).
 *
 * Every route here is protected by CustomerGuards
 * (RequireAuth → RequirePasswordChanged → RequireRole(CUSTOMER))
 * and rendered inside the Customer Common Layout.
 *
 * Module owners add their customer pages as child routes:
 *   - CUSTOMER_ACCOUNT_ROUTES: pages opened from the account sidebar
 *     (layout shows the MY ACTIVITY / MY ACCOUNT sidebar on desktop),
 *     e.g. { path: '/customer/account/favorites', element: <FavoritesPage /> }
 *   - CUSTOMER_PAGE_ROUTES: other customer pages (no sidebar, content max 1200 px),
 *     e.g. booking flow screens.
 * Also add the menu item to shared/layouts/customer/customerNavConfig.js.
 */

export const CUSTOMER_ACCOUNT_ROUTES = [
  { path: CUSTOMER_ACCOUNT_PATH, element: <Navigate to="/customer/account/profile" replace /> },
  { path: '/customer/account/profile', element: <ProfilePage /> },
  { path: '/customer/account/addresses', element: <SavedAddressesPage /> },
  { path: '/customer/account/settings', element: <SettingsPage /> },
  { path: '/customer/account/deactivate', element: <DeactivatePage /> },
  { path: '/customer/account/payments', element: <PaymentsPage /> },
  { path: '/customer/account/favorites', element: <FavoritesPage /> },
  // WP09 – My Disputes (sidebar item "My Disputes", active on the list and on each dispute)
  { path: '/customer/disputes', element: <MyDisputesPage role="CUSTOMER" /> },
  { path: '/customer/disputes/:disputeId', element: <CustomerDisputePage /> },
]

export const CUSTOMER_PAGE_ROUTES = []

const layoutGroups = [
  { element: <CustomerLayoutRoute showAccountNav />, children: CUSTOMER_ACCOUNT_ROUTES },
  { element: <CustomerLayoutRoute />, children: CUSTOMER_PAGE_ROUTES },
].filter((group) => group.children.length > 0)

export const customerRoutes = {
  element: <CustomerGuards />,
  children: layoutGroups,
}
