import { describe, expect, it } from 'vitest'

import { availableActions, excerpt, highlightSegments, moderationErrorMessage, queueSource } from '../utils/moderationUtils'

const hits = (text) => highlightSegments(text).filter((s) => s.hit).map((s) => s.text)

describe('moderationUtils', () => {
  it('highlights contact details, links and blocked words like the backend checks', () => {
    expect(hits('Call 077 123 4567 or mail me@x.lk')).toEqual(['077 123 4567', 'me@x.lk'])
    expect(hits('See www.myfixes.lk and example.com')).toEqual(['www.myfixes.lk', 'example.com'])
    expect(hits('Message me on WhatsApp, or DM me')).toEqual(['WhatsApp', 'DM me'])
    // fewer than 9 digits is not a phone number; the whole text is kept in order
    expect(hits('Booked 2 rooms on 12.11.2026')).toEqual([])
    expect(highlightSegments('a 0771234567 b').map((s) => s.text).join('')).toBe('a 0771234567 b')
  })

  it('offers only the decisions the backend accepts', () => {
    expect(availableActions({ status: 'AWAITING_MODERATION' })).toEqual({ publish: 'PUBLISH', remove: true })
    expect(availableActions({ status: 'PUBLISHED', openReportCount: 2 })).toEqual({ publish: 'KEEP', remove: true })
    expect(availableActions({ status: 'PUBLISHED', openReportCount: 0 })).toEqual({ publish: null, remove: true })
    expect(availableActions({ status: 'REMOVED' })).toEqual({ publish: null, remove: false })
  })

  it('names the queue source and shortens long texts', () => {
    expect(queueSource({ status: 'AWAITING_MODERATION' })).toBe('FLAGGED')
    expect(queueSource({ status: 'PUBLISHED', openReportCount: 1 })).toBe('REPORTED')
    expect(excerpt('a'.repeat(100), 10)).toBe(`${'a'.repeat(9)}…`)
  })

  it('explains a conflict and a forbidden decision', () => {
    const conflict = { response: { status: 409, data: { error: 'REVIEW_UPDATE_CONFLICT', message: 'x' } } }
    expect(moderationErrorMessage(conflict)).toMatch(/changed by another moderator/)
    expect(moderationErrorMessage({ response: { status: 403, data: {} } })).toMatch(/only a moderator/)
    expect(moderationErrorMessage({ response: { status: 400, data: { message: 'Please choose a reason' } } })).toBe('Please choose a reason')
  })
})
