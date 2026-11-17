/**
 * SAMPLE DATA for the dispute screens in mock mode (VITE_DISPUTE_FIXTURE_MODE=true, DEV only).
 * Not real users: ids and texts are obviously fake. Shapes match the backend DisputeDetailResponse.
 *
 * Open:  /customer/disputes/sample-awaiting   /provider/disputes/sample-awaiting
 *        /provider/disputes/sample-review     /provider/disputes/sample-overdue
 *        /customer/disputes/sample-denied (403)   any other id (404)
 */

const HOUR_MS = 60 * 60 * 1000
const MAX_TEXT_LENGTH = 2000

function iso(offsetMs) {
  return new Date(Date.now() + offsetMs).toISOString()
}

function baseDispute(id, createdOffsetMs) {
  const createdAt = iso(createdOffsetMs)
  return {
    id,
    bookingId: `sample-booking-${id}`,
    customerId: 'sample-customer-id',
    providerId: 'sample-provider-id',
    raisedBy: 'sample-customer-id',
    raisedByRole: 'CUSTOMER',
    category: 'POOR_QUALITY',
    description: 'Sample issue text for the demo. The service was marked complete, but the reported problem '
      + 'was still there afterwards. This is placeholder content, not a real case.',
    evidence: ['evidence/sample-photo-1.jpg', 'evidence/sample-photo-2.png', 'evidence/sample-receipt.pdf'],
    status: 'AWAITING_RESPONSE',
    response: null,
    responseDueAt: iso(createdOffsetMs + 72 * HOUR_MS),
    createdAt,
    updatedAt: createdAt,
    statusHistory: [{
      fromStatus: null,
      toStatus: 'AWAITING_RESPONSE',
      actorId: 'sample-customer-id',
      actorRole: 'CUSTOMER',
      reason: null,
      timestamp: createdAt,
    }],
  }
}

function buildFixtures() {
  // Raised ~20h ago: "2 days 4 hours left".
  const awaiting = baseDispute('sample-awaiting', -(19 * HOUR_MS + 50 * 60 * 1000))

  const review = baseDispute('sample-review', -30 * HOUR_MS)
  const respondedAt = iso(-6 * HOUR_MS)
  review.status = 'UNDER_REVIEW'
  review.response = {
    responderId: 'sample-provider-id',
    text: 'Sample response text for the demo. The provider explains the steps taken during the service.',
    evidence: [],
    respondedAt,
  }
  review.updatedAt = respondedAt
  review.statusHistory = [...review.statusHistory, {
    fromStatus: 'AWAITING_RESPONSE',
    toStatus: 'UNDER_REVIEW',
    actorId: 'sample-provider-id',
    actorRole: 'PROVIDER',
    reason: null,
    timestamp: respondedAt,
  }]

  // Raised 80h ago: the 72h window has passed.
  const overdue = baseDispute('sample-overdue', -80 * HOUR_MS)

  return { [awaiting.id]: awaiting, [review.id]: review, [overdue.id]: overdue }
}

let fixtures = buildFixtures()

/** Test helper: rebuild the sample data (also resets responses submitted in the session). */
export function resetMockDisputes() {
  fixtures = buildFixtures()
}

function apiError(status, error, fieldErrors = []) {
  const err = new Error(error)
  err.response = { status, data: { status, error, message: error, fieldErrors } }
  return err
}

const delay = (ms) => new Promise((resolve) => {
  setTimeout(resolve, ms)
})

export async function getMockDispute(disputeId) {
  await delay(300)
  if (disputeId === 'sample-denied') {
    throw apiError(403, 'NOT_DISPUTE_PARTY')
  }
  const dispute = fixtures[disputeId]
  if (!dispute) {
    throw apiError(404, 'DISPUTE_NOT_FOUND')
  }
  return structuredClone(dispute)
}

/** Applies the same rules as the backend's submitResponse. */
export async function submitMockResponse(disputeId, text) {
  await delay(500)
  const dispute = fixtures[disputeId]
  if (!dispute) {
    throw apiError(404, 'DISPUTE_NOT_FOUND')
  }
  const clean = typeof text === 'string' ? text.trim() : ''
  if (clean === '' || clean.length > MAX_TEXT_LENGTH) {
    throw apiError(400, 'VALIDATION_FAILED', [{
      field: 'text',
      message: clean === '' ? 'text is required' : 'text must be at most 2000 characters',
    }])
  }
  if (dispute.status !== 'AWAITING_RESPONSE') {
    throw apiError(409, 'INVALID_DISPUTE_STATUS')
  }
  if (Date.parse(dispute.responseDueAt) <= Date.now()) {
    throw apiError(400, 'RESPONSE_WINDOW_CLOSED')
  }
  const now = new Date().toISOString()
  dispute.response = { responderId: 'sample-provider-id', text: clean, evidence: [], respondedAt: now }
  dispute.status = 'UNDER_REVIEW'
  dispute.updatedAt = now
  dispute.statusHistory = [...dispute.statusHistory, {
    fromStatus: 'AWAITING_RESPONSE',
    toStatus: 'UNDER_REVIEW',
    actorId: 'sample-provider-id',
    actorRole: 'PROVIDER',
    reason: null,
    timestamp: now,
  }]
  return structuredClone(dispute)
}
