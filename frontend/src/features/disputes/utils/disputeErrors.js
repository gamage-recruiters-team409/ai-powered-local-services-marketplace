/**
 * Turns an API error into what the screen needs. The code is always read from the response's
 * `error` field (backend ErrorResponse), never from the message text.
 *
 * kind: 'network' | 'unauthorized' | 'forbidden' | 'not-found' | 'validation' | 'window-closed'
 *       | 'not-responder' | 'conflict' | 'error'
 */

const MESSAGES = Object.freeze({
  network: 'We could not reach the server. Check your connection and try again.',
  unauthorized: 'Your session has ended. Please sign in again.',
  forbidden: 'You do not have access to this dispute.',
  'not-found': 'We could not find this dispute.',
  validation: 'Please check the highlighted fields.',
  'window-closed': 'The response window for this dispute has closed, or a response was already submitted.',
  'not-responder': 'Only the other party can respond to this dispute.',
  conflict: 'This dispute was changed by someone else. Reload it and try again.',
  error: 'Something went wrong. Please try again.',
})

const KIND_BY_CODE = Object.freeze({
  VALIDATION_FAILED: 'validation',
  RESPONSE_WINDOW_CLOSED: 'window-closed',
  INVALID_DISPUTE_STATUS: 'window-closed',
  NOT_RESPONDING_PARTY: 'not-responder',
  NOT_DISPUTE_PARTY: 'forbidden',
  ACCESS_DENIED: 'forbidden',
  DISPUTE_NOT_FOUND: 'not-found',
  DISPUTE_CONCURRENT_UPDATE: 'conflict',
  UNAUTHORIZED: 'unauthorized',
})

const KIND_BY_STATUS = Object.freeze({
  401: 'unauthorized',
  403: 'forbidden',
  404: 'not-found',
  409: 'conflict',
})

function toFieldErrors(list) {
  if (!Array.isArray(list)) {
    return {}
  }
  const fieldErrors = {}
  for (const item of list) {
    if (item && typeof item.field === 'string' && typeof item.message === 'string' && !fieldErrors[item.field]) {
      fieldErrors[item.field] = item.message
    }
  }
  return fieldErrors
}

export function parseApiError(error) {
  const response = error?.response
  if (!response) {
    return { kind: 'network', status: null, code: null, message: MESSAGES.network, fieldErrors: {} }
  }
  const body = response.data && typeof response.data === 'object' ? response.data : {}
  const code = typeof body.error === 'string' ? body.error : null
  const kind = KIND_BY_CODE[code] ?? KIND_BY_STATUS[response.status] ?? 'error'
  return {
    kind,
    status: response.status,
    code,
    message: MESSAGES[kind],
    fieldErrors: kind === 'validation' ? toFieldErrors(body.fieldErrors) : {},
  }
}

export function messageForKind(kind) {
  return MESSAGES[kind] ?? MESSAGES.error
}
