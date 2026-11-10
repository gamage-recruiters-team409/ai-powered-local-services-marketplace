import { Navigate, useLocation } from 'react-router-dom'

import { hasSession, LOGIN_PATH } from '../services/authBridge'

/** Sends signed-out users to login and remembers where they were going. */
export function RequireAuth({ children }) {
  const location = useLocation()
  if (!hasSession()) {
    return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  return children
}
