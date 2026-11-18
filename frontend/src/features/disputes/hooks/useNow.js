import { useEffect, useState } from 'react'

const MINUTE_MS = 60 * 1000

/** Current time, refreshed every minute (or `intervalMs`). The timer is cleared on unmount. */
export function useNow(intervalMs = MINUTE_MS) {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])

  return now
}
