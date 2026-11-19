import { CUSTOMER_ACCOUNT_PATH } from '../../shared/layouts/customer'
import { CustomerAccountPage } from './CustomerAccountPage'
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
  { path: CUSTOMER_ACCOUNT_PATH, element: <CustomerAccountPage /> },
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
