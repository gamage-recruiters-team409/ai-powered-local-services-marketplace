/** Clear messages for the backend's operations error codes (shown in toasts and inline). */
const CODE_MESSAGES = Object.freeze({
  OPS_INVALID_NOTE: 'A note of 10–1000 characters is required.',
  OPS_INVALID_ACTION: 'That action is not recognised.',
  OPS_ACTION_REQUIRED: 'Please choose an action.',
  OPS_ACTION_NOT_ALLOWED_FOR_CASE: 'That action does not apply to this case.',
  OPS_SUPPORT_CANNOT_PERFORM_ACTION: 'Only an Admin can suspend an account.',
  OPS_FORBIDDEN: 'You do not have permission to do this.',
  CONCURRENT_MODIFICATION: 'The account was changed by someone else. Reload and try again.',
  USER_STATUS_NOT_CHANGEABLE: null, // the backend message explains which rule applies
  ACCESS_DENIED: 'You do not have permission to do this.',
  UNAUTHORIZED: 'Your session has ended. Please sign in again.',
})

/** Normalises an Axios error to { status, code, message }. */
export function parseApiError(error) {
  const response = error?.response
  if (!response) {
    return {
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'The server could not be reached. Check your connection and try again.',
    }
  }
  const body = response.data && typeof response.data === 'object' ? response.data : {}
  const code = body.error ?? null
  const known = code ? CODE_MESSAGES[code] : undefined
  let message = known
  if (!message) {
    message = typeof body.message === 'string' && body.message ? body.message : 'Something went wrong. Please try again.'
  }
  return { status: response.status, code, message }
}

export function isConcurrentModification(apiError) {
  return apiError?.code === 'CONCURRENT_MODIFICATION'
}
