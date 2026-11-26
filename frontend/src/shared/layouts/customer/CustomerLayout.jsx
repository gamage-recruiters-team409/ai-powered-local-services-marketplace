import { Outlet } from 'react-router-dom'

import './customerTheme.css'
import styles from './CustomerLayout.module.css'
import { CustomerHeader } from './CustomerHeader'
import { CustomerMobileBottomNav } from './CustomerMobileBottomNav'
import { CustomerSidebar } from './CustomerSidebar'
import { CUSTOMER_BRAND } from './customerBrand'
import {
  CUSTOMER_BOTTOM_NAV_ITEMS,
  CUSTOMER_HEADER_LINKS,
  CUSTOMER_NAV_FOOTER_ITEMS,
  CUSTOMER_NAV_SECTIONS,
  CUSTOMER_PROFILE_MENU_ITEMS,
} from './customerNavConfig'
import { cx } from './customerLayoutUtils'

const MAIN_ID = 'customer-main'

const FALLBACK_USER = Object.freeze({
  name: 'Customer',
  email: '',
  avatarUrl: null,
})

/**
 * Customer Common Layout (WP05 shared UI).
 *
 * - Default: header + page content (max width 1200) + footer.
 * - showAccountNav: adds the account sidebar (MY ACTIVITY / MY ACCOUNT) on desktop.
 * - Mobile: compact header + bottom navigation; account items are reached from the Account tab.
 *
 * This component only renders. Login state, the current user (name, email, avatarUrl), logout and unread counts
 * are passed in by the route that uses it.
 */
export function CustomerLayout({
  user = FALLBACK_USER,
  showAccountNav = false,
  headerLinks = CUSTOMER_HEADER_LINKS,
  navSections = CUSTOMER_NAV_SECTIONS,
  navFooterItems = CUSTOMER_NAV_FOOTER_ITEMS,
  profileMenuItems = CUSTOMER_PROFILE_MENU_ITEMS,
  bottomNavItems = CUSTOMER_BOTTOM_NAV_ITEMS,
  location = null,
  messagesAction = null,
  notificationsAction = null,
  onLogout,
  brand = CUSTOMER_BRAND,
  children,
}) {
  return (
    <div className={cx('customerTheme', styles.layout)}>
      <a href={`#${MAIN_ID}`} className={styles.skipLink}>
        Skip to main content
      </a>

      <CustomerHeader
        brand={brand}
        user={user}
        links={headerLinks}
        profileMenuItems={profileMenuItems}
        location={location}
        messagesAction={messagesAction}
        notificationsAction={notificationsAction}
        onLogout={onLogout}
      />

      <div className={cx(styles.body, showAccountNav && styles.bodyWithSidebar)}>
        {showAccountNav && (
          <CustomerSidebar
            user={user}
            sections={navSections}
            footerItems={navFooterItems}
            onLogout={onLogout}
          />
        )}

        <main
          id={MAIN_ID}
          tabIndex={-1}
          className={cx(styles.main, !showAccountNav && styles.mainCentered)}
        >
          {children ?? <Outlet />}
        </main>
      </div>

      <footer className={styles.footer}>
        <div className={styles.footerInner}>
          <div className={styles.footerStart}>
            <strong>{brand.footerBrand}</strong>

            <nav aria-label="Footer">
              <ul className={styles.footerLinks}>
                {brand.footerLinks.map((link) => (
                  <li key={link.label}>
                    <a href={link.href}>{link.label}</a>
                  </li>
                ))}
              </ul>
            </nav>
          </div>

          <span>{brand.footerText}</span>
        </div>
      </footer>

      <CustomerMobileBottomNav items={bottomNavItems} />
    </div>
  )
}
