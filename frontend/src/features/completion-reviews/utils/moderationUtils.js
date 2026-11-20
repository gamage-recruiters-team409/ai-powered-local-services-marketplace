/** WP08 review moderation (S14–S16): labels, rules and text highlighting. Mirrors the backend rules. */

export const QUEUE_FILTERS = Object.freeze([
  { value: 'ALL', label: 'All' },
  { value: 'FLAGGED', label: 'Flagged' },
  { value: 'REPORTED', label: 'Reported' },
])

/** Backend RemovalReason codes; the label is what the customer sees. */
export const REMOVAL_REASONS = Object.freeze([
  { value: 'CONTACT_DETAILS', label: 'Contains contact details' },
  { value: 'OFFENSIVE', label: 'Offensive or abusive language' },
  { value: 'SPAM', label: 'Spam or advertising' },
  { value: 'FAKE', label: 'Not a genuine review of this booking' },
  { value: 'NOT_RELATED', label: 'Not about this service' },
  { value: 'OTHER', label: 'Breaks the review guidelines' },
])

const FLAG_LABELS = {
  CONTACT_DETAILS: 'Contact details',
  LINK: 'Link',
  BLOCKED_WORDS: 'Blocked words',
}

const FLAG_MESSAGES = {
  CONTACT_DETAILS: 'The text contains a phone number or e-mail address.',
  LINK: 'The text contains a web link or domain name.',
  BLOCKED_WORDS: 'The text contains a blocked word or phrase.',
}

const REPORT_REASONS = {
  SPAM: 'Spam',
  OFFENSIVE: 'Offensive',
  CONTACT_DETAILS: 'Contact details',
  FAKE: 'Fake review',
  OTHER: 'Other',
}

const ACTION_LABELS = {
  AUTO_FLAGGED: 'Held by the automatic checks',
  PUBLISHED: 'Published by a moderator',
  REPORTS_DISMISSED: 'Reports dismissed, review kept',
  REMOVED: 'Removed by a moderator',
}

export const flagLabel = (flag) => FLAG_LABELS[flag] ?? flag
export const flagMessage = (flag) => FLAG_MESSAGES[flag] ?? 'Held for a manual check.'
export const reportReasonLabel = (reason) => REPORT_REASONS[reason] ?? reason
export const actionLabel = (action) => ACTION_LABELS[action] ?? action
export const removalReasonLabel = (code) => REMOVAL_REASONS.find((r) => r.value === code)?.label ?? code

/** Why the review is in the queue: 'FLAGGED' (held by the checks) or 'REPORTED' (published, open reports). */
export function queueSource(review) {
  if (review?.status === 'AWAITING_MODERATION') return 'FLAGGED'
  if (review?.status === 'PUBLISHED' && review.openReportCount > 0) return 'REPORTED'
  return null
}

/**
 * Which decisions the backend accepts for this review:
 * held → publish or remove · published with open reports → keep (dismiss reports) or remove ·
 * published without reports → remove · removed → none.
 */
export function availableActions(review) {
  switch (review?.status) {
    case 'AWAITING_MODERATION':
      return { publish: 'PUBLISH', remove: true }
    case 'PUBLISHED':
      return { publish: review.openReportCount > 0 ? 'KEEP' : null, remove: true }
    default:
      return { publish: null, remove: false }
  }
}

/** Clear message for an API error (409 conflicts and 403 get a specific text). */
export function moderationErrorMessage(error) {
  const status = error?.response?.status ?? error?.status
  const code = error?.response?.data?.error ?? error?.code
  if (status === 409 && code === 'REVIEW_UPDATE_CONFLICT') {
    return 'This review was changed by another moderator. It has been reloaded – please check it again.'
  }
  if (status === 403) {
    return 'You can view this review, but only a moderator can make a decision.'
  }
  return error?.response?.data?.message ?? 'Something went wrong. Please try again.'
}

export const isConflict = (error) => (error?.response?.status ?? error?.status) === 409

// Same patterns as the backend ReviewContentChecker (default blocked words only).
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g
const PHONE = /\+?\d[\d\s().-]{7,}\d/g
const LINK = /(https?:\/\/\S+|www\.\S+|\b[a-z0-9-]+\.(com|lk|net|org|io|info|biz)\b)/gi
const BLOCKED = /(?<![\p{L}\p{N}])(whatsapp|telegram|viber|dm me)(?![\p{L}\p{N}])/giu

/**
 * Splits the review text into [{ text, hit }] so the parts the checks object to can be highlighted.
 * Phone candidates count only with 9 or more digits, as in the backend.
 */
export function highlightSegments(text = '') {
  const ranges = []
  const collect = (pattern, accept = () => true) => {
    for (const match of text.matchAll(pattern)) {
      if (accept(match[0])) ranges.push([match.index, match.index + match[0].length])
    }
  }
  collect(EMAIL)
  collect(PHONE, (value) => value.replace(/\D/g, '').length >= 9)
  collect(LINK)
  collect(BLOCKED)
  ranges.sort((a, b) => a[0] - b[0])

  const segments = []
  let cursor = 0
  for (const [start, end] of ranges) {
    if (end <= cursor) continue
    const from = Math.max(start, cursor)
    if (from > cursor) segments.push({ text: text.slice(cursor, from), hit: false })
    segments.push({ text: text.slice(from, end), hit: true })
    cursor = end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), hit: false })
  return segments
}

/** Short preview of a review text for the queue table. */
export function excerpt(text = '', max = 90) {
  const clean = text.replace(/\s+/g, ' ').trim()
  return clean.length > max ? `${clean.slice(0, max - 1)}…` : clean
}
