/**
 * WP08 – Review rules used by the customer review pages (S07–S09).
 * The backend checks every rule again; these helpers only decide what the UI shows.
 */

export const RATING_MIN = 1
export const RATING_MAX = 5
export const COMMENT_MAX_LENGTH = 1000
/** Reviews can be written within 7 days of completion (backend REVIEW_WINDOW). */
export const REVIEW_WINDOW_DAYS = 7
/** Reviews can be edited within 48 hours of posting (backend EDIT_WINDOW, agreed with the TL). */
export const EDIT_WINDOW_HOURS = 48

export const REVIEW_STATUS = {
  PUBLISHED: 'PUBLISHED',
  AWAITING_MODERATION: 'AWAITING_MODERATION',
  REMOVED: 'REMOVED',
}

const HOUR_MS = 60 * 60 * 1000

export const RATING_LABELS = {
  1: 'Poor',
  2: 'Fair',
  3: 'Good',
  4: 'Very good',
  5: 'Excellent',
}

/** Badge text and variant for a review status. */
export function reviewStatusBadge(status) {
  switch (status) {
    case REVIEW_STATUS.PUBLISHED:
      return { label: 'Published', variant: 'success' }
    case REVIEW_STATUS.AWAITING_MODERATION:
      return { label: 'Awaiting moderation', variant: 'warning' }
    case REVIEW_STATUS.REMOVED:
      return { label: 'Removed', variant: 'neutral' }
    default:
      return { label: status ?? 'Unknown', variant: 'neutral' }
  }
}

/** Last moment the customer can edit the review, or null when it cannot be worked out. */
export function editableUntil(review) {
  if (!review?.createdAt) {
    return null
  }
  const created = new Date(review.createdAt).getTime()
  return Number.isNaN(created) ? null : new Date(created + EDIT_WINDOW_HOURS * HOUR_MS)
}

/**
 * Whether the Edit button is shown. Same rules as the backend:
 * not removed, no provider reply yet, and within 48 hours of posting.
 */
export function canEditReview(review, now = new Date()) {
  if (!review || review.status === REVIEW_STATUS.REMOVED || review.providerReply) {
    return false
  }
  const until = editableUntil(review)
  return Boolean(until) && now.getTime() <= until.getTime()
}

/** Why the review can no longer be edited (shown under the card), or null when it can be. */
export function editBlockedReason(review, now = new Date()) {
  if (!review || canEditReview(review, now)) {
    return null
  }
  if (review.status === REVIEW_STATUS.REMOVED) {
    return null
  }
  if (review.providerReply) {
    return 'The provider has replied, so this review can no longer be edited.'
  }
  return `Reviews can be edited within ${EDIT_WINDOW_HOURS} hours of posting.`
}

/** Client-side checks before calling the API. Returns { rating?, comment? } error messages. */
export function validateReview({ rating, comment }) {
  const errors = {}
  if (!Number.isInteger(rating) || rating < RATING_MIN || rating > RATING_MAX) {
    errors.rating = 'Please choose a rating from 1 to 5 stars.'
  }
  if ((comment ?? '').length > COMMENT_MAX_LENGTH) {
    errors.comment = `Your review must not exceed ${COMMENT_MAX_LENGTH} characters.`
  }
  return errors
}

/** Request body for create/update: comment trimmed, empty comment sent as null. */
export function toReviewPayload({ rating, comment }) {
  const trimmed = (comment ?? '').trim()
  return { rating, comment: trimmed.length > 0 ? trimmed : null }
}

/** Counts for the S09 tabs. */
export function tabCounts(toReview, reviews) {
  return { toReview: toReview?.length ?? 0, reviews: reviews?.length ?? 0 }
}

// ---------------------------------------------------------------- provider (S11–S12)

export const REPLY_MAX_LENGTH = 1000

export const RATING_FILTERS = [
  { value: 'all', label: 'All ratings' },
  { value: '5', label: '5 stars' },
  { value: '4', label: '4 stars' },
  { value: '3', label: '3 stars' },
  { value: '2', label: '2 stars' },
  { value: '1', label: '1 star' },
]

export const SORT_OPTIONS = [
  { value: 'newest', label: 'Newest first' },
  { value: 'oldest', label: 'Oldest first' },
  { value: 'highest', label: 'Highest rating' },
  { value: 'lowest', label: 'Lowest rating' },
]

/** A published review without a provider reply: the provider can still reply to it. */
export function needsReply(review) {
  return review?.status === REVIEW_STATUS.PUBLISHED && !review.providerReply
}

export function notRepliedCount(reviews) {
  return (reviews ?? []).filter(needsReply).length
}

/** S11 filters: rating ("all" or "1"–"5"), sort and "Not replied" only. */
export function filterProviderReviews(reviews, { rating = 'all', sort = 'newest', notReplied = false } = {}) {
  const time = (r) => new Date(r.createdAt).getTime() || 0
  const sorters = {
    newest: (a, b) => time(b) - time(a),
    oldest: (a, b) => time(a) - time(b),
    highest: (a, b) => b.rating - a.rating || time(b) - time(a),
    lowest: (a, b) => a.rating - b.rating || time(b) - time(a),
  }
  return (reviews ?? [])
    .filter((r) => rating === 'all' || r.rating === Number(rating))
    .filter((r) => !notReplied || needsReply(r))
    .sort(sorters[sort] ?? sorters.newest)
}

/** Rows for the distribution bars: [{ stars, count, percent }] from 5 down to 1. */
export function ratingDistribution(summary) {
  const counts = summary?.ratingCounts ?? {}
  const total = summary?.reviewCount ?? 0
  return [5, 4, 3, 2, 1].map((stars) => {
    const count = Number(counts[stars] ?? counts[String(stars)] ?? 0)
    return { stars, count, percent: total > 0 ? Math.round((count / total) * 100) : 0 }
  })
}

/** "Amaya Rathnayake" -> "AR"; no name -> "C" (customer). */
export function initials(name) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) {
    return 'C'
  }
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}
