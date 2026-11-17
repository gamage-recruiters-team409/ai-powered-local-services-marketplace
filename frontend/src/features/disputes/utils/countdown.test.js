import { describe, expect, it } from 'vitest'

import { formatTimeLeft, getTimeLeft } from './countdown'

const NOW = Date.parse('2026-11-16T10:00:00Z')
const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE

function dueIn(ms) {
  return new Date(NOW + ms).toISOString()
}

describe('getTimeLeft', () => {
  it('splits the remaining time into days, hours and minutes (UTC deadline)', () => {
    expect(getTimeLeft(dueIn(2 * 24 * HOUR + 4 * HOUR + 30 * MINUTE), NOW))
      .toEqual({ overdue: false, days: 2, hours: 4, minutes: 30 })
  })

  it('rounds down to whole minutes', () => {
    expect(getTimeLeft(dueIn(90 * 1000), NOW)).toEqual({ overdue: false, days: 0, hours: 0, minutes: 1 })
  })

  it('is overdue exactly at the deadline and after it', () => {
    expect(getTimeLeft(dueIn(0), NOW).overdue).toBe(true)
    expect(getTimeLeft(dueIn(-5 * MINUTE), NOW).overdue).toBe(true)
  })

  it('returns null for a missing or invalid date', () => {
    expect(getTimeLeft(null, NOW)).toBeNull()
    expect(getTimeLeft('not a date', NOW)).toBeNull()
  })
})

describe('formatTimeLeft', () => {
  it.each([
    [2 * 24 * HOUR + 4 * HOUR, '2 days 4 hours left'],
    [24 * HOUR + HOUR, '1 day 1 hour left'],
    [3 * HOUR + 12 * MINUTE, '3 hours 12 minutes left'],
    [45 * MINUTE, '45 minutes left'],
    [30 * 1000, 'less than a minute left'],
    [-1, 'Overdue'],
  ])('%i ms -> %s', (ms, text) => {
    expect(formatTimeLeft(getTimeLeft(dueIn(ms), NOW))).toBe(text)
  })

  it('is empty when there is no deadline', () => {
    expect(formatTimeLeft(null)).toBe('')
  })
})
