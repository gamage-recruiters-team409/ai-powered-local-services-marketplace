import { NavLink } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerIcon } from './CustomerIcon'
import { CustomerNavItemBadge } from './CustomerNavItemBadge'
import { cx } from './customerLayoutUtils'

export function CustomerMobileBottomNav({ items = [] }) {
  return (
    <nav
      className={styles.bottomNav}
      aria-label="Customer mobile navigation"
      style={{ '--cus-bottom-nav-columns': Math.max(items.length, 1) }}
    >
      {items.map((item) => (
        <NavLink
          key={item.id}
          to={item.to}
          end={item.end}
          className={({ isActive }) =>
            cx(styles.bottomNavLink, isActive && styles.bottomNavLinkActive)
          }
        >
          <span className={styles.bottomNavIconWrap}>
            <CustomerIcon name={item.icon} size={22} />
            <span className={styles.bottomNavBadge}>
              <CustomerNavItemBadge item={item} />
            </span>
          </span>

          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  )
}
