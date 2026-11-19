/** Clear messages for the backend's admin users error codes (shown in toasts and alerts). */
const CODE_MESSAGES = Object.freeze({
  USER_NOT_FOUND: 'This account could not be found.',
  LISTING_NOT_FOUND: 'This listing could not be found for this provider.',
  USER_ACCOUNT_ACTION_UNAVAILABLE: 'Account actions are not available yet — the accounts service is not connected.',
  LISTING_ACTION_UNAVAILABLE: 'Listing decisions are not available yet — the listing service is not connected.',
  USER_ACTION_NOT_ALLOWED: 'This action is not allowed for this account.',
  USER_STATUS_NOT_CHANGEABLE: null, // the backend message explains which rule applies
  ACCESS_DENIED: 'You do not have permission to do this.',
  UNAUTHORIZED: 'Your session has ended. Please sign in again.',
  INVALID_FILTER: 'That filter is not supported.',
  INVALID_SEARCH_TEXT: 'The search text is too long.',
  INVALID_PAGE_REQUEST: 'That page could not be loaded.',
})

/**
 * Normalises an Axios error to { status, code, message }. Network errors and timeouts get a readable message;
 * the backend's own message is used when there is no better one for the code.
 */
export function parseApiError(error) {
  const response = error?.response
  if (!response) {
    return { status: 0, code: 'NETWORK_ERROR', message: 'The server could not be reached. Check your connection and try again.' }
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
