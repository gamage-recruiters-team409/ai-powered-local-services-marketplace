/** Dates come from the API in UTC (ISO-8601) and are shown in the admin's own time zone. */

const DATE = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
const DATE_TIME = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})
const SHORT_DAY = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })
const MONTH = new Intl.DateTimeFormat('en-US', { month: 'short' })
const MONTH_UTC = new Intl.DateTimeFormat('en-US', { month: 'short', timeZone: 'UTC' })
const DAY_UTC = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
const LKR = new Intl.NumberFormat('en-LK', { style: 'currency', currency: 'LKR', maximumFractionDigits: 0 })
const NUMBER = new Intl.NumberFormat('en-US')

function toDate(value) {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDate(value) {
  const date = toDate(value)
  return date ? DATE.format(date) : ''
}

export function formatDateTime(value) {
  const date = toDate(value)
  return date ? DATE_TIME.format(date) : ''
}

export function formatShortDay(value) {
  const date = toDate(value)
  return date ? SHORT_DAY.format(date) : ''
}

export function formatMonth(value) {
  const date = toDate(value)
  return date ? MONTH.format(date) : ''
}

/** Chart buckets are UTC boundaries, so their labels are UTC too (a week never shows as the day before). */
export function bucketLabel(value, period) {
  const date = toDate(value)
  if (!date) return ''
  return period === 'MONTHS' ? MONTH_UTC.format(date) : DAY_UTC.format(date)
}

export function formatLkr(amount) {
  if (amount === null || amount === undefined || amount === '') return ''
  const number = Number(amount)
  return Number.isFinite(number) ? LKR.format(number) : ''
}

export function formatNumber(value) {
  return Number.isFinite(Number(value)) ? NUMBER.format(Number(value)) : '—'
}

/** Whole-number percentage of part / total, or null when there is nothing to compute from (never a made-up 0%). */
export function percent(part, total) {
  if (!Number.isFinite(part) || !Number.isFinite(total) || total <= 0) return null
  return Math.round((part / total) * 100)
}

export function initials(name) {
  const parts = String(name ?? '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}

export function plural(count, word, many = `${word}s`) {
  return `${formatNumber(count)} ${count === 1 ? word : many}`
}
