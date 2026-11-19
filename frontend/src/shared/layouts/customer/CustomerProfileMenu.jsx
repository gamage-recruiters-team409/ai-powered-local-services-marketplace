import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerIcon } from './CustomerIcon'
import { getInitials, getShortName } from './customerLayoutUtils'

export function CustomerProfileMenu({
  user,
  items = [],
  onLogout,
}) {
  const [open, setOpen] = useState(false)

  const rootRef = useRef(null)
  const buttonRef = useRef(null)
  const menuRef = useRef(null)

  const menuId = useId()

  const name = user?.name?.trim() || 'Customer'

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

    const menuItems = [
      ...(menuRef.current?.querySelectorAll('[role="menuitem"]') ?? []),
    ]

    if (menuItems.length === 0) {
      return
    }

    const currentIndex = menuItems.indexOf(document.activeElement)
    const direction = event.key === 'ArrowDown' ? 1 : -1
    const nextIndex =
      (currentIndex + direction + menuItems.length) % menuItems.length

    menuItems[nextIndex]?.focus()
  }

  /** Keyboard users tabbing out of the menu: close it without moving focus back. */
  const handleBlur = (event) => {
    if (!open) {
      return
    }

    const next = event.relatedTarget

    if (next && !rootRef.current?.contains(next)) {
      setOpen(false)
    }
  }

  const handleLogout = () => {
    setOpen(false)

    if (typeof onLogout === 'function') {
      onLogout()
    }
  }

  return (
    <div className={styles.profileMenuRoot} ref={rootRef} onBlur={handleBlur}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.profileButton}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`Account menu for ${name}`}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={styles.headerAvatar} aria-hidden="true">
          {getInitials(name)}
        </span>

        <span className={styles.headerProfileName}>
          {getShortName(name)}
        </span>

        <CustomerIcon
          name="chevronDown"
          size={16}
          className={open ? styles.profileChevronOpen : styles.profileChevron}
        />
      </button>

      {open && (
        <div
          id={menuId}
          ref={menuRef}
          role="menu"
          aria-label="Customer account"
          className={styles.profileMenu}
          onKeyDown={handleMenuKeyDown}
        >
          <div className={styles.profileMenuIdentity} role="presentation">
            <span className={styles.menuAvatar} aria-hidden="true">
              {getInitials(name)}
            </span>

            <span className={styles.identityText}>
              <strong>{name}</strong>
              {user?.email && <span>{user.email}</span>}
            </span>
          </div>

          {items.map((item) => (
            <Link
              key={item.id}
              to={item.to}
              role="menuitem"
              className={styles.profileMenuItem}
              onClick={() => setOpen(false)}
            >
              <CustomerIcon name={item.icon} size={20} />
              {item.label}
            </Link>
          ))}

          <div className={styles.profileMenuDivider} role="separator" />

          <button
            type="button"
            role="menuitem"
            className={styles.profileMenuItem}
            onClick={handleLogout}
          >
            <CustomerIcon name="logout" size={20} />
            Log out
          </button>
        </div>
      )}
    </div>
  )
}
