import { useNavigate } from 'react-router-dom'

import { CARD_LOCKED, CARD_SIGN_IN } from '../access/accessRules'
import { areaPath } from '../config/adminAreas'
import { endSession, LOGIN_PATH } from '../services/authBridge'

/** Opens a role card: navigates to its area, or to login for the Super Admin sign-in card. */
export function useOpenRoleCard(onSelect) {
  const navigate = useNavigate()
  return (card) => {
    if (card.mode === CARD_LOCKED) {
      return
    }
    onSelect?.()
    if (card.mode === CARD_SIGN_IN) {
      // Super Admin needs its own account: end this session and go through login.
      endSession()
      navigate(LOGIN_PATH, { replace: true, state: { from: areaPath(card.area) } })
      return
    }
    navigate(areaPath(card.area))
  }
}
