import { Link, NavLink } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerIcon } from './CustomerIcon'
import { CustomerProfileMenu } from './CustomerProfileMenu'
import { cx } from './customerLayoutUtils'

function BrandLink({ brand }) {
  return (
    <Link to="/" className={styles.brand} aria-label={`${brand.name} home`}>
      <span className={styles.logo} aria-hidden="true">
        {brand.logoSrc ? (
          <img src={brand.logoSrc} alt="" />
        ) : (
          <CustomerIcon name="logo" size={19} strokeWidth={2} />
        )}
      </span>

      <span className={styles.brandName} aria-hidden="true">
        {brand.nameStart}
        <span className={styles.brandNameAccent}>{brand.nameEnd}</span>
      </span>
    </Link>
  )
}

/**
 * Optional location pill (e.g. "Colombo"). Hidden unless a label is given.
 */
function LocationButton({ location }) {
  if (!location?.label) {
    return null
  }

  return (
    <button
      type="button"
      className={styles.locationButton}
      aria-label={`Location: ${location.label}. Change location`}
      onClick={location.onClick}
      disabled={typeof location.onClick !== 'function'}
    >
      <CustomerIcon name="location" size={17} className={styles.locationIcon} />
      <span className={styles.locationLabel}>{location.label}</span>
      <CustomerIcon name="chevronDown" size={16} className={styles.locationChevron} />
    </button>
  )
}

export function CustomerHeader({
  brand,
  user,
  links = [],
  profileMenuItems = [],
  location = null,
  messagesAction = null,
  notificationsAction = null,
  onLogout,
}) {
  return (
    <header className={styles.header}>
      <div className={styles.headerStart}>
        <BrandLink brand={brand} />

        <nav className={styles.headerNav} aria-label="Main navigation">
          <ul className={styles.headerNavList}>
            {links.map((link) => (
              <li key={link.id}>
                <NavLink
                  to={link.to}
                  end={link.end}
                  className={({ isActive }) =>
                    cx(styles.headerNavLink, isActive && styles.headerNavLinkActive)
                  }
                >
                  {link.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </div>

      <div className={styles.headerEnd}>
        <LocationButton location={location} />

        {messagesAction && (
          <span className={styles.desktopOnly}>{messagesAction}</span>
        )}

        {notificationsAction}

        <span className={styles.desktopOnly}>
          <CustomerProfileMenu
            user={user}
            items={profileMenuItems}
            onLogout={onLogout}
          />
        </span>
      </div>
    </header>
  )
}
