import {
  useCallback,
  useEffect,
  useLayoutEffect,
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

const FOCUS_MENU_BUTTON = 'menu-button'
const FOCUS_MAIN = 'main'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

const FALLBACK_USER = {
  name: 'Provider Name',
  email: 'providername@email.com',
}

function getFocusable(container) {
  if (!container) {
    return []
  }

  return [
    ...container.querySelectorAll(
      FOCUSABLE_SELECTOR,
    ),
  ]
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
  const [mobile, setMobile] =
    useState(getInitialMobileState)

  const [drawerOpen, setDrawerOpen] =
    useState(false)

  const [
    sidebarCollapsed,
    setSidebarCollapsed,
  ] = useState(false)

  const menuButtonRef = useRef(null)
  const drawerRef = useRef(null)
  const mainRef = useRef(null)

  const focusAfterCloseRef = useRef(null)

  const closeDrawer = useCallback(
    (focusTarget = null) => {
      focusAfterCloseRef.current =
        focusTarget

      setDrawerOpen(false)
    },
    [],
  )

  /*
   * Restore focus only after the drawer has closed
   * and the background is no longer inert.
   */
  useLayoutEffect(() => {
    if (drawerOpen) {
      return
    }

    const focusTarget =
      focusAfterCloseRef.current

    if (!focusTarget) {
      return
    }

    focusAfterCloseRef.current = null

    if (
      focusTarget ===
      FOCUS_MENU_BUTTON
    ) {
      menuButtonRef.current?.focus()
      return
    }

    if (
      focusTarget ===
      FOCUS_MAIN
    ) {
      mainRef.current?.focus()
    }
  }, [drawerOpen])

  /*
   * Mobile drawer keyboard accessibility.
   *
   * - Moves focus inside the drawer.
   * - Traps Tab / Shift+Tab.
   * - Escape closes the drawer.
   */
  useEffect(() => {
    if (!drawerOpen || !mobile) {
      return undefined
    }

    const drawer = drawerRef.current

    const initialFocusTarget =
      getFocusable(drawer)[0] ?? drawer

    initialFocusTarget?.focus()

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()

        closeDrawer(
          FOCUS_MENU_BUTTON,
        )

        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const items =
        getFocusable(drawer)

      if (items.length === 0) {
        event.preventDefault()
        drawer?.focus()

        return
      }

      const first = items[0]
      const last =
        items[items.length - 1]

      const active =
        document.activeElement

      if (!drawer?.contains(active)) {
        event.preventDefault()
        first.focus()

        return
      }

      if (
        event.shiftKey &&
        active === first
      ) {
        event.preventDefault()
        last.focus()

        return
      }

      if (
        !event.shiftKey &&
        active === last
      ) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener(
      'keydown',
      handleKeyDown,
    )

    return () => {
      document.removeEventListener(
        'keydown',
        handleKeyDown,
      )
    }
  }, [
    drawerOpen,
    mobile,
    closeDrawer,
  ])

  /*
   * Listen for future viewport changes.
   */
  useEffect(() => {
    if (
      typeof window === 'undefined' ||
      typeof window.matchMedia !==
        'function'
    ) {
      return undefined
    }

    const mediaQuery =
      window.matchMedia(MOBILE_QUERY)

    const handleChange = (event) => {
      setMobile(event.matches)

      if (!event.matches) {
        closeDrawer(null)
      }
    }

    mediaQuery.addEventListener(
      'change',
      handleChange,
    )

    return () => {
      mediaQuery.removeEventListener(
        'change',
        handleChange,
      )
    }
  }, [closeDrawer])

  /*
   * Desktop:
   *   expand/collapse sidebar.
   *
   * Mobile:
   *   open/close navigation drawer.
   */
  const handleMenuClick = () => {
    if (mobile) {
      if (drawerOpen) {
        closeDrawer(
          FOCUS_MENU_BUTTON,
        )
      } else {
        setDrawerOpen(true)
      }

      return
    }

    setSidebarCollapsed(
      (value) => !value,
    )
  }

  /*
   * Navigation from the mobile drawer
   * closes it and moves focus to main.
   */
  const handleNavigate = () => {
    if (mobile) {
      closeDrawer(FOCUS_MAIN)
    }
  }

  const drawerModalActive =
    mobile && drawerOpen

  return (
    <div
      className={`providerTheme ${styles.layout}`}
    >
      <a
        href={`#${MAIN_ID}`}
        className={styles.skipLink}
        inert={drawerModalActive}
      >
        Skip to main content
      </a>

      <ProviderHeader
        brand={brand}
        user={user}
        mobile={mobile}
        drawerOpen={drawerOpen}
        sidebarCollapsed={
          sidebarCollapsed
        }
        menuButtonRef={menuButtonRef}
        sidebarId={SIDEBAR_ID}
        onMenuClick={handleMenuClick}
        notificationCount={
          notificationCount
        }
        onNotificationsClick={
          onNotificationsClick
        }
        onLogout={onLogout}
        inert={drawerModalActive}
      />

      <div className={styles.body}>
        {drawerModalActive && (
          <div
            className={styles.overlay}
            aria-hidden="true"
            data-testid="provider-drawer-backdrop"
            onMouseDown={(event) => {
              /*
               * Keep focus inside the drawer until
               * the close state has rendered.
               */
              event.preventDefault()
            }}
            onClick={() =>
              closeDrawer(
                FOCUS_MENU_BUTTON,
              )
            }
          />
        )}

        <ProviderSidebar
          id={SIDEBAR_ID}
          mobile={mobile}
          open={drawerOpen}
          collapsed={
            sidebarCollapsed
          }
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
          inert={drawerModalActive}
        >
          {children ?? <Outlet />}
        </main>
      </div>

      <footer
        className={styles.footer}
        inert={drawerModalActive}
      >
        <strong>
          {brand.footerBrand}
        </strong>

        <nav aria-label="Footer">
          <ul
            className={
              styles.footerLinks
            }
          >
            {brand.footerLinks.map(
              (link) => (
                <li key={link.label}>
                  <a href={link.href}>
                    {link.label}
                  </a>
                </li>
              ),
            )}
          </ul>
        </nav>

        <span>
          {brand.footerText}
        </span>
      </footer>

      <ProviderMobileBottomNav
        items={bottomNavItems}
        messageCount={messageCount}
        inert={drawerModalActive}
      />
    </div>
  )
}