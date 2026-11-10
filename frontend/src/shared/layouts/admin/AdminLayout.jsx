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
// Must match the drawer breakpoint in AdminLayout.module.css (max-width: 1023px).
const DESKTOP_QUERY = '(min-width: 1024px)'
const FOCUS_MENU_BUTTON = 'menu-button'
const FOCUS_MAIN = 'main'
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** Focusable elements that are actually rendered (skips display:none, e.g. collapsed nav groups). */
function getFocusable(container) {
  if (!container) {
    return []
  }
  return [...container.querySelectorAll(FOCUSABLE_SELECTOR)].filter((element) => element.getClientRects().length > 0)
}

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
  const drawerRef = useRef(null)
  const mainRef = useRef(null)
  // Where focus goes once the drawer has closed. Applied after render, when the background is no longer inert.
  const focusAfterCloseRef = useRef(null)

  const closeDrawer = useCallback((focusTarget) => {
    focusAfterCloseRef.current = focusTarget
    setDrawerOpen(false)
  }, [])

  useEffect(() => {
    if (!drawerOpen) {
      const target = focusAfterCloseRef.current
      focusAfterCloseRef.current = null
      if (target === FOCUS_MENU_BUTTON) {
        menuButtonRef.current?.focus()
      } else if (target === FOCUS_MAIN) {
        mainRef.current?.focus()
      }
      return undefined
    }

    const drawer = drawerRef.current
    ;(closeButtonRef.current ?? getFocusable(drawer)[0])?.focus()

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeDrawer(FOCUS_MENU_BUTTON)
        return
      }
      if (event.key !== 'Tab') {
        return
      }
      // Keep Tab / Shift+Tab cycling inside the drawer.
      const items = getFocusable(drawer)
      if (items.length === 0) {
        event.preventDefault()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      const active = document.activeElement
      if (!drawer?.contains(active)) {
        event.preventDefault()
        first.focus()
      } else if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [drawerOpen, closeDrawer])

  // The drawer only exists on small screens: close it if the window grows to desktop width.
  useEffect(() => {
    const desktop = window.matchMedia(DESKTOP_QUERY)
    const handleChange = (event) => {
      if (event.matches) {
        closeDrawer(null)
      }
    }
    desktop.addEventListener('change', handleChange)
    return () => desktop.removeEventListener('change', handleChange)
  }, [closeDrawer])

  const handleSwitchRole = onSwitchRole
    ? () => {
        // The switch-role dialog returns focus to the menu button when it closes.
        closeDrawer(FOCUS_MENU_BUTTON)
        onSwitchRole()
      }
    : undefined

  return (
    <div className={styles.layout}>
      <a className={styles.skipLink} href={`#${MAIN_ID}`} inert={drawerOpen}>
        Skip to main content
      </a>

      <AdminHeader
        inert={drawerOpen}
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
        {drawerOpen && (
          <div className={styles.overlay} aria-hidden="true" onClick={() => closeDrawer(FOCUS_MENU_BUTTON)} />
        )}

        <AdminSidebar
          id={SIDEBAR_ID}
          open={drawerOpen}
          brand={brand}
          user={user}
          sections={navSections}
          drawerRef={drawerRef}
          closeButtonRef={closeButtonRef}
          onClose={() => closeDrawer(FOCUS_MENU_BUTTON)}
          onNavigate={() => {
            if (drawerOpen) {
              closeDrawer(FOCUS_MAIN)
            }
          }}
          onLogout={onLogout}
          onSwitchRole={handleSwitchRole}
        />

        {/* Header, main and footer are inert while the mobile drawer is open; the drawer itself never is. */}
        <main ref={mainRef} id={MAIN_ID} className={styles.main} tabIndex={-1} inert={drawerOpen}>
          {children ?? <Outlet />}
        </main>
      </div>

      <footer className={styles.footer} inert={drawerOpen}>
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
