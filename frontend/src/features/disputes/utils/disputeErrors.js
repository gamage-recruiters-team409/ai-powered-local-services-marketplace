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
  'appeal-closed': 'The appeal window for this dispute has closed.',
  'appeal-used': 'An appeal has already been filed for this dispute.',
  'raise-window-closed': 'The time to raise a dispute for this booking has passed.',
  'already-exists': 'A dispute already exists for this booking.',
  'not-disputable': 'This booking cannot be disputed in its current state.',
  'booking-not-found': 'We could not find this booking.',
  'evidence-type': 'Only JPG, PNG, WEBP or PDF files can be uploaded.',
  'evidence-size': 'Each file must be 5 MB or smaller.',
  'evidence-limit': 'You can upload at most 5 files to this dispute.',
  'evidence-closed': 'Evidence can no longer be added to this dispute.',
  'evidence-unavailable': 'File uploads are not available right now. Please try again later.',
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
  APPEAL_WINDOW_CLOSED: 'appeal-closed',
  APPEAL_ALREADY_FILED: 'appeal-used',
  DISPUTE_WINDOW_CLOSED: 'raise-window-closed',
  DISPUTE_ALREADY_EXISTS: 'already-exists',
  INVALID_STATUS_TRANSITION: 'not-disputable',
  BOOKING_NOT_FOUND: 'booking-not-found',
  EVIDENCE_TYPE_NOT_ALLOWED: 'evidence-type',
  EVIDENCE_TOO_LARGE: 'evidence-size',
  EVIDENCE_LIMIT_REACHED: 'evidence-limit',
  EVIDENCE_UPLOAD_CLOSED: 'evidence-closed',
  EVIDENCE_STORAGE_UNAVAILABLE: 'evidence-unavailable',
  EVIDENCE_UPLOAD_FAILED: 'evidence-unavailable',
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
