import * as realApi from './completionApi'
import * as mockApi from './completionApi.mock'
import * as realReviewApi from './reviewApi'
import * as mockReviewApi from './reviewApi.mock'
import * as realModerationApi from './moderationApi'
import * as mockModerationApi from './moderationApi.mock'

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

// ---------------------------------------------------------------- reviews (S07–S09)

if (useCompletionMock) {
  mockReviewApi.seedDemoReviews()
}

/** Same switch as the completion API: VITE_USE_COMPLETION_MOCK=true uses the in-memory review data. */
export const reviewApi = useCompletionMock ? mockReviewApi : realReviewApi

/** Turns a review API error into a short message for the user. */
export function reviewErrorMessage(error) {
  const data = error?.response?.data
  if (data?.message) {
    return data.message
  }
  if (error?.response?.status === 403) {
    return 'You can only review your own bookings.'
  }
  return 'Something went wrong. Please try again.'
}

// ---------------------------------------------------------------- review moderation (S14–S16, admin)

if (useCompletionMock) {
  mockModerationApi.seedDemoModeration()
}

/** Same switch: VITE_USE_COMPLETION_MOCK=true uses the in-memory moderation data. */
export const moderationApi = useCompletionMock ? mockModerationApi : realModerationApi
