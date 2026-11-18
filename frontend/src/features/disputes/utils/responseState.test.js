import { describe, expect, it } from 'vitest'

import { responseClosedReason } from './responseState'

const NOW = Date.parse('2026-11-17T10:00:00Z')
const HOUR = 60 * 60 * 1000

function dispute(overrides = {}) {
  return {
    status: 'AWAITING_RESPONSE',
    response: null,
    responseDueAt: new Date(NOW + HOUR).toISOString(),
    ...overrides,
  }
}

describe('responseClosedReason', () => {
  it('is null while awaiting a response before the deadline', () => {
    expect(responseClosedReason(dispute(), NOW)).toBeNull()
  })

  it('explains why the form is closed', () => {
    expect(responseClosedReason(dispute({ response: { text: 'x' } }), NOW)).toMatch(/already responded/)
    expect(responseClosedReason(dispute({ status: 'UNDER_REVIEW' }), NOW)).toMatch(/no longer waiting/)
    expect(responseClosedReason(dispute({ responseDueAt: new Date(NOW - 1).toISOString() }), NOW))
      .toMatch(/deadline has passed/)
    expect(responseClosedReason(dispute({ responseDueAt: null }), NOW)).toMatch(/deadline has passed/)
  })
})
