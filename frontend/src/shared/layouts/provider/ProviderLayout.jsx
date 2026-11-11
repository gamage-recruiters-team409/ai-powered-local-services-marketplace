import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import { Outlet } from 'react-router-dom'

import './providerTheme.css'
import styles from './ProviderLayout.module.css'
import { ProviderHeader } from './ProviderHeader'
import { ProviderMobileBottomNav } from './ProviderMobileBottomNav'
import { ProviderSidebar } from './ProviderSidebar'
import { PROVIDER_BRAND } from './providerBrand'
import {
  PROVIDER_BOTTOM_NAV_ITEMS,
  PROVIDER_NAV_SECTIONS,
} from './providerNavConfig'

const SIDEBAR_ID = 'provider-sidebar'
const MAIN_ID = 'provider-main'

const MOBILE_QUERY = '(max-width: 767px)'

const FALLBACK_USER = {
  name: 'Provider Name',
  email: 'providername@email.com',
}

function getInitialMobileState() {
  if (
    typeof window === 'undefined' ||
    typeof window.matchMedia !== 'function'
  ) {
    return false
  }

  return window.matchMedia(MOBILE_QUERY).matches
}

export function ProviderLayout({
  user = FALLBACK_USER,
  navSections = PROVIDER_NAV_SECTIONS,
  bottomNavItems = PROVIDER_BOTTOM_NAV_ITEMS,
  notificationCount = 0,
  messageCount = 0,
  onNotificationsClick,
  onLogout,
  brand = PROVIDER_BRAND,
  children,
}) {
  const [mobile, setMobile] = useState(getInitialMobileState)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  const menuButtonRef = useRef(null)
  const drawerRef = useRef(null)
  const mainRef = useRef(null)

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
  }, [])

  /*
   * Listen only for future viewport changes.
   *
   * The initial value is already provided by getInitialMobileState(),
   * so there is no need to call setMobile() synchronously inside
   * this effect.
   */
  useEffect(() => {
    if (
      typeof window === 'undefined' ||
      typeof window.matchMedia !== 'function'
    ) {
      return undefined
    }

    const mediaQuery = window.matchMedia(MOBILE_QUERY)

    const handleChange = (event) => {
      setMobile(event.matches)

      if (!event.matches) {
        setDrawerOpen(false)
      }
    }

    mediaQuery.addEventListener('change', handleChange)

    return () => {
      mediaQuery.removeEventListener('change', handleChange)
    }
  }, [])

  /*
   * Allow Escape to close the mobile navigation drawer.
   */
  useEffect(() => {
    if (!drawerOpen || !mobile) {
      return undefined
    }

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeDrawer()
        menuButtonRef.current?.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)

    return () => {
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [drawerOpen, mobile, closeDrawer])

  /*
   * Desktop:
   *   toggles the expanded/collapsed sidebar.
   *
   * Mobile:
   *   opens/closes the navigation drawer.
   */
  const handleMenuClick = () => {
    if (mobile) {
      setDrawerOpen((value) => !value)
      return
    }

    setSidebarCollapsed((value) => !value)
  }

  /*
   * Close the mobile drawer after selecting a navigation item.
   */
  const handleNavigate = () => {
    if (mobile) {
      closeDrawer()
      mainRef.current?.focus()
    }
  }

  return (
    <div className={`providerTheme ${styles.layout}`}>
      <a
        href={`#${MAIN_ID}`}
        className={styles.skipLink}
      >
        Skip to main content
      </a>

      <ProviderHeader
        brand={brand}
        user={user}
        mobile={mobile}
        drawerOpen={drawerOpen}
        sidebarCollapsed={sidebarCollapsed}
        menuButtonRef={menuButtonRef}
        sidebarId={SIDEBAR_ID}
        onMenuClick={handleMenuClick}
        notificationCount={notificationCount}
        onNotificationsClick={onNotificationsClick}
        onLogout={onLogout}
      />

      <div className={styles.body}>
        {mobile && drawerOpen && (
          <button
            type="button"
            className={styles.overlay}
            aria-label="Close navigation menu"
            onClick={closeDrawer}
          />
        )}

        <ProviderSidebar
          id={SIDEBAR_ID}
          mobile={mobile}
          open={drawerOpen}
          collapsed={sidebarCollapsed}
          user={user}
          sections={navSections}
          drawerRef={drawerRef}
          onNavigate={handleNavigate}
          onLogout={onLogout}
        />

        <main
          ref={mainRef}
          id={MAIN_ID}
          className={styles.main}
          tabIndex={-1}
          inert={mobile && drawerOpen}
        >
          {children ?? <Outlet />}
        </main>
      </div>

      <footer className={styles.footer}>
        <strong>{brand.footerBrand}</strong>

        <nav aria-label="Footer">
          <ul className={styles.footerLinks}>
            {brand.footerLinks.map((link) => (
              <li key={link.label}>
                <a href={link.href}>
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <span>{brand.footerText}</span>
      </footer>

      <ProviderMobileBottomNav
        items={bottomNavItems}
        messageCount={messageCount}
      />
    </div>
  )
}