import { NavLink } from 'react-router-dom'

import styles from './ProviderLayout.module.css'
import { ProviderIcon } from './ProviderIcon'
import { cx, getInitials } from './providerLayoutUtils'

function ProviderIdentity({ user }) {
  return (
    <div className={styles.providerIdentity}>
      <span className={styles.providerAvatar} aria-hidden="true">
        {getInitials(user.name)}
      </span>

      <span className={styles.providerIdentityText}>
        <strong>{user.name}</strong>
        <span>{user.email}</span>
      </span>
    </div>
  )
}

export function ProviderSidebar({
  id,
  mobile,
  open,
  collapsed,
  user,
  sections,
  drawerRef,
  onNavigate,
  onLogout,
}) {
  return (
    <aside
      id={id}
      ref={drawerRef}
      className={cx(
        styles.sidebar,
        collapsed && !mobile && styles.sidebarCollapsed,
        open && styles.sidebarOpen,
      )}
      role={mobile && open ? 'dialog' : undefined}
      aria-modal={mobile && open ? 'true' : undefined}
      aria-label={mobile && open ? 'Provider navigation menu' : 'Provider sidebar'}
    >
      <ProviderIdentity user={user} />

      <nav
        className={styles.sidebarNav}
        aria-label="Provider navigation"
      >
        {sections.map((section) => {
          const titleId = `${id}-${section.id}`

          return (
            <section
              key={section.id}
              className={styles.navSection}
              aria-labelledby={titleId}
            >
              <h2
                id={titleId}
                className={styles.sectionTitle}
              >
                {section.title}
              </h2>

              <ul className={styles.navList}>
                {section.items.map((item) => (
                  <li key={item.id}>
                    <NavLink
                      to={item.to}
                      end={item.end}
                      className={({ isActive }) =>
                        cx(
                          styles.navLink,
                          isActive && styles.navLinkActive,
                        )
                      }
                      onClick={onNavigate}
                    >
                      <ProviderIcon
                        name={item.icon}
                        className={styles.navIcon}
                      />

                      <span>{item.label}</span>
                    </NavLink>
                  </li>
                ))}
              </ul>
            </section>
          )
        })}
      </nav>

      <div className={styles.sidebarFooter}>
        <button
          type="button"
          className={styles.logoutButton}
          onClick={onLogout}
        >
          <ProviderIcon name="logout" size={18} />
          <span>Log out</span>
        </button>
      </div>
    </aside>
  )
}