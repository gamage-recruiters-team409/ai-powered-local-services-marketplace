import { describe, expect, it } from 'vitest'

import { parseApiError } from './disputeErrors'

function httpError(status, data) {
  return { response: { status, data } }
}

describe('parseApiError', () => {
  it('reads the code from the `error` field, not from the message text', () => {
    const parsed = parseApiError(httpError(404, { error: 'DISPUTE_NOT_FOUND', message: 'access denied' }))
    expect(parsed).toMatchObject({ kind: 'not-found', status: 404, code: 'DISPUTE_NOT_FOUND' })
  })

  it('returns field errors for 400 VALIDATION_FAILED', () => {
    const parsed = parseApiError(httpError(400, {
      error: 'VALIDATION_FAILED',
      fieldErrors: [{ field: 'text', message: 'text is required' }, { field: 'text', message: 'second' }],
    }))
    expect(parsed.kind).toBe('validation')
    expect(parsed.fieldErrors).toEqual({ text: 'text is required' })
  })

  it.each([
    [400, 'RESPONSE_WINDOW_CLOSED', 'window-closed'],
    [409, 'INVALID_DISPUTE_STATUS', 'window-closed'],
    [409, 'DISPUTE_CONCURRENT_UPDATE', 'conflict'],
    [403, 'NOT_RESPONDING_PARTY', 'not-responder'],
    [403, 'NOT_DISPUTE_PARTY', 'forbidden'],
    [403, 'ACCESS_DENIED', 'forbidden'],
    [401, 'UNAUTHORIZED', 'unauthorized'],
  ])('%i %s -> %s', (status, code, kind) => {
    expect(parseApiError(httpError(status, { error: code })).kind).toBe(kind)
  })

  it('falls back to the HTTP status when the code is unknown or missing', () => {
    expect(parseApiError(httpError(401, {})).kind).toBe('unauthorized')
    expect(parseApiError(httpError(403, 'not json')).kind).toBe('forbidden')
    expect(parseApiError(httpError(409, { error: 'SOMETHING_ELSE' })).kind).toBe('conflict')
    expect(parseApiError(httpError(500, { error: 'INTERNAL_ERROR' })).kind).toBe('error')
  })

  it('treats a missing response as a network error', () => {
    expect(parseApiError(new Error('Network Error')).kind).toBe('network')
  })

  it('never returns field errors for non-validation errors', () => {
    expect(parseApiError(httpError(409, { error: 'INVALID_DISPUTE_STATUS', fieldErrors: [{ field: 'x', message: 'y' }] }))
      .fieldErrors).toEqual({})
  })
})
