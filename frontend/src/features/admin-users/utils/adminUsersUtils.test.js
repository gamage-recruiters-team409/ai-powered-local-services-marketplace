import { describe, expect, it } from 'vitest'

import { fallbackExportName, filenameFromDisposition } from '../services/adminUsersService'
import { isContactReason, splitContacts } from './contactHighlight'
import { parseApiError } from './errors'
import { initials, percent } from './format'
import { noteError } from './validation'

describe('admin users utils', () => {
  it('splitContacts marks phone numbers, emails and links as text segments only', () => {
    const segments = splitContacts('Call +94 77 123 4567, mail a.b@shop.lk or visit https://x.lk/p <b>now</b>')
    expect(segments.filter((s) => s.contact).map((s) => s.text)).toEqual([
      '+94 77 123 4567',
      'a.b@shop.lk',
      'https://x.lk/p',
    ])
    expect(segments.map((s) => s.text).join('')).toBe(
      'Call +94 77 123 4567, mail a.b@shop.lk or visit https://x.lk/p <b>now</b>',
    )
    expect(splitContacts(null)).toEqual([])
    expect(splitContacts('No contact here, price 4500')).toEqual([{ text: 'No contact here, price 4500', contact: false }])
  })

  it('isContactReason reads the reason text and the screening flags', () => {
    expect(isContactReason('Description contains contact details')).toBe(true)
    expect(isContactReason(null, ['OFF_PLATFORM_CONTACT'])).toBe(true)
    expect(isContactReason('Misleading pricing', ['PRICE'])).toBe(false)
  })

  it('percent never invents a value when there is nothing to divide', () => {
    expect(percent(123, 128)).toBe(96)
    expect(percent(0, 0)).toBeNull()
    expect(percent(undefined, 10)).toBeNull()
  })

  it('noteError follows the backend rule (10..500 when required, at most 500 otherwise)', () => {
    expect(noteError('', { required: true })).toBe('Enter a note.')
    expect(noteError('   short   ', { required: true })).toBe('The note must be at least 10 characters.')
    expect(noteError('1234567890', { required: true })).toBeNull()
    expect(noteError('', { required: false })).toBeNull()
    expect(noteError('n'.repeat(501), { required: false })).toMatch(/500 characters/)
  })

  it('file name: from Content-Disposition when readable, otherwise the dated fallback', () => {
    expect(filenameFromDisposition('attachment; filename="users-2026-11-19.csv"')).toBe('users-2026-11-19.csv')
    expect(filenameFromDisposition(undefined)).toBeNull()
    expect(fallbackExportName(new Date('2026-11-19T23:30:00Z'))).toBe('users-2026-11-19.csv')
  })

  it('parseApiError maps codes to clear messages and handles network errors', () => {
    const err = (status, error, message) => ({ response: { status, data: { error, message } } })
    expect(parseApiError(err(503, 'USER_ACCOUNT_ACTION_UNAVAILABLE', 'x')).message).toBe(
      'Account actions are not available yet — the accounts service is not connected.',
    )
    expect(parseApiError(err(409, 'USER_STATUS_NOT_CHANGEABLE', 'Only a suspended account can be reactivated')).message).toBe(
      'Only a suspended account can be reactivated',
    )
    expect(parseApiError(new Error('offline')).code).toBe('NETWORK_ERROR')
    expect(initials('Sarah  Smith')).toBe('SS')
    expect(initials('')).toBe('?')
  })
})
