import { useCallback, useEffect, useMemo, useState } from 'react'

import { endSession } from '../services/authBridge'
import { fetchAdminAccess, fetchCurrentUser, isRequestCancelled } from '../services/adminApi'
import { ACCESS_ERROR, classifyAccessError, normalizeAccess } from './accessRules'
import { ACCESS_STATUS, AdminAccessContext } from './accessContexts'

const INITIAL_STATE = { status: ACCESS_STATUS.LOADING, access: null, user: null, errorKind: null }

/** Loads GET /admin/access (and the user's name) once for every admin page. */
export function AdminAccessProvider({ children }) {
  const [state, setState] = useState(INITIAL_STATE)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    Promise.all([
      fetchAdminAccess(controller.signal),
      // The name is only for the header, so a failure here must not block the layout.
      fetchCurrentUser(controller.signal).catch(() => null),
    ])
      .then(([accessData, user]) => {
        setState({ status: ACCESS_STATUS.READY, access: normalizeAccess(accessData), user, errorKind: null })
      })
      .catch((error) => {
        if (isRequestCancelled(error)) {
          return
        }
        const errorKind = classifyAccessError(error)
        if (errorKind === ACCESS_ERROR.UNAUTHORIZED) {
          endSession()
        }
        setState({ status: ACCESS_STATUS.ERROR, access: null, user: null, errorKind })
      })

    return () => controller.abort()
  }, [attempt])

  const reload = useCallback(() => {
    setState(INITIAL_STATE)
    setAttempt((value) => value + 1)
  }, [])

  const value = useMemo(() => ({ ...state, reload }), [state, reload])

  return <AdminAccessContext.Provider value={value}>{children}</AdminAccessContext.Provider>
}
