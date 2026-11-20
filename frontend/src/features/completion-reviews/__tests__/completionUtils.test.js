import { describe, expect, it } from 'vitest'

import {
  autoConfirmRemainingMs,
  buildIssueReason,
  formatDuration,
  formatSlot,
  hoursSinceSlotEnded,
  noShowAllowedFrom,
  noShowRemainingMs,
  shortName,
  stepState,
  toPhotoItems,
  validatePhoto,
} from '../utils/completionUtils'



const file = (name, type, size = 1000) => ({ name, type, size })

describe('completionUtils', () => {
  it('maps booking statuses to stepper state', () => {
    expect(stepState('CONFIRMED')).toEqual({ done: 0, current: 0, error: null })
    expect(stepState('IN_PROGRESS')).toEqual({ done: 1, current: 1, error: null })
    expect(stepState('COMPLETION_PENDING')).toEqual({ done: 2, current: 2, error: null })
    expect(stepState('DISPUTED').error).toBe(2)
    expect(stepState('COMPLETED').done).toBe(4)
  })

  it('counts down the 48-hour auto-confirm window', () => {
    const marked = '2026-11-11T00:00:00Z'
    expect(formatDuration(autoConfirmRemainingMs(marked, new Date('2026-11-11T16:40:00Z')))).toBe('31h 20m')
    expect(autoConfirmRemainingMs(marked, new Date('2026-11-14T00:00:00Z'))).toBe(0)
  })

  it('reports whole hours since the slot ended', () => {
    expect(hoursSinceSlotEnded('2026-11-11T06:30:00Z', new Date('2026-11-11T09:45:00Z'))).toBe(3)
    expect(hoursSinceSlotEnded('2026-11-11T10:00:00Z', new Date('2026-11-11T09:00:00Z'))).toBe(0)
  })

  it('formats the slot in Sri Lanka time and says "today" for today', () => {
    // 04:30Z = 10:00 in Colombo
    expect(formatSlot('2026-11-11T04:30:00Z', '2026-11-11T06:30:00Z', new Date('2026-11-11T03:00:00Z')))
      .toBe('today, 10:00 – 12:00')
  })

  it('shortens customer names', () => {
    expect(shortName('Nimal Perera')).toBe('Nimal P.')
    expect(shortName('Nimal')).toBe('Nimal')
  })

  it('accepts JPG/PNG up to 5 MB and rejects other files', () => {
    expect(validatePhoto(file('a.jpg', 'image/jpeg'))).toBeNull()
    expect(validatePhoto(file('b.PNG', 'image/png'))).toBeNull()
    expect(validatePhoto(file('photo_3.heic', 'image/heic'))).toBe('photo_3.heic – this file type is not supported')
    expect(validatePhoto(file('big.jpg', 'image/jpeg', 6 * 1024 * 1024))).toBe('big.jpg – this file is larger than 5 MB')
  })

  it('allows at most 5 valid photos', () => {
    const files = Array.from({ length: 3 }, (_, i) => file(`p${i}.jpg`, 'image/jpeg'))
    const items = toPhotoItems(files, 3)
    expect(items.filter((item) => !item.error)).toHaveLength(2)
    expect(items[2].error).toBe('p2.jpg – you can add up to 5 photos')
  })
})

describe('no-show and raise-issue helpers', () => {
  it('allows a no-show report 120 minutes after the slot start', () => {
    const slotStart = '2026-11-11T04:30:00Z'
    expect(noShowAllowedFrom(slotStart).toISOString()).toBe('2026-11-11T06:30:00.000Z')
    expect(noShowRemainingMs(slotStart, new Date('2026-11-11T05:15:00Z'))).toBe(75 * 60 * 1000)
    expect(noShowRemainingMs(slotStart, new Date('2026-11-11T06:30:00Z'))).toBe(0)
    expect(noShowRemainingMs(null)).toBe(0)
  })

  it('builds the backend reason as "<reason label>: <description>" (max 500 characters)', () => {
    expect(buildIssueReason('DAMAGE', '  Broken glass on the balcony door. ')).toBe('Damage to my property: Broken glass on the balcony door.')
    expect(buildIssueReason('UNKNOWN', 'Something else went wrong')).toBe('Other: Something else went wrong')
    expect(buildIssueReason('OTHER', 'x'.repeat(600))).toHaveLength(500)
  })
})
