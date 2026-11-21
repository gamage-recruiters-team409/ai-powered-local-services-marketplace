/**
 * SAMPLE DATA for the dispute screens in mock mode (VITE_DISPUTE_FIXTURE_MODE=true, DEV only).
 * Not real users: ids and texts are obviously fake. Shapes match the backend DisputeDetailResponse.
 *
 * Open:  /customer/disputes/sample-awaiting   /provider/disputes/sample-awaiting
 *        /provider/disputes/sample-review     /provider/disputes/sample-overdue
 *        /customer/disputes/sample-provider-raised (the customer must respond: shows the customer form)
 *        /customer/disputes/sample-denied (403)   any other id (404)
 * Each sample also has a realistic hex id (like a MongoDB id), so references read "D-001042", "BK-048192".
 */

const HOUR_MS = 60 * 60 * 1000
const MAX_TEXT_LENGTH = 2000

function iso(offsetMs) {
  return new Date(Date.now() + offsetMs).toISOString()
}

function baseDispute(id, bookingId, createdOffsetMs) {
  const createdAt = iso(createdOffsetMs)
  return {
    id,
    bookingId,
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
  const awaiting = baseDispute('6a0f00c0ffee00a000001042', '6a0f00c0ffee00b000048192', -(19 * HOUR_MS + 50 * 60 * 1000))

  const review = baseDispute('6a0f00c0ffee00a000001043', '6a0f00c0ffee00b000048193', -30 * HOUR_MS)
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
  const overdue = baseDispute('6a0f00c0ffee00a000001044', '6a0f00c0ffee00b000048194', -80 * HOUR_MS)

  // Raised by the provider ~10h ago: the customer is the responding party.
  const providerRaised = baseDispute('6a0f00c0ffee00a000001045', '6a0f00c0ffee00b000048195', -10 * HOUR_MS)
  providerRaised.raisedBy = 'sample-provider-id'
  providerRaised.raisedByRole = 'PROVIDER'
  providerRaised.category = 'CONDUCT'
  providerRaised.description = 'Sample provider report for the demo. Access to the property was not available at the '
    + 'booked time. This is placeholder content, not a real case.'
  providerRaised.evidence = ['evidence/sample-arrival-photo.jpg']
  providerRaised.statusHistory = [{ ...providerRaised.statusHistory[0], actorId: 'sample-provider-id', actorRole: 'PROVIDER' }]

  // Keyed by the short URL slug; findFixture also accepts the hex id (used by the response form).
  return {
    'sample-awaiting': awaiting,
    'sample-review': review,
    'sample-overdue': overdue,
    'sample-provider-raised': providerRaised,
  }
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

function findFixture(disputeId) {
  return fixtures[disputeId] ?? Object.values(fixtures).find((dispute) => dispute.id === disputeId)
}

const delay = (ms) => new Promise((resolve) => {
  setTimeout(resolve, ms)
})

export async function getMockDispute(disputeId) {
  await delay(300)
  if (disputeId === 'sample-denied') {
    throw apiError(403, 'NOT_DISPUTE_PARTY')
  }
  const dispute = findFixture(disputeId)
  if (!dispute) {
    throw apiError(404, 'DISPUTE_NOT_FOUND')
  }
  return structuredClone(dispute)
}

/** Applies the same rules as the backend's submitResponse. */
export async function submitMockResponse(disputeId, text) {
  await delay(500)
  const dispute = findFixture(disputeId)
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
  // The responding party is the one who did not raise the dispute.
  const responderRole = dispute.raisedByRole === 'PROVIDER' ? 'CUSTOMER' : 'PROVIDER'
  const responderId = responderRole === 'CUSTOMER' ? dispute.customerId : dispute.providerId
  dispute.response = { responderId, text: clean, evidence: [], respondedAt: now }
  dispute.status = 'UNDER_REVIEW'
  dispute.updatedAt = now
  dispute.statusHistory = [...dispute.statusHistory, {
    fromStatus: 'AWAITING_RESPONSE',
    toStatus: 'UNDER_REVIEW',
    actorId: responderId,
    actorRole: responderRole,
    reason: null,
    timestamp: now,
  }]
  return structuredClone(dispute)
}
