import { NavLink } from 'react-router-dom'

import styles from './ProviderLayout.module.css'
import { ProviderIcon } from './ProviderIcon'
import { cx } from './providerLayoutUtils'

function normalizeCount(value) {
  const number = Number(value)

  if (!Number.isFinite(number) || number <= 0) {
    return 0
  }

  return Math.floor(number)
}

export function ProviderMobileBottomNav({
  items = [],
  messageCount = 0,
}) {
  const safeMessageCount =
    normalizeCount(messageCount)

  return (
    <nav
      className={styles.bottomNav}
      aria-label="Provider mobile navigation"
    >
      {items.map((item) => {
        const isMessages =
          item.id === 'messages' ||
          item.icon === 'messages' ||
          item.icon === 'message' ||
          item.label
            ?.toLowerCase()
            .includes('message')

        return (
          <NavLink
            key={item.id}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              cx(
                styles.bottomNavLink,
                isActive &&
                  styles.bottomNavLinkActive,
              )
            }
          >
            <span
              className={
                styles.bottomNavIconWrap
              }
            >
              <ProviderIcon
                name={item.icon}
                size={20}
              />

              {isMessages &&
                safeMessageCount > 0 && (
                  <span
                    className={
                      styles.bottomNavBadge
                    }
                    aria-hidden="true"
                  >
                    {safeMessageCount > 99
                      ? '99+'
                      : safeMessageCount}
                  </span>
                )}
            </span>

            <span>{item.label}</span>
          </NavLink>
        )
      })}
    </nav>
  )
}