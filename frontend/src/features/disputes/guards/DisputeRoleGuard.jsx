import { useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router-dom'

import { hasSession, LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import { DisputeAccessDenied, DisputeErrorState, DisputeSkeleton } from '../components/DisputeStates'
import { fetchCurrentUser, isDisputeFixtureMode, isRequestCancelled } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'

/**
 * Lets only signed-in users with `role` (CUSTOMER or PROVIDER) see a dispute screen.
 * The backend checks the role and dispute membership again on every request; this guard only decides
 * what to render. Mock mode (DEV only) skips sign-in so the screens can be previewed.
 */
export function DisputeRoleGuard({ role, children }) {
  if (isDisputeFixtureMode()) {
    return children
  }
  return <SessionRoleCheck role={role}>{children}</SessionRoleCheck>
}

function SessionRoleCheck({ role, children }) {
  const location = useLocation()
  const signedIn = hasSession()
  const [check, setCheck] = useState({ status: 'loading', error: null })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!signedIn) {
      return undefined
    }
    const controller = new AbortController()
    fetchCurrentUser(controller.signal)
      .then((user) => {
        if (!controller.signal.aborted) {
          setCheck({ status: user?.role === role ? 'allowed' : 'denied', error: null })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isRequestCancelled(error)) {
          setCheck({ status: 'error', error: parseApiError(error) })
        }
      })
    return () => controller.abort()
  }, [role, signedIn, attempt])

  const from = `${location.pathname}${location.search}`
  if (!signedIn || check.error?.kind === 'unauthorized') {
    return <Navigate to={LOGIN_PATH} replace state={{ from }} />
  }
  if (check.status === 'loading') {
    return <DisputeSkeleton />
  }
  if (check.status === 'denied' || check.error?.kind === 'forbidden') {
    return <DisputeAccessDenied />
  }
  if (check.status === 'error') {
    return (
      <DisputeErrorState
        message={check.error.message}
        onRetry={() => {
          setCheck({ status: 'loading', error: null })
          setAttempt((value) => value + 1)
        }}
      />
    )
  }
  return children
}
