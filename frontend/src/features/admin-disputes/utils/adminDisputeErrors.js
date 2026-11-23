/**
 * Admin dispute API errors -> { status, code, kind, message, fieldErrors }. The code is read from the response's
 * `error` field (never from message text).
 */

const MESSAGES = Object.freeze({
  INVALID_DISPUTE_STATUS: 'This dispute can no longer be decided in its current state. Reload it to see the latest.',
  APPEAL_REVIEWER_MUST_DIFFER: 'A different admin must review this appeal.',
  DISPUTE_CONCURRENT_UPDATE: 'This dispute was changed by someone else. Reload it and try again.',
  DISPUTE_NOT_FOUND: 'This dispute could not be found.',
  ACCESS_DENIED: 'You do not have permission to do this.',
  UNAUTHORIZED: 'Your session has ended. Please sign in again.',
  VALIDATION_FAILED: 'Please check the highlighted fields.',
  INVALID_SEARCH_TEXT: 'Search by a case, booking or user id (letters, digits, "_" or "-").',
  INVALID_DATE_RANGE: 'The "from" date must not be after the "to" date.',
  INVALID_SORT: 'That sort order is not supported.',
  INVALID_STATUS_FILTER: 'That status filter is not supported.',
  INVALID_CATEGORY_FILTER: 'That category filter is not supported.',
})

const STATUS_MESSAGES = Object.freeze({
  401: MESSAGES.UNAUTHORIZED,
  403: MESSAGES.ACCESS_DENIED,
  404: MESSAGES.DISPUTE_NOT_FOUND,
  409: MESSAGES.DISPUTE_CONCURRENT_UPDATE,
})

function fieldErrorsOf(list) {
  const result = {}
  if (Array.isArray(list)) {
    for (const item of list) {
      if (item && typeof item.field === 'string' && typeof item.message === 'string' && !result[item.field]) {
        result[item.field] = item.message
      }
    }
  }
  return result
}

export function parseAdminError(error) {
  const response = error?.response
  if (!response) {
    return { status: 0, code: 'NETWORK_ERROR', kind: 'network',
      message: 'The server could not be reached. Check your connection and try again.', fieldErrors: {} }
  }
  const body = response.data && typeof response.data === 'object' ? response.data : {}
  const code = typeof body.error === 'string' ? body.error : null
  const message = (code && MESSAGES[code]) || STATUS_MESSAGES[response.status]
    || (typeof body.message === 'string' && body.message) || 'Something went wrong. Please try again.'
  let kind = 'error'
  if (response.status === 401) kind = 'unauthorized'
  else if (response.status === 403) kind = 'forbidden'
  else if (response.status === 404) kind = 'not-found'
  else if (response.status === 409) kind = 'conflict'
  else if (code === 'VALIDATION_FAILED' || response.status === 400) kind = 'validation'
  return { status: response.status, code, kind, message, fieldErrors: fieldErrorsOf(body.fieldErrors) }
}
