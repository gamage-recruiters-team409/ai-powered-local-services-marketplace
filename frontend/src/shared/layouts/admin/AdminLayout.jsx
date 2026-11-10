import { useCallback, useEffect, useRef, useState } from 'react'
import { Outlet } from 'react-router-dom'

import './adminTheme.css'
import styles from './AdminLayout.module.css'
import { AdminHeader } from './AdminHeader'
import { AdminSidebar } from './AdminSidebar'
import { ADMIN_BRAND } from './adminBrand'

const SIDEBAR_ID = 'admin-sidebar'
const MAIN_ID = 'admin-main'
const FALLBACK_USER = { name: 'Admin', roleLabel: 'Admin' }

/**
 * Shared Admin Common Layout: header, sidebar navigation, main content and footer.
 * Presentational only. It makes no API calls and holds no access rules.
 *
 * @param {object}   props
 * @param {Array}    props.navSections  [{ id, title?, items: [{ id, label, to, icon?, end?, children?: [{ id, label, to }] }] }]
 * @param {object}   props.user         { name, roleLabel }
 * @param {Function} props.onLogout
 * @param {Function} [props.onSwitchRole]          shows "Switch role" in the profile menu and mobile drawer
 * @param {number}   [props.notificationCount]
 * @param {Function} [props.onNotificationsClick]  the bell is disabled until this is provided
 * @param {object}   [props.brand]                 defaults to ADMIN_BRAND
 * @param {*}        [props.children]              rendered instead of <Outlet />
 */
export function AdminLayout({
  navSections = [],
  user = FALLBACK_USER,
  onLogout,
  onSwitchRole,
  notificationCount = 0,
  onNotificationsClick,
  brand = ADMIN_BRAND,
  children,
}) {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const menuButtonRef = useRef(null)
  const closeButtonRef = useRef(null)

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
    menuButtonRef.current?.focus()
  }, [])

  useEffect(() => {
    if (!drawerOpen) {
      return undefined
    }
    closeButtonRef.current?.focus()
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        closeDrawer()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [drawerOpen, closeDrawer])

  const handleSwitchRole = onSwitchRole
    ? () => {
        setDrawerOpen(false)
        onSwitchRole()
      }
    : undefined

  return (
    <div className={styles.layout}>
      <a className={styles.skipLink} href={`#${MAIN_ID}`}>
        Skip to main content
      </a>

      <AdminHeader
        brand={brand}
        user={user}
        sidebarId={SIDEBAR_ID}
        drawerOpen={drawerOpen}
        menuButtonRef={menuButtonRef}
        onMenuClick={() => setDrawerOpen(true)}
        notificationCount={notificationCount}
        onNotificationsClick={onNotificationsClick}
        onLogout={onLogout}
        onSwitchRole={onSwitchRole}
      />

      <div className={styles.body}>
        {drawerOpen && <div className={styles.overlay} aria-hidden="true" onClick={closeDrawer} />}

        <AdminSidebar
          id={SIDEBAR_ID}
          open={drawerOpen}
          brand={brand}
          user={user}
          sections={navSections}
          closeButtonRef={closeButtonRef}
          onClose={closeDrawer}
          onNavigate={() => setDrawerOpen(false)}
          onLogout={onLogout}
          onSwitchRole={handleSwitchRole}
        />

        <main id={MAIN_ID} className={styles.main} tabIndex={-1}>
          {children ?? <Outlet />}
        </main>
      </div>

      <footer className={styles.footer}>
        <p>{brand.footerText}</p>
        {brand.footerLinks?.length > 0 && (
          <nav aria-label="Footer">
            <ul className={styles.footerLinks}>
              {brand.footerLinks.map((link) => (
                <li key={link.label}>
                  <a href={link.href}>{link.label}</a>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </footer>
    </div>
  )
}
