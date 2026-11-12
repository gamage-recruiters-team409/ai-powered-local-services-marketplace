import { useOutletContext } from 'react-router-dom'

import '../../../shared/layouts/admin/adminTheme.css'
import { ADMIN_BRAND, BrandLogo, BrandName, cx, Icon } from '../../../shared/layouts/admin'
import { AREA_MODE, CARD_LOCKED, CARD_SIGN_IN, getRoleCards } from '../access/accessRules'
import { useAdminAccess } from '../access/useAdminAccess'
import { useOpenRoleCard } from '../components/useOpenRoleCard'
import { ADMIN_AREAS } from '../config/adminAreas'
import styles from './RoleSelectionPage.module.css'

function actionFor(card, info, isPrimary) {
  if (isPrimary) {
    return { label: `Continue as ${info.label}`, icon: 'arrowRight' }
  }
  if (card.mode === CARD_SIGN_IN) {
    return { label: 'Sign in as Super Admin', icon: 'lock' }
  }
  if (card.mode === CARD_LOCKED) {
    return { label: 'Not available for your role', icon: 'lock' }
  }
  return { label: 'Enter workspace', icon: 'arrowRight' }
}

function WorkspaceCard({ card, isPrimary, onOpen }) {
  const info = ADMIN_AREAS[card.area]
  const isLocked = card.mode === CARD_LOCKED
  const action = actionFor(card, info, isPrimary)
  const titleId = `role-card-${card.area}`

  return (
    <li className={styles.cardItem}>
      <article
        className={cx(styles.card, isPrimary && styles.cardPrimary, isLocked && styles.cardLocked)}
        aria-labelledby={titleId}
      >
        {isPrimary && (
          <span className={styles.primaryBadge}>
            <Icon name="check" size={12} strokeWidth={2.4} />
            Primary workspace
          </span>
        )}

        <div className={styles.cardTop}>
          <span className={styles.category}>{info.category}</span>
          <span className={styles.cardIcon} aria-hidden="true">
            <Icon name={info.icon} size={20} />
          </span>
        </div>

        <h2 id={titleId} className={styles.cardTitle}>
          {info.label}
          {isPrimary && <span className={styles.yourRoleDot} title="Your role" />}
          {isPrimary && <span className={styles.srOnly}> (your role)</span>}
          {card.mode === AREA_MODE.VIEW && (
            <span className={styles.viewOnly}>
              <Icon name="eye" size={13} />
              View only
            </span>
          )}
        </h2>
        <p className={styles.cardText}>{info.description}</p>

        <ul className={styles.tags} aria-label={`${info.label} features`}>
          {info.tags.map((tag) => (
            <li key={tag} className={styles.tag}>
              {tag}
            </li>
          ))}
        </ul>

        <div className={styles.cardFooter}>
          <button
            type="button"
            className={cx(styles.cardButton, isPrimary && styles.cardButtonPrimary)}
            disabled={isLocked}
            onClick={() => onOpen(card)}
          >
            {action.label}
            <Icon name={action.icon} size={16} />
          </button>
        </div>
      </article>
    </li>
  )
}

/** Full-page workspace role selection. The user's own role is highlighted as the primary workspace. */
export function RoleSelectionPage() {
  const { access } = useAdminAccess()
  const { user, onLogout } = useOutletContext()
  const openCard = useOpenRoleCard()
  const cards = getRoleCards(access)
  const helpLink = ADMIN_BRAND.footerLinks?.find((link) => link.label === 'Help')

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <span className={styles.brand}>
          <BrandLogo brand={ADMIN_BRAND} />
          <span className={styles.brandText}>
            <span className={styles.brandName}>
              <BrandName brand={ADMIN_BRAND} />
            </span>
            <span className={styles.portalLabel}>{ADMIN_BRAND.portalLabel}</span>
          </span>
        </span>

        <div className={styles.headerEnd}>
          <span className={styles.session}>
            <span className={styles.sessionDot} aria-hidden="true" />
            Signed in as <strong>{user.name}</strong>
          </span>
          <button type="button" className={styles.logout} onClick={onLogout}>
            <Icon name="logout" size={16} />
            Log out
          </button>
        </div>
      </header>

      <main className={styles.main}>
        <div className={styles.intro}>
          <h1 className={styles.title}>Select your workspace role</h1>
          <p className={styles.subtitle}>
            Choose a role to continue. You can make changes only in your own role. Other roles are view only or not
            available.
          </p>
        </div>

        <ul className={styles.cards} aria-label="Admin role areas">
          {cards.map((card) => (
            <WorkspaceCard key={card.area} card={card} isPrimary={card.area === access.role} onOpen={openCard} />
          ))}
        </ul>
      </main>

      <footer className={styles.footer}>
        <span>{ADMIN_BRAND.footerText}</span>
        <span className={styles.footerSep} aria-hidden="true">
          •
        </span>
        <span className={styles.footerSecure}>
          <Icon name="shield" size={14} />
          Secure session
        </span>
        {helpLink && (
          <>
            <span className={styles.footerSep} aria-hidden="true">
              •
            </span>
            <span>
              Need help? <a href={helpLink.href}>Contact system administrator</a>
            </span>
          </>
        )}
      </footer>
    </div>
  )
}
