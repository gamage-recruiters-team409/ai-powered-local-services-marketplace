const MINUTE_MS = 60 * 1000
const MINUTES_PER_DAY = 24 * 60

/**
 * Time left until a UTC deadline (ISO-8601 string from the backend).
 * Returns null for a missing or invalid date. Overdue when the deadline is now or in the past.
 */
export function getTimeLeft(dueAt, now = Date.now()) {
  const due = typeof dueAt === 'string' ? Date.parse(dueAt) : Number.NaN
  if (Number.isNaN(due)) {
    return null
  }
  const remainingMs = due - now
  if (remainingMs <= 0) {
    return { overdue: true, days: 0, hours: 0, minutes: 0 }
  }
  const totalMinutes = Math.floor(remainingMs / MINUTE_MS)
  return {
    overdue: false,
    days: Math.floor(totalMinutes / MINUTES_PER_DAY),
    hours: Math.floor((totalMinutes % MINUTES_PER_DAY) / 60),
    minutes: totalMinutes % 60,
  }
}

function plural(count, word) {
  return `${count} ${word}${count === 1 ? '' : 's'}`
}

/** "2 days 4 hours left", "3 hours 12 minutes left", "less than a minute left" or "Overdue". */
export function formatTimeLeft(timeLeft) {
  if (!timeLeft) {
    return ''
  }
  if (timeLeft.overdue) {
    return 'Overdue'
  }
  const { days, hours, minutes } = timeLeft
  if (days > 0) {
    return `${plural(days, 'day')} ${plural(hours, 'hour')} left`
  }
  if (hours > 0) {
    return `${plural(hours, 'hour')} ${plural(minutes, 'minute')} left`
  }
  if (minutes > 0) {
    return `${plural(minutes, 'minute')} left`
  }
  return 'less than a minute left'
}
