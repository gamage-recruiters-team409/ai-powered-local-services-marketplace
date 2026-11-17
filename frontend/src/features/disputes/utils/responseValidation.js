/** Same limit as the backend's SubmitDisputeResponseRequest (DisputeRules.MAX_DESCRIPTION_LENGTH). */
export const MAX_RESPONSE_LENGTH = 2000

/** Returns an error message for the response text, or null when it can be sent. */
export function validateResponseText(text) {
  const clean = typeof text === 'string' ? text.trim() : ''
  if (clean === '') {
    return 'Please tell your side before submitting.'
  }
  if (clean.length > MAX_RESPONSE_LENGTH) {
    return `Your response must be at most ${MAX_RESPONSE_LENGTH} characters.`
  }
  return null
}
