/**
 * Finds contact details in listing text (phone numbers, email addresses, links) so the review modal can highlight
 * them when a listing was hidden for off-platform contact. Returns plain text segments; the caller renders them as
 * React text (never as HTML), so the listing text can never inject markup.
 */
const CONTACT = new RegExp(
  [
    '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}', // email
    '(?:https?:\\/\\/|www\\.)[^\\s<>"\']+', // link
    '\\+?\\d[\\d\\s-]{7,}\\d', // phone: 9+ digits with optional spaces / dashes
  ].join('|'),
  'g',
)

/** @returns {{ text: string, contact: boolean }[]} */
export function splitContacts(text) {
  const value = typeof text === 'string' ? text : ''
  const segments = []
  let last = 0
  for (const match of value.matchAll(CONTACT)) {
    if (match.index > last) segments.push({ text: value.slice(last, match.index), contact: false })
    segments.push({ text: match[0], contact: true })
    last = match.index + match[0].length
  }
  if (last < value.length) segments.push({ text: value.slice(last), contact: false })
  return segments
}

/** True when the listing was hidden because of contact details (reason text or screening flags). */
export function isContactReason(reason, flags = []) {
  const text = `${reason ?? ''} ${(flags ?? []).join(' ')}`.toLowerCase()
  return /contact|phone|email|off[-_ ]?platform/.test(text)
}
