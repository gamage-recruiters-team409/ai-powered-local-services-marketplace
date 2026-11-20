import { describe, expect, it } from 'vitest'

import {
  canEditReview,
  editBlockedReason,
  editableUntil,
  reviewStatusBadge,
  toReviewPayload,
  validateReview,
} from '../utils/reviewUtils'

const NOW = new Date('2026-11-18T10:00:00Z')
const hoursAgo = (h) => new Date(NOW.getTime() - h * 3600 * 1000).toISOString()

describe('reviewUtils', () => {
  it('editable until is 48 hours after posting', () => {
    expect(editableUntil({ createdAt: '2026-11-18T10:00:00Z' }).toISOString()).toBe('2026-11-20T10:00:00.000Z')
    expect(editableUntil({ createdAt: null })).toBeNull()
  })

  it('allows edit within 48 hours (boundary included) and blocks after', () => {
    expect(canEditReview({ status: 'PUBLISHED', createdAt: hoursAgo(47) }, NOW)).toBe(true)
    expect(canEditReview({ status: 'PUBLISHED', createdAt: hoursAgo(48) }, NOW)).toBe(true)
    expect(canEditReview({ status: 'PUBLISHED', createdAt: hoursAgo(49) }, NOW)).toBe(false)
  })

  it('blocks edit when removed, replied to, or without a posting time (same as the backend)', () => {
    expect(canEditReview({ status: 'REMOVED', createdAt: hoursAgo(1) }, NOW)).toBe(false)
    expect(canEditReview({ status: 'PUBLISHED', createdAt: hoursAgo(1), providerReply: { text: 'Thanks' } }, NOW)).toBe(false)
    expect(canEditReview({ status: 'PUBLISHED', createdAt: null }, NOW)).toBe(false)
  })

  it('explains why a review cannot be edited', () => {
    expect(editBlockedReason({ status: 'PUBLISHED', createdAt: hoursAgo(1), providerReply: { text: 'x' } }, NOW))
      .toMatch(/provider has replied/)
    expect(editBlockedReason({ status: 'PUBLISHED', createdAt: hoursAgo(60) }, NOW)).toMatch(/48 hours/)
    expect(editBlockedReason({ status: 'PUBLISHED', createdAt: hoursAgo(1) }, NOW)).toBeNull()
  })

  it('validates rating 1–5 and comment length', () => {
    expect(validateReview({ rating: 0, comment: '' }).rating).toBeTruthy()
    expect(validateReview({ rating: 5, comment: 'x'.repeat(1001) }).comment).toBeTruthy()
    expect(validateReview({ rating: 3, comment: 'Good job' })).toEqual({})
  })

  it('trims the comment and sends an empty one as null', () => {
    expect(toReviewPayload({ rating: 4, comment: '  Nice  ' })).toEqual({ rating: 4, comment: 'Nice' })
    expect(toReviewPayload({ rating: 4, comment: '   ' })).toEqual({ rating: 4, comment: null })
  })

  it('maps statuses to badges', () => {
    expect(reviewStatusBadge('PUBLISHED')).toEqual({ label: 'Published', variant: 'success' })
    expect(reviewStatusBadge('AWAITING_MODERATION').variant).toBe('warning')
    expect(reviewStatusBadge('REMOVED').label).toBe('Removed')
  })
})
