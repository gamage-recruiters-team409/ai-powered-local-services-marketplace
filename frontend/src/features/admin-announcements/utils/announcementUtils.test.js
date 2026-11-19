import { describe, expect, it } from 'vitest'

import { deliveryLine, shortReference } from '../config/announcementConfig'
import { localInputToUtcIso, toLocalInputValue, utcOffsetLabel } from './datetime'
import { parseApiError } from './errors'
import { safeHref } from './markdown'
import { insertLink, prefixLines, wrapSelection } from './markdownEditing'
import { containsHtml, validateAnnouncement } from './validation'

describe('HTML check (same rule as the backend)', () => {
  it.each([
    '<script>alert(1)</script>',
    'Hi <b>there</b>',
    '<img src=x onerror=alert(1)>',
    '<iframe/src=x>',
    'end </div>',
    '<!-- hidden -->',
    '<!DOCTYPE html>',
    '<?xml version="1.0"?>',
  ])('rejects %s', (value) => {
    expect(containsHtml(value)).toBe(true)
  })

  it.each([
    '**bold** and _italic_',
    'Visit <https://example.com>',
    'a < b and c > d',
    'Use 2<3',
    '`code < 5`',
  ])('allows markdown %s', (value) => {
    expect(containsHtml(value)).toBe(false)
  })
})

describe('validation', () => {
  const ok = { title: 'Title', message: 'Message', audience: 'ALL_USERS', scheduledLocal: '' }

  it('passes a valid draft and publish', () => {
    expect(validateAnnouncement(ok, { intent: 'DRAFT' })).toEqual({})
    expect(validateAnnouncement(ok, { intent: 'PUBLISH' })).toEqual({})
  })

  it('a past schedule only blocks publishing, not drafts or locked (published) schedules', () => {
    const past = { ...ok, scheduledLocal: '2020-01-01T10:00' }
    expect(validateAnnouncement(past, { intent: 'PUBLISH' }).scheduledAt).toBeTruthy()
    expect(validateAnnouncement(past, { intent: 'DRAFT' })).toEqual({})
    expect(validateAnnouncement(past, { intent: 'PUBLISH', lockedSchedule: true })).toEqual({})
  })

  it('title is checked after trimming; message length is 5000', () => {
    expect(validateAnnouncement({ ...ok, title: ` ${'t'.repeat(80)} ` }, { intent: 'DRAFT' })).toEqual({})
    expect(validateAnnouncement({ ...ok, message: 'm'.repeat(5001) }, { intent: 'DRAFT' }).message).toBeTruthy()
  })
})

describe('dates', () => {
  it('local datetime input <-> UTC ISO round trip', () => {
    const iso = localInputToUtcIso('2026-04-01T09:00')
    expect(iso.endsWith('Z')).toBe(true)
    expect(toLocalInputValue(iso)).toBe('2026-04-01T09:00')
    expect(localInputToUtcIso('')).toBeNull()
  })

  it('offset label has the UTC+hh:mm shape', () => {
    expect(utcOffsetLabel()).toMatch(/^UTC[+-]\d{2}:\d{2}$/)
  })
})

describe('markdown helpers', () => {
  it('only http, https and mailto links are kept', () => {
    expect(safeHref('https://example.com/a')).toBe('https://example.com/a')
    expect(safeHref('mailto:help@example.com')).toBe('mailto:help@example.com')
    expect(safeHref('javascript:alert(1)')).toBeNull()
    expect(safeHref('data:text/html,hi')).toBeNull()
    expect(safeHref('/relative')).toBeNull()
  })

  it('toolbar transforms', () => {
    expect(wrapSelection('Sale now', 0, 4, '**', 'bold').value).toBe('**Sale** now')
    expect(wrapSelection('', 0, 0, '_', 'italic text').value).toBe('_italic text_')
    expect(insertLink('see docs', 4, 8).value).toBe('see [docs](https://)')
    expect(prefixLines('one\ntwo', 0, 7, false).value).toBe('- one\n- two')
    expect(prefixLines('one\ntwo', 0, 7, true).value).toBe('1. one\n2. two')
  })
})

describe('labels and errors', () => {
  it('delivery line and short reference', () => {
    expect(deliveryLine({ sendPush: true, pinToHeader: true })).toBe('In-app banner + push notification')
    expect(deliveryLine({ sendPush: true, pinToHeader: false })).toBe('Push notification')
    expect(shortReference('65f1c0aa11bb22cc33a1b2c3')).toBe('#A1B2C3')
  })

  it('maps backend codes, field errors and network failures', () => {
    const conflict = parseApiError({ response: { status: 409, data: { error: 'ANNOUNCEMENT_FIELDS_LOCKED' } } })
    expect(conflict.message).toMatch(/Only the title and message/)
    const invalid = parseApiError({
      response: { status: 400, data: { error: 'VALIDATION_FAILED', fieldErrors: [{ field: 'title', message: 'too long' }] } },
    })
    expect(invalid.fieldErrors).toEqual({ title: 'too long' })
    expect(parseApiError(new Error('Network Error')).message).toMatch(/Could not reach the server/)
    expect(parseApiError({ response: { status: 403, data: {} } }).message).toMatch(/view-only access/)
  })
})
