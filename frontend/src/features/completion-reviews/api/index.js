import * as realApi from './completionApi'
import * as mockApi from './completionApi.mock'

/**
 * Set VITE_USE_COMPLETION_MOCK=true in frontend/.env (local only) to use the mock API.
 * Pages receive the API through props, so switching needs no page changes.
 */
export const useCompletionMock = import.meta.env.VITE_USE_COMPLETION_MOCK === 'true'

export const completionApi = useCompletionMock ? mockApi : realApi

/** Turns an API error into a short message for the user. */
export function completionErrorMessage(error) {
  const data = error?.response?.data
  if (data?.message) {
    return data.message
  }
  if (error?.response?.status === 403) {
    return 'You are not allowed to do this for this booking.'
  }
  return 'Something went wrong. Please try again.'
}
