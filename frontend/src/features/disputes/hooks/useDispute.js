import { useCallback, useEffect, useState } from 'react'

import { fetchDispute, isRequestCancelled } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'

/**
 * Loads one dispute. status: 'loading' | 'ready' | 'error'.
 * `reload` refetches; `replace` swaps in a dispute returned by a write (no second request).
 * Each result is tagged with the request it belongs to, so "loading" is derived instead of set in the effect.
 */
export function useDispute(disputeId) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ key: null, dispute: null, error: null })
  const requestKey = `${disputeId}#${attempt}`

  useEffect(() => {
    const controller = new AbortController()
    fetchDispute(disputeId, controller.signal)
      .then((dispute) => {
        if (!controller.signal.aborted) {
          setResult({ key: requestKey, dispute, error: null })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isRequestCancelled(error)) {
          setResult({ key: requestKey, dispute: null, error: parseApiError(error) })
        }
      })
    return () => controller.abort()
  }, [disputeId, requestKey])

  const reload = useCallback(() => setAttempt((value) => value + 1), [])
  const replace = useCallback(
    (dispute) => setResult({ key: requestKey, dispute, error: null }),
    [requestKey],
  )
  /** Refetch in the background, keeping the current screen (and any message on it) until fresh data arrives. */
  const refresh = useCallback(() => {
    fetchDispute(disputeId)
      .then((dispute) => setResult((current) => (current.key === requestKey ? { key: requestKey, dispute, error: null } : current)))
      .catch(() => {
        // Keep showing what we have; the user can reload the page.
      })
  }, [disputeId, requestKey])

  let status = 'ready'
  if (result.key !== requestKey) {
    status = 'loading'
  } else if (result.error) {
    status = 'error'
  }
  return { status, dispute: result.dispute, error: result.error, reload, replace, refresh }
}
