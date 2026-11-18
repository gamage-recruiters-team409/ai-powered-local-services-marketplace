import { NavLink } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerIcon } from './CustomerIcon'
import { CustomerNavItemBadge } from './CustomerNavItemBadge'
import { cx, getInitials, splitFooterItems } from './customerLayoutUtils'

function SidebarLink({ item }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        cx(
          styles.navLink,
          item.tone === 'danger' && styles.navLinkDanger,
          isActive && styles.navLinkActive,
        )
      }
    >
      <CustomerIcon name={item.icon} size={22} className={styles.navIcon} />
      <span className={styles.navLabel}>{item.label}</span>
      <CustomerNavItemBadge item={item} />
    </NavLink>
  )
}

export function CustomerSidebar({
  id = 'customer-sidebar',
  user,
  sections = [],
  footerItems = [],
  onLogout,
}) {
  const name = user?.name?.trim() || 'Customer'
  const { regular, danger } = splitFooterItems(footerItems)
  const visibleSections = sections.filter((section) => section.items?.length > 0)

  return (
    <aside id={id} className={styles.sidebar} aria-label="Account sidebar">
      <div className={styles.sidebarInner}>
        <div className={styles.sidebarIdentity}>
          <span className={styles.sidebarAvatar} aria-hidden="true">
            {getInitials(name)}
          </span>

          <span className={styles.identityText}>
            <strong>{name}</strong>
            {user?.email && <span>{user.email}</span>}
          </span>
        </div>

        <nav className={styles.sidebarNav} aria-label="Account navigation">
          {visibleSections.map((section) => {
            const titleId = `${id}-${section.id}`

            return (
              <section
                key={section.id}
                className={styles.navSection}
                aria-labelledby={titleId}
              >
                <h2 id={titleId} className={styles.sectionTitle}>
                  {section.title}
                </h2>

                <ul className={styles.navList}>
                  {section.items.map((item) => (
                    <li key={item.id}>
                      <SidebarLink item={item} />
                    </li>
                  ))}
                </ul>
              </section>
            )
          })}

          <div className={styles.sidebarDivider} role="separator" />

          <ul className={styles.navList}>
            {regular.map((item) => (
              <li key={item.id}>
                <SidebarLink item={item} />
              </li>
            ))}

            <li>
              <button
                type="button"
                className={styles.navLink}
                onClick={onLogout}
              >
                <CustomerIcon name="logout" size={22} className={styles.navIcon} />
                <span className={styles.navLabel}>Log out</span>
              </button>
            </li>

            {danger.map((item) => (
              <li key={item.id}>
                <SidebarLink item={item} />
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </aside>
  )
}
