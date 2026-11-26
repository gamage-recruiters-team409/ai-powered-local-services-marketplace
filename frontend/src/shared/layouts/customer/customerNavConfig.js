/*
 * Customer Common Layout – navigation config (WP05 shared UI).
 *
 * Module owners add their own customer pages here once the page exists.
 * Do not edit the layout components to add a menu item.
 *
 * Item shape:
 *   {
 *     id: 'reviews',                 // unique within the list
 *     label: 'My Reviews',
 *     to: '/customer/reviews',       // also active on sub-pages (e.g. /customer/reviews/123)
 *     icon: 'reviews',               // a CustomerIcon name
 *     end: false,                    // true = active only on the exact path
 *     badge: MyCountBadge,           // optional component that renders a count (or null)
 *     tone: 'danger',                // optional, footer items only (e.g. Deactivate Account)
 *   }
 *
 * Agreed items still to be added by their owners:
 *   MY ACTIVITY: Messages (/customer/messages) and Notifications (/customer/notifications) – WP06,
 *                Payments (/customer/account/payments) – WP01, My Reviews (/customer/reviews) – WP08,
 *                My Disputes (/customer/disputes) – WP09
 *   MY ACCOUNT:  Profile & Addresses (/customer/account/profile), Favorites (/customer/account/favorites),
 *                Settings (/customer/account/settings) – WP01
 *   FOOTER:      Deactivate Account (/customer/account/deactivate, tone 'danger') – WP01
 *   BOTTOM NAV:  Messages (/customer/messages) – WP06
 */

export const CUSTOMER_ACCOUNT_PATH = '/customer/account'

/** Profile page opened from the account menu header. Set by WP01 when the page exists. */
export const CUSTOMER_PROFILE_PATH = '/customer/account/profile'

export const CUSTOMER_HEADER_LINKS = Object.freeze([
  { id: 'home', label: 'Home', to: '/', end: true },
  { id: 'find-services', label: 'Find Services', to: '/search' },
  { id: 'my-bookings', label: 'My Bookings', to: '/customer/bookings' },
])

export const CUSTOMER_NAV_SECTIONS = Object.freeze([
  {
    id: 'activity',
    title: 'My Activity',
    items: [
      {
        id: 'bookings',
        label: 'My Bookings',
        to: '/customer/bookings',
        icon: 'bookings',
      },
      {
        id: 'payments',
        label: 'Payments',
        to: '/customer/account/payments',
        icon: 'payments',
      },
      {
        id: 'disputes',
        label: 'My Disputes',
        to: '/customer/disputes',
        icon: 'disputes',
      },
    ],
  },
  {
    id: 'account',
    title: 'My Account',
    items: [
      {
        id: 'profile',
        label: 'Profile',
        to: '/customer/account/profile',
        icon: 'profile',
      },
      {
        id: 'addresses',
        label: 'Saved Addresses',
        to: '/customer/account/addresses',
        icon: 'address',
      },
      {
        id: 'favorites',
        label: 'Favorites',
        to: '/customer/account/favorites',
        icon: 'favorites',
      },
    ],
  },
])

/** Shown below the sections, before Log out. Items with tone 'danger' are shown after Log out. */
export const CUSTOMER_NAV_FOOTER_ITEMS = Object.freeze([
  {
    id: 'help',
    label: 'Help & Support',
    to: '/help',
    icon: 'help',
  },
  {
    id: 'deactivate',
    label: 'Deactivate Account',
    to: '/customer/account/deactivate',
    icon: 'alert',
    tone: 'danger',
  },
])

export const CUSTOMER_PROFILE_MENU_ITEMS = Object.freeze([
  {
    id: 'bookings',
    label: 'My Bookings',
    to: '/customer/bookings',
    icon: 'bookings',
  },
  {
    id: 'settings',
    label: 'Settings',
    to: '/customer/account/settings',
    icon: 'settings',
  },
  {
    id: 'help',
    label: 'Help & Support',
    to: '/help',
    icon: 'help',
  },
])

export const CUSTOMER_BOTTOM_NAV_ITEMS = Object.freeze([
  { id: 'home', label: 'Home', to: '/', icon: 'home', end: true },
  { id: 'search', label: 'Search', to: '/search', icon: 'search' },
  { id: 'bookings', label: 'Bookings', to: '/customer/bookings', icon: 'bookings' },
  { id: 'account', label: 'Account', to: CUSTOMER_ACCOUNT_PATH, icon: 'profile' },
])
