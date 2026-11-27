import { Link } from 'react-router-dom'

import styles from './CustomerLayout.module.css'
import { CustomerAvatar } from './CustomerAvatar'
import { CustomerIcon } from './CustomerIcon'
import { CustomerNavItemBadge } from './CustomerNavItemBadge'
import { cx, splitFooterItems } from './customerLayoutUtils'
import {
  CUSTOMER_NAV_FOOTER_ITEMS,
  CUSTOMER_NAV_SECTIONS,
  CUSTOMER_PROFILE_PATH,
} from './customerNavConfig'

function MenuRow({ item }) {
  return (
    <li>
      <Link
        to={item.to}
        className={cx(styles.accountRow, item.tone === 'danger' && styles.accountRowDanger)}
      >
        <CustomerIcon name={item.icon} size={22} className={styles.navIcon} />
        <span className={styles.navLabel}>{item.label}</span>
        <CustomerNavItemBadge item={item} />
        {item.tone !== 'danger' && (
          <CustomerIcon name="chevronRight" size={18} className={styles.accountRowChevron} />
        )}
      </Link>
    </li>
  )
}

/**
 * Mobile account menu (the "Account" tab): every account item from the nav config in one list.
 * Rendered by the /customer/account page.
 */
export function CustomerAccountMenu({
  user,
  sections = CUSTOMER_NAV_SECTIONS,
  footerItems = CUSTOMER_NAV_FOOTER_ITEMS,
  profileTo = CUSTOMER_PROFILE_PATH,
  onLogout,
}) {
  const name = user?.name?.trim() || 'Customer'
  const { regular, danger } = splitFooterItems(footerItems)
  const items = [...sections.flatMap((section) => section.items ?? []), ...regular]

  const identity = (
    <>
      <CustomerAvatar
        name={name}
        src={user?.avatarUrl}
        className={styles.accountAvatar}
      />

      <span className={styles.identityText}>
        <strong>{name}</strong>
        {profileTo ? (
          <span className={styles.viewProfile}>View profile</span>
        ) : (
          user?.email && <span>{user.email}</span>
        )}
      </span>

      {profileTo && (
        <CustomerIcon name="chevronRight" size={18} className={styles.accountRowChevron} />
      )}
    </>
  )

  return (
    <div className={styles.accountMenu}>
      {profileTo ? (
        <Link to={profileTo} className={cx(styles.accountCard, styles.accountIdentity)}>
          {identity}
        </Link>
      ) : (
        <div className={cx(styles.accountCard, styles.accountIdentity)}>{identity}</div>
      )}

      {items.length > 0 && (
        <nav aria-label="Account menu">
          <ul className={cx(styles.accountCard, styles.accountList)}>
            {items.map((item) => (
              <MenuRow key={item.id} item={item} />
            ))}
          </ul>
        </nav>
      )}

      <ul className={cx(styles.accountCard, styles.accountList)}>
        <li>
          <button type="button" className={styles.accountRow} onClick={onLogout}>
            <CustomerIcon name="logout" size={22} className={styles.navIcon} />
            <span className={styles.navLabel}>Log out</span>
          </button>
        </li>

        {danger.map((item) => (
          <MenuRow key={item.id} item={item} />
        ))}
      </ul>
    </div>
  )
}
