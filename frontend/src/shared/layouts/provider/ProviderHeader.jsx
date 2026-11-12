import { useEffect, useId, useRef, useState } from 'react'

import styles from './ProviderLayout.module.css'
import { ProviderIcon } from './ProviderIcon'
import { cx, getInitials } from './providerLayoutUtils'

function normalizeCount(value) {
  const number = Number(value)

  if (!Number.isFinite(number) || number <= 0) {
    return 0
  }

  return Math.floor(number)
}

function BrandLogo({ brand }) {
  return (
    <span className={styles.logo} aria-hidden="true">
      {brand?.logoSrc ? (
        <img src={brand.logoSrc} alt="" />
      ) : (
        <ProviderIcon
          name="home"
          size={20}
          strokeWidth={2}
        />
      )}
    </span>
  )
}

function NotificationsButton({
  count = 0,
  onClick,
}) {
  const safeCount = normalizeCount(count)

  const label =
    safeCount > 0
      ? `Notifications, ${safeCount} unread`
      : 'Notifications'

  return (
    <button
      type="button"
      className={styles.iconButton}
      aria-label={label}
      title={label}
      onClick={onClick}
      disabled={!onClick}
    >
      <ProviderIcon
        name="bell"
        size={19}
      />

      {safeCount > 0 && (
        <span
          className={styles.notificationBadge}
          aria-hidden="true"
        >
          {safeCount > 99 ? '99+' : safeCount}
        </span>
      )}
    </button>
  )
}

function ProfileMenu({
  user,
  onLogout,
}) {
  const [open, setOpen] = useState(false)

  const rootRef = useRef(null)
  const buttonRef = useRef(null)
  const menuRef = useRef(null)

  const menuId = useId()

  const name =
    user?.name?.trim() ||
    'Provider'

  useEffect(() => {
    if (!open) {
      return undefined
    }

    menuRef.current
      ?.querySelector('[role="menuitem"]')
      ?.focus()

    const handlePointerDown = (event) => {
      if (!rootRef.current?.contains(event.target)) {
        setOpen(false)
      }
    }

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }

    document.addEventListener(
      'pointerdown',
      handlePointerDown,
    )

    document.addEventListener(
      'keydown',
      handleKeyDown,
    )

    return () => {
      document.removeEventListener(
        'pointerdown',
        handlePointerDown,
      )

      document.removeEventListener(
        'keydown',
        handleKeyDown,
      )
    }
  }, [open])

  const handleMenuKeyDown = (event) => {
    if (
      event.key !== 'ArrowDown' &&
      event.key !== 'ArrowUp'
    ) {
      return
    }

    event.preventDefault()

    const items = [
      ...(
        menuRef.current?.querySelectorAll(
          '[role="menuitem"]',
        ) ?? []
      ),
    ]

    if (items.length === 0) {
      return
    }

    const currentIndex = items.indexOf(
      document.activeElement,
    )

    const direction =
      event.key === 'ArrowDown' ? 1 : -1

    const nextIndex =
      (
        currentIndex +
        direction +
        items.length
      ) % items.length

    items[nextIndex]?.focus()
  }

  const handleLogout = () => {
    setOpen(false)

    if (typeof onLogout === 'function') {
      onLogout()
    }
  }

  return (
    <div
      className={styles.profileMenuRoot}
      ref={rootRef}
    >
      <button
        ref={buttonRef}
        type="button"
        className={styles.profileButton}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={
          open ? menuId : undefined
        }
        aria-label={`Account menu for ${name}`}
        onClick={() =>
          setOpen((value) => !value)
        }
      >
        <span
          className={styles.headerAvatar}
          aria-hidden="true"
        >
          {getInitials(name)}
        </span>

        <span
          className={styles.headerProfileName}
        >
          {name}
        </span>

        <ProviderIcon
          name="chevronDown"
          size={14}
          className={styles.profileChevron}
        />
      </button>

      {open && (
        <ul
          id={menuId}
          ref={menuRef}
          role="menu"
          aria-label="Provider account"
          className={styles.profileMenu}
          onKeyDown={handleMenuKeyDown}
        >
          <li role="none">
            <button
              type="button"
              role="menuitem"
              className={styles.profileMenuItem}
              onClick={handleLogout}
            >
              <ProviderIcon
                name="logout"
                size={16}
              />
              Log out
            </button>
          </li>
        </ul>
      )}
    </div>
  )
}

export function ProviderHeader({
  brand,
  user,
  sidebarId,
  drawerOpen,
  menuButtonRef,
  onMenuClick,
  notificationCount = 0,
  onNotificationsClick,
  onLogout,
  inert = false,
}) {
  return (
    <header
      className={styles.header}
      inert={inert}
    >
      <div className={styles.headerStart}>
        <button
          ref={menuButtonRef}
          type="button"
          className={cx(
            styles.iconButton,
            styles.menuButton,
          )}
          aria-label={
            drawerOpen
              ? 'Close navigation menu'
              : 'Open navigation menu'
          }
          aria-controls={sidebarId}
          aria-expanded={drawerOpen}
          onClick={onMenuClick}
        >
          <ProviderIcon
            name="menu"
            size={20}
          />
        </button>

        <span className={styles.brand}>
          <BrandLogo brand={brand} />

          <span className={styles.brandName}>
            {brand?.name || 'LocalServe'}
          </span>
        </span>
      </div>

      <div className={styles.headerEnd}>
        <NotificationsButton
          count={notificationCount}
          onClick={onNotificationsClick}
        />

        <div className={styles.desktopProfile}>
          <ProfileMenu
            user={user}
            onLogout={onLogout}
          />
        </div>
      </div>
    </header>
  )
}