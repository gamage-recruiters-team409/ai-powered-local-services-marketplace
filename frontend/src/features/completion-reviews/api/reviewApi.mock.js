/**
 * WP08 – Mock Review API.
 * Same function names and response shape as reviewApi.js, with in-memory data and a short delay.
 * Applies the same main rules as the backend so the UI states can be checked without a server.
 */

import { EDIT_WINDOW_HOURS, REVIEW_STATUS } from '../utils/reviewUtils'

const DELAY_MS = 500
const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

const bookings = new Map() // bookingId -> booking summary
const reviews = new Map() // reviewId -> review
const providerReviews = new Map() // reviews of the signed-in provider (S11)
let nextId = 1

const wait = (value, ms = DELAY_MS) => new Promise((resolve) => setTimeout(() => resolve(value), ms))

function apiError(status, error, message) {
  const err = new Error(message)
  err.response = { status, data: { status, error, message } }
  return err
}

const copy = (review) => ({ ...review, providerReply: review.providerReply ? { ...review.providerReply } : null })

/** Adds a booking that the mock treats as completed (used by the preview data and tests). */
export function seedBooking(booking) {
  bookings.set(booking.id, { ...booking })
}

/** Adds an existing review (used by the preview data and tests). */
export function seedReview(review) {
  const id = review.id ?? `rv-${nextId++}`
  reviews.set(id, {
    id,
    listingId: null,
    providerId: 'provider-1',
    comment: null,
    status: REVIEW_STATUS.PUBLISHED,
    providerReply: null,
    edited: false,
    editedAt: null,
    ...review,
  })
  return id
}

export function resetMockReviews() {
  bookings.clear()
  reviews.clear()
  providerReviews.clear()
  nextId = 1
}

/** Adds a review of the signed-in provider (used by the preview data and tests). */
export function seedProviderReview(review) {
  const id = review.id ?? `prv-${nextId++}`
  providerReviews.set(id, {
    id,
    bookingId: `bk-${id}`,
    listingId: null,
    providerId: 'provider-1',
    comment: null,
    status: REVIEW_STATUS.PUBLISHED,
    providerReply: null,
    edited: false,
    editedAt: null,
    ...review,
  })
  return id
}

/** Loads a realistic set of bookings and reviews for the dev preview. */
export function seedDemoReviews(now = new Date()) {
  resetMockReviews()
  const ago = (ms) => new Date(now.getTime() - ms).toISOString()
  seedBooking({ id: 'bk-2041', ref: 'BK-2041', title: 'Home cleaning – 3 rooms', providerName: 'Bright Home Cleaning', completedAt: ago(1 * DAY_MS) })
  seedBooking({ id: 'bk-2042', ref: 'BK-2042', title: 'Plumbing – leak repair', providerName: 'Kamal Fixers', completedAt: ago(2 * DAY_MS) })
  seedBooking({ id: 'bk-2043', ref: 'BK-2043', title: 'Garden care', providerName: 'Green Hands', completedAt: ago(3 * DAY_MS) })
  seedBooking({ id: 'bk-2030', ref: 'BK-2030', title: 'Sofa deep cleaning', providerName: 'Fresh Fabrics', completedAt: ago(4 * DAY_MS) })
  seedBooking({ id: 'bk-2031', ref: 'BK-2031', title: 'Electrical – socket repair', providerName: 'Spark Electric', completedAt: ago(6 * DAY_MS) })
  seedBooking({ id: 'bk-2032', ref: 'BK-2032', title: 'AC repair', providerName: 'CoolFix', completedAt: ago(9 * DAY_MS) })

  seedReview({ id: 'rv-a', bookingId: 'bk-2030', rating: 4, comment: 'Arrived on time and did a careful job. Would book again.', createdAt: ago(5 * HOUR_MS) })
  seedReview({
    id: 'rv-b',
    bookingId: 'bk-2031',
    rating: 5,
    comment: 'Fixed the socket quickly and explained the problem.',
    createdAt: ago(4 * DAY_MS),
    providerReply: { text: 'Thank you! Glad we could help.', repliedAt: ago(3 * DAY_MS) },
  })
  seedReview({ id: 'rv-c', bookingId: 'bk-2032', rating: 1, comment: null, status: REVIEW_STATUS.REMOVED, createdAt: ago(8 * DAY_MS) })

  // Reviews the signed-in provider received (S11)
  seedProviderReview({ id: 'prv-1', customerName: 'Amaya Rathnayake', serviceTitle: 'Home cleaning', rating: 5, comment: 'Arrived on time and did a careful job. Would book again.', edited: true, createdAt: ago(6 * HOUR_MS) })
  seedProviderReview({
    id: 'prv-2', customerName: 'Ravi Silva', serviceTitle: 'Home cleaning', rating: 4,
    comment: 'Good work – a little late, but kept me informed the whole time.', createdAt: ago(2 * DAY_MS),
    providerReply: { text: 'Thank you Ravi, sorry for the delay!', repliedAt: ago(1 * DAY_MS) },
  })
  seedProviderReview({ id: 'prv-3', customerName: 'Nuwan Perera', serviceTitle: 'Deep cleaning', rating: 2, comment: 'Did not finish the balcony.', createdAt: ago(3 * DAY_MS) })
  seedProviderReview({ id: 'prv-4', customerName: 'Dilani Fernando', serviceTitle: 'Home cleaning', rating: 5, comment: 'Excellent and friendly service.', createdAt: ago(9 * DAY_MS) })
  seedProviderReview({ id: 'prv-5', customerName: 'Kasun Jayasuriya', serviceTitle: 'Deep cleaning', rating: 3, comment: null, status: REVIEW_STATUS.REMOVED, createdAt: ago(12 * DAY_MS) })
}

export async function getMyReviews() {
  const list = [...reviews.values()]
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    .map(copy)
  return wait(list)
}

export const supportsToReview = true

/** Completed bookings within the 7-day window that have no review yet. */
export async function getBookingsToReview(now = new Date()) {
  const reviewed = new Set([...reviews.values()].map((r) => r.bookingId))
  const list = [...bookings.values()]
    .filter((b) => !reviewed.has(b.id))
    .filter((b) => now.getTime() - new Date(b.completedAt).getTime() <= 7 * DAY_MS)
    .sort((a, b) => new Date(b.completedAt) - new Date(a.completedAt))
    .map((b) => ({ ...b }))
  return wait(list)
}

export async function getBookingSummary(bookingId) {
  const booking = bookings.get(bookingId)
  return wait(booking ? { ...booking } : null, 0)
}

export async function createReview({ bookingId, rating, comment }) {
  if (!bookings.has(bookingId)) {
    throw apiError(404, 'BOOKING_NOT_FOUND', 'Booking not found')
  }
  if ([...reviews.values()].some((r) => r.bookingId === bookingId)) {
    throw apiError(409, 'REVIEW_ALREADY_EXISTS', 'You have already reviewed this booking')
  }
  const id = seedReview({ bookingId, rating, comment, createdAt: new Date().toISOString() })
  return wait(copy(reviews.get(id)))
}

export async function updateReview(reviewId, { rating, comment }) {
  const review = reviews.get(reviewId)
  if (!review) {
    throw apiError(404, 'REVIEW_NOT_FOUND', 'Review not found')
  }
  if (review.status === REVIEW_STATUS.REMOVED) {
    throw apiError(409, 'REVIEW_NOT_EDITABLE', 'This review has been removed and cannot be edited')
  }
  if (review.providerReply) {
    throw apiError(409, 'REVIEW_NOT_EDITABLE', 'This review cannot be edited because the provider has already replied')
  }
  if (Date.now() - new Date(review.createdAt).getTime() > EDIT_WINDOW_HOURS * HOUR_MS) {
    throw apiError(409, 'REVIEW_EDIT_WINDOW_CLOSED', 'Reviews can be edited within 48 hours of posting')
  }
  const now = new Date().toISOString()
  Object.assign(review, { rating, comment, edited: true, editedAt: now })
  return wait(copy(review))
}

// ---------------------------------------------------------------- provider (S11–S12)

export async function getProviderReviews() {
  const list = [...providerReviews.values()]
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    .map(copy)
  return wait(list)
}

export async function getRatingSummary(providerId) {
  const published = [...providerReviews.values()].filter((r) => r.status === REVIEW_STATUS.PUBLISHED)
  const ratingCounts = { 5: 0, 4: 0, 3: 0, 2: 0, 1: 0 }
  published.forEach((r) => {
    ratingCounts[r.rating] += 1
  })
  const sum = published.reduce((total, r) => total + r.rating, 0)
  const averageRating = published.length ? Math.round((sum / published.length) * 10) / 10 : 0
  return wait({ providerId, averageRating, reviewCount: published.length, ratingCounts }, 200)
}

export async function replyToReview(reviewId, { text }) {
  const review = providerReviews.get(reviewId)
  if (!review) {
    throw apiError(404, 'REVIEW_NOT_FOUND', 'Review not found')
  }
  if (review.status !== REVIEW_STATUS.PUBLISHED) {
    throw apiError(409, 'REVIEW_NOT_PUBLISHED', 'You can only reply to a published review')
  }
  review.providerReply = { text: text.trim(), repliedAt: new Date().toISOString() }
  return wait(copy(review))
}
