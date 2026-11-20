/**
 * In-memory moderation API for local work (VITE_USE_COMPLETION_MOCK=true). Same rules as the backend:
 * queue = held reviews + published reviews with open reports; decisions need the current revision.
 */
import { REMOVAL_REASONS } from '../utils/moderationUtils'

let reviews = []
let reports = []

const wait = (value) => new Promise((resolve) => setTimeout(() => resolve(structuredClone(value)), 250))

function apiError(status, error, message) {
  const err = new Error(message)
  err.response = { status, data: { status, error, message } }
  return err
}

const hoursAgo = (h) => new Date(Date.now() - h * 3600_000).toISOString()

export function seedDemoModeration() {
  reviews = [
    {
      id: 'mrv-1', bookingId: 'bk-1042', listingId: 'ls-7', providerId: 'p-1', providerName: "Maya's Home Care",
      customerId: 'c-1', customerName: 'Amaya Rathnayake', rating: 5,
      comment: 'Great cleaning! Call me on 077 123 4567 for a direct deal next time.', status: 'AWAITING_MODERATION',
      moderationFlags: ['CONTACT_DETAILS'], openReportCount: 0, edited: false, providerReply: null,
      removalReason: null, createdAt: hoursAgo(5), moderationRevision: 0,
    },
    {
      id: 'mrv-2', bookingId: 'bk-1050', listingId: 'ls-3', providerId: 'p-2', providerName: 'Ruwan Electricals',
      customerId: 'c-2', customerName: 'Kasun Perera', rating: 4,
      comment: 'Good work. More photos at www.myfixes.lk', status: 'AWAITING_MODERATION',
      moderationFlags: ['LINK'], openReportCount: 0, edited: true, providerReply: null,
      removalReason: null, createdAt: hoursAgo(20), moderationRevision: 1,
    },
    {
      id: 'mrv-3', bookingId: 'bk-0991', listingId: 'ls-9', providerId: 'p-1', providerName: "Maya's Home Care",
      customerId: 'c-3', customerName: 'Nimali Fernando', rating: 1,
      comment: 'Worst service ever, they are all cheats.', status: 'PUBLISHED',
      moderationFlags: [], openReportCount: 2, edited: false, providerReply: null,
      removalReason: null, createdAt: hoursAgo(50), moderationRevision: 2,
    },
  ]
  reports = [
    { id: 'rp-1', reviewId: 'mrv-3', reporterRole: 'PROVIDER', reason: 'OFFENSIVE', details: 'Calls us cheats.', status: 'OPEN', createdAt: hoursAgo(30) },
    { id: 'rp-2', reviewId: 'mrv-3', reporterRole: 'CUSTOMER', reason: 'FAKE', details: null, status: 'OPEN', createdAt: hoursAgo(26) },
  ]
}

const inQueue = (r, filter) => {
  const flagged = r.status === 'AWAITING_MODERATION'
  const reported = r.status === 'PUBLISHED' && r.openReportCount > 0
  if (filter === 'FLAGGED') return flagged
  if (filter === 'REPORTED') return reported
  return flagged || reported
}

export async function getQueue({ filter = 'ALL', page = 0, size = 10 } = {}) {
  const all = reviews.filter((r) => inQueue(r, filter)).sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  const content = all.slice(page * size, page * size + size)
  return wait({ content, page, size, totalElements: all.length, totalPages: Math.ceil(all.length / size) })
}

export async function getReview(reviewId) {
  const review = reviews.find((r) => r.id === reviewId)
  if (!review) throw apiError(404, 'REVIEW_NOT_FOUND', 'Review not found')
  return wait({ review, openReports: reports.filter((p) => p.reviewId === reviewId && p.status === 'OPEN'), history: review.history ?? [] })
}

function decide(reviewId, revision, apply) {
  const review = reviews.find((r) => r.id === reviewId)
  if (!review) throw apiError(404, 'REVIEW_NOT_FOUND', 'Review not found')
  if (review.moderationRevision !== revision) {
    throw apiError(409, 'REVIEW_UPDATE_CONFLICT', 'This review was changed by another request at the same time. Please reload it and try again')
  }
  apply(review)
  review.moderationRevision += 1
  review.openReportCount = 0
  reports.filter((p) => p.reviewId === reviewId).forEach((p) => { p.status = 'RESOLVED' })
  return wait(review)
}

export async function publishReview(reviewId, { note, revision }) {
  return decide(reviewId, revision, (review) => {
    review.history = [...(review.history ?? []), { action: review.status === 'PUBLISHED' ? 'REPORTS_DISMISSED' : 'PUBLISHED', fromStatus: review.status, toStatus: 'PUBLISHED', note: note || null, at: new Date().toISOString() }]
    review.status = 'PUBLISHED'
  })
}

export async function removeReview(reviewId, { reasonCode, note, revision }) {
  return decide(reviewId, revision, (review) => {
    review.history = [...(review.history ?? []), { action: 'REMOVED', fromStatus: review.status, toStatus: 'REMOVED', reasonCode, note: note || null, at: new Date().toISOString() }]
    review.status = 'REMOVED'
    review.removalReason = REMOVAL_REASONS.find((r) => r.value === reasonCode)?.label ?? reasonCode
  })
}
