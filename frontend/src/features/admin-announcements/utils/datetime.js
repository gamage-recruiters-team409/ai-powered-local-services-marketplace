/**
 * Dates are stored and sent in UTC (ISO-8601) and always shown in the admin's own browser time zone.
 */

const DATE = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
const TIME = new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit' })

function toDate(value) {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** "Mar 28, 2026 • 09:00 AM" in local time, or '' for empty/invalid input. */
export function formatDateTime(value) {
  const date = toDate(value)
  return date ? `${DATE.format(date)} • ${TIME.format(date)}` : ''
}

/** "Mar 28, 2026" in local time. */
export function formatDate(value) {
  const date = toDate(value)
  return date ? DATE.format(date) : ''
}

/** IANA zone of the browser, e.g. "Asia/Colombo". */
export function browserTimeZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'local time'
  } catch {
    return 'local time'
  }
}

/** "UTC+05:30" for the given moment (offsets change with daylight saving). */
export function utcOffsetLabel(at = new Date()) {
  const minutes = -at.getTimezoneOffset()
  const sign = minutes >= 0 ? '+' : '-'
  const abs = Math.abs(minutes)
  const hh = String(Math.floor(abs / 60)).padStart(2, '0')
  const mm = String(abs % 60).padStart(2, '0')
  return `UTC${sign}${hh}:${mm}`
}

/** "Asia/Colombo (UTC+05:30)" */
export function timeZoneLabel(at = new Date()) {
  return `${browserTimeZone()} (${utcOffsetLabel(at)})`
}

/** ISO (UTC) -> value for <input type="datetime-local"> in local time: "2026-03-28T09:00". */
export function toLocalInputValue(value) {
  const date = toDate(value)
  if (!date) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** <input type="datetime-local"> value (local time) -> UTC ISO string, or null when empty/invalid. */
export function localInputToUtcIso(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
