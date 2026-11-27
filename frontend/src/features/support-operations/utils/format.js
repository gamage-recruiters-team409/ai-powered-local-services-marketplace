const numberFormat = new Intl.NumberFormat('en-US')

export function formatNumber(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  return numberFormat.format(Number(value))
}

export function formatDate(iso) {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

export function formatDateTime(iso) {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

/** "4h 15m late" / "45m late" from a whole number of minutes. */
export function formatDelay(minutesLate) {
  const mins = Math.max(0, Math.round(Number(minutesLate) || 0))
  const h = Math.floor(mins / 60)
  const m = mins % 60
  const parts = []
  if (h > 0) parts.push(`${h}h`)
  parts.push(`${m}m`)
  return `${parts.join(' ')} late`
}

export function formatDays(days) {
  const n = Math.max(0, Math.round(Number(days) || 0))
  return `${n} ${n === 1 ? 'day' : 'days'}`
}

/** A short, readable id for display (ops rows carry ids, not names). */
export function shortId(id) {
  if (!id) return '—'
  return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id
}
