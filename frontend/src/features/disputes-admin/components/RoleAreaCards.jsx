import { useLocation, useNavigate } from 'react-router-dom'

import { cx, Icon } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'
import { ADMIN_AREAS, areaFromPathname, areaPath } from '../config/adminAreas'
import { AREA_MODE, CARD_SIGN_IN, getRoleCards } from '../access/accessRules'
import { useAdminAccess } from '../access/useAdminAccess'
import { endSession, LOGIN_PATH } from '../services/authBridge'

const CHIPS = {
  [AREA_MODE.EDIT]: { label: 'Full access', className: styles.chipEdit },
  [AREA_MODE.VIEW]: { label: 'View only', className: styles.chipView },
  [CARD_SIGN_IN]: { label: 'Sign-in required', className: styles.chipSignIn },
}

function actionLabel(mode, isCurrent) {
  if (isCurrent) {
    return 'Current area'
  }
  if (mode === CARD_SIGN_IN) {
    return 'Sign in as Super Admin'
  }
  return mode === AREA_MODE.VIEW ? 'View area' : 'Open area'
}

/** Role-area cards used by the role selection page and the switch-role modal. */
export function RoleAreaCards({ compact = false, onSelect }) {
  const { access } = useAdminAccess()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const currentArea = areaFromPathname(pathname)

  const openCard = (card) => {
    onSelect?.()
    if (card.mode === CARD_SIGN_IN) {
      // Super Admin needs its own account: end this session and go through login.
      endSession()
      navigate(LOGIN_PATH, { replace: true, state: { from: areaPath(card.area) } })
      return
    }
    navigate(areaPath(card.area))
  }

  return (
    <ul className={cx(styles.cards, compact && styles.cardsCompact)} aria-label="Admin role areas">
      {getRoleCards(access).map((card) => {
        const info = ADMIN_AREAS[card.area]
        const chip = CHIPS[card.mode]
        const isCurrent = card.area === currentArea
        return (
          <li key={card.area}>
            <button
              type="button"
              className={cx(styles.card, isCurrent && styles.cardCurrent)}
              aria-current={isCurrent ? 'true' : undefined}
              onClick={() => openCard(card)}
            >
              <span className={styles.cardTop}>
                <span className={styles.cardTitle}>{info.label}</span>
                <span className={cx(styles.chip, chip.className)}>
                  {card.mode === AREA_MODE.VIEW && <Icon name="eye" size={13} />}
                  {card.mode === CARD_SIGN_IN && <Icon name="lock" size={13} />}
                  {chip.label}
                </span>
              </span>
              <span className={styles.cardText}>{info.description}</span>
              <span className={styles.cardAction}>{actionLabel(card.mode, isCurrent)}</span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}
