import { useCallback, useEffect, useState } from 'react'

import { getAdminDispute, isRequestCancelled } from '../services/adminDisputesService'
import { parseAdminError } from '../utils/adminDisputeErrors'

/**
 * Loads one admin dispute. status: 'loading' | 'ready' | 'error'. `replace` swaps in a dispute returned by a write.
 * Each result is tagged with its request, so a result for another id is never shown.
 */
export function useAdminDispute(id) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ key: null, dispute: null, error: null })
  const requestKey = `${id}#${attempt}`

  useEffect(() => {
    const controller = new AbortController()
    getAdminDispute(id, controller.signal)
      .then((dispute) => {
        if (!controller.signal.aborted) setResult({ key: requestKey, dispute, error: null })
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isRequestCancelled(error)) {
          setResult({ key: requestKey, dispute: null, error: parseAdminError(error) })
        }
      })
    return () => controller.abort()
  }, [id, requestKey])

  const reload = useCallback(() => setAttempt((value) => value + 1), [])
  const replace = useCallback((dispute) => setResult({ key: requestKey, dispute, error: null }), [requestKey])

  let status = 'ready'
  if (result.key !== requestKey) status = 'loading'
  else if (result.error) status = 'error'
  return { status, dispute: result.dispute, error: result.error, reload, replace }
}
