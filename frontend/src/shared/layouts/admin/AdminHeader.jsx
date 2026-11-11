import { useEffect, useId, useRef, useState } from 'react'

import styles from './AdminLayout.module.css'
import { Icon } from './Icon'
import { cx, getInitials } from './adminLayoutUtils'

function BrandName({ brand }) {
  const { name, nameHighlight } = brand
  if (!nameHighlight || !name.endsWith(nameHighlight)) {
    return name
  }
  return (
    <>
      {name.slice(0, -nameHighlight.length)}
      <span className={styles.brandNameHighlight}>{nameHighlight}</span>
    </>
  )
}

export function BrandLogo({ brand, small = false }) {
  return (
    <span className={cx(styles.logo, small && styles.logoSmall)} aria-hidden="true">
      {brand.logoSrc ? <img src={brand.logoSrc} alt="" /> : <Icon name="wrench" size={small ? 16 : 20} strokeWidth={2} />}
    </span>
  )
}

function NotificationsButton({ count, onClick }) {
  const label = count > 0 ? `Notifications, ${count} unread` : 'Notifications'
  return (
    <button
      type="button"
      className={styles.iconButton}
      aria-label={label}
      title={onClick ? label : 'Notifications (coming soon)'}
      onClick={onClick}
      disabled={!onClick}
    >
      <Icon name="bell" size={19} />
      {count > 0 && (
        <span className={styles.badge} aria-hidden="true">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </button>
  )
}

function ProfileMenu({ user, onLogout, onSwitchRole }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef(null)
  const buttonRef = useRef(null)
  const menuRef = useRef(null)
  const menuId = useId()

  useEffect(() => {
    if (!open) {
      return undefined
    }
    menuRef.current?.querySelector('[role="menuitem"]')?.focus()

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
    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  const handleMenuKeyDown = (event) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return
    }
    event.preventDefault()
    const items = [...(menuRef.current?.querySelectorAll('[role="menuitem"]') ?? [])]
    const index = items.indexOf(document.activeElement)
    const step = event.key === 'ArrowDown' ? 1 : -1
    items[(index + step + items.length) % items.length]?.focus()
  }

  const choose = (action) => {
    setOpen(false)
    action()
  }

  return (
    <div className={styles.profile} ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.profileButton}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`Account menu: ${user.name}, signed in as ${user.roleLabel}`}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={styles.avatar} aria-hidden="true">
          {getInitials(user.name)}
        </span>
        <span className={styles.profileText} aria-hidden="true">
          <span className={styles.profileName}>{user.name}</span>
          <span className={styles.profileRole}>Signed in as: {user.roleLabel}</span>
        </span>
      </button>

      {open && (
        <ul
          id={menuId}
          ref={menuRef}
          role="menu"
          aria-label="Account"
          className={styles.menu}
          onKeyDown={handleMenuKeyDown}
        >
          {onSwitchRole && (
            <li role="none">
              <button type="button" role="menuitem" className={styles.menuItem} onClick={() => choose(onSwitchRole)}>
                <Icon name="switch" size={16} />
                Switch role
              </button>
            </li>
          )}
          <li role="none">
            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => choose(onLogout)}>
              <Icon name="logout" size={16} />
              Log out
            </button>
          </li>
        </ul>
      )}
    </div>
  )
}

export function AdminHeader({
  brand,
  user,
  sidebarId,
  drawerOpen,
  menuButtonRef,
  onMenuClick,
  notificationCount,
  onNotificationsClick,
  onLogout,
  onSwitchRole,
  inert = false,
}) {
  return (
    <header className={styles.header} inert={inert}>
      <div className={styles.headerStart}>
        <button
          ref={menuButtonRef}
          type="button"
          className={cx(styles.iconButton, styles.menuButton)}
          aria-label="Open navigation menu"
          aria-controls={sidebarId}
          aria-expanded={drawerOpen}
          onClick={onMenuClick}
        >
          <Icon name="menu" size={20} />
        </button>
        <span className={styles.brand}>
          <BrandLogo brand={brand} />
          <span className={styles.brandName}>
            <BrandName brand={brand} />
          </span>
        </span>
      </div>

      <div className={styles.headerEnd}>
        <NotificationsButton count={notificationCount} onClick={onNotificationsClick} />
        <ProfileMenu user={user} onLogout={onLogout} onSwitchRole={onSwitchRole} />
      </div>
    </header>
  )
}
