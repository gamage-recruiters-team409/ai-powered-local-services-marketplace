import { useId, useState } from 'react'
import { matchPath, NavLink, useLocation } from 'react-router-dom'

import styles from './AdminLayout.module.css'
import { BrandLogo } from './AdminHeader'
import { Icon } from './Icon'
import { cx, getInitials } from './adminLayoutUtils'

function NavItemLink({ item, onNavigate }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) => cx(styles.navLink, isActive && styles.navLinkActive)}
      onClick={onNavigate}
    >
      {item.icon && <Icon name={item.icon} className={styles.navIcon} />}
      <span>{item.label}</span>
    </NavLink>
  )
}

function NavGroup({ item, onNavigate }) {
  const { pathname } = useLocation()
  const listId = useId()
  const childActive = item.children.some((child) => matchPath({ path: child.to, end: false }, pathname))
  // null = follow the route (open while a child is active); true/false = user toggled it.
  const [manualOpen, setManualOpen] = useState(null)
  const isOpen = manualOpen ?? childActive

  return (
    <>
      <button
        type="button"
        className={styles.groupButton}
        aria-expanded={isOpen}
        aria-controls={listId}
        onClick={() => setManualOpen(!isOpen)}
      >
        {item.icon && <Icon name={item.icon} className={styles.navIcon} />}
        <span>{item.label}</span>
        <Icon name="chevronDown" size={16} className={cx(styles.groupChevron, isOpen && styles.groupChevronOpen)} />
      </button>
      <ul id={listId} className={styles.subList} hidden={!isOpen}>
        {item.children.map((child) => (
          <li key={child.id}>
            <NavLink
              to={child.to}
              end={child.end}
              className={({ isActive }) => cx(styles.subLink, isActive && styles.subLinkActive)}
              onClick={onNavigate}
            >
              {child.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </>
  )
}

export function AdminSidebar({
  id,
  open,
  brand,
  user,
  sections,
  drawerRef,
  closeButtonRef,
  onClose,
  onNavigate,
  onLogout,
  onSwitchRole,
}) {
  return (
    <aside
      id={id}
      ref={drawerRef}
      className={cx(styles.sidebar, open && styles.sidebarOpen)}
      // Open = mobile drawer over the page, so expose it as a modal dialog.
      role={open ? 'dialog' : undefined}
      aria-modal={open ? 'true' : undefined}
      aria-label={open ? 'Navigation menu' : 'Sidebar'}
    >
      <div className={cx(styles.drawerOnly, styles.drawerHeader)}>
        <span className={styles.drawerTitle}>
          <BrandLogo brand={brand} small />
          {brand.portalLabel}
        </span>
        <button
          ref={closeButtonRef}
          type="button"
          className={cx(styles.iconButton, styles.iconButtonPlain)}
          aria-label="Close navigation menu"
          onClick={onClose}
        >
          <Icon name="close" size={20} />
        </button>
      </div>

      <div className={cx(styles.drawerOnly, styles.drawerProfile)}>
        <span className={cx(styles.avatar, styles.avatarLarge)} aria-hidden="true">
          {getInitials(user.name)}
        </span>
        <span className={styles.profileText}>
          <span className={cx(styles.profileName, styles.profileNameLarge)}>{user.name}</span>
          <span className={styles.profileRole}>Signed in as: {user.roleLabel}</span>
        </span>
      </div>

      <nav className={styles.nav} aria-label="Admin navigation">
        {sections.map((section) => {
          const titleId = `${id}-${section.id}`
          return (
            <div key={section.id} className={styles.section}>
              {section.title && (
                <h2 id={titleId} className={styles.sectionTitle}>
                  {section.title}
                </h2>
              )}
              <ul className={styles.navList} aria-labelledby={section.title ? titleId : undefined}>
                {section.items.map((item) => (
                  <li key={item.id}>
                    {item.children ? (
                      <NavGroup item={item} onNavigate={onNavigate} />
                    ) : (
                      <NavItemLink item={item} onNavigate={onNavigate} />
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )
        })}
      </nav>

      <div className={styles.sidebarFooter}>
        {onSwitchRole && (
          <button type="button" className={cx(styles.switchButton, styles.drawerOnly)} onClick={onSwitchRole}>
            <Icon name="switch" size={16} />
            Switch role
          </button>
        )}
        <button type="button" className={cx(styles.navLink, styles.logoutButton)} onClick={onLogout}>
          <Icon name="logout" className={styles.navIcon} />
          <span>Log out</span>
        </button>
      </div>
    </aside>
  )
}
