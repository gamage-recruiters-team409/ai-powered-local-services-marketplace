/** Clear messages for the backend's announcement error codes (shown in toasts and alerts). */
const CODE_MESSAGES = Object.freeze({
  ANNOUNCEMENT_NOT_FOUND: 'This announcement no longer exists.',
  ANNOUNCEMENT_NOT_DELETABLE: 'This announcement has already been published and can no longer be deleted.',
  ANNOUNCEMENT_STATE_CONFLICT:
    'This announcement just changed (for example it was published at its scheduled time). The latest version is shown.',
  ANNOUNCEMENT_UPDATE_CONFLICT: 'Someone else changed this announcement at the same time. Reload it and try again.',
  ANNOUNCEMENT_FIELDS_LOCKED: 'Only the title and message of a published announcement can be changed.',
  VALIDATION_FAILED: 'Some fields need attention.',
  MALFORMED_REQUEST: 'The request could not be read. Check the fields and try again.',
  INVALID_PAGE_REQUEST: 'That page could not be loaded.',
  INVALID_SEARCH_TEXT: 'The search text is too long.',
})

/** Conflicts after which the shown data is out of date and must be reloaded. */
export const STALE_CODES = Object.freeze(['ANNOUNCEMENT_NOT_DELETABLE', 'ANNOUNCEMENT_STATE_CONFLICT',
  'ANNOUNCEMENT_UPDATE_CONFLICT', 'ANNOUNCEMENT_NOT_FOUND'])

/**
 * Normalises an Axios error into { status, code, message, fieldErrors }.
 * fieldErrors maps the backend's field names to their messages.
 */
export function parseApiError(error) {
  const status = error?.response?.status ?? null
  const data = error?.response?.data ?? {}
  const code = typeof data.error === 'string' ? data.error : null
  const fieldErrors = {}
  for (const item of Array.isArray(data.fieldErrors) ? data.fieldErrors : []) {
    if (item?.field && !fieldErrors[item.field]) fieldErrors[item.field] = item.message
  }

  let message
  if (!error?.response) {
    message = 'Could not reach the server. Check your connection and try again.'
  } else if (status === 401) {
    message = 'Your session has expired. Please sign in again.'
  } else if (status === 403) {
    message = 'You have view-only access. This action is not allowed for your role.'
  } else {
    const backendMessage = typeof data.message === 'string' && data.message ? data.message : null
    message = CODE_MESSAGES[code] ?? backendMessage ?? 'Something went wrong. Please try again.'
  }
  return { status, code, message, fieldErrors }
}
