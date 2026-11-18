/**
 * Display helpers. All times come from the backend in UTC and are shown in the browser's local time.
 */

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

export function formatDateTime(isoString) {
  const time = typeof isoString === 'string' ? Date.parse(isoString) : Number.NaN
  return Number.isNaN(time) ? '' : DATE_TIME.format(new Date(time))
}

/**
 * Short, human-friendly reference for an id, e.g. "D-3F9A21". The backend has no sequential dispute number yet,
 * so this is derived from the end of the id. Display only: never use it to look anything up.
 */
export function shortReference(prefix, id) {
  if (typeof id !== 'string' || id.length === 0) {
    return `${prefix}-?`
  }
  const tail = id.replace(/[^A-Za-z0-9]/g, '').slice(-6).toUpperCase()
  return `${prefix}-${tail || '?'}`
}

/** A file-like name for an evidence reference (last path segment), shown as plain text. */
export function evidenceName(reference) {
  if (typeof reference !== 'string') {
    return ''
  }
  const withoutQuery = reference.split(/[?#]/)[0]
  const segments = withoutQuery.split('/').filter(Boolean)
  return segments.length ? segments[segments.length - 1] : reference
}

/** Page title for a dispute, e.g. "Dispute D-3F9A21". */
export function disputeTitle(dispute) {
  return `Dispute ${shortReference('D', dispute?.id)}`
}
