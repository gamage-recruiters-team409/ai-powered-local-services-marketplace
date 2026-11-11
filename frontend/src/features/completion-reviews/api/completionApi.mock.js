/**
 * WP08 – Mock Service Completion API.
 * Same function names and response shape as completionApi.js, with in-memory data and a short delay.
 * Used until the booking (WP05) and completion backend endpoints are merged.
 */

const DELAY_MS = 600
const store = new Map()

const wait = (value, ms = DELAY_MS) => new Promise((resolve) => setTimeout(() => resolve(value), ms))

function apiError(status, error, message) {
  const err = new Error(message)
  err.response = { status, data: { status, error, message } }
  return err
}

function toResponse(record) {
  return { ...record, photoUrls: [...(record.photoUrls ?? [])] }
}

/** Seeds or replaces the mock completion for a booking (used by the preview page and tests). */
export function seedCompletion(bookingId, completion) {
  if (completion) {
    store.set(bookingId, { id: `comp-${bookingId}`, bookingId, photoUrls: [], ...completion })
  } else {
    store.delete(bookingId)
  }
}

export function resetMockCompletions() {
  store.clear()
}

export async function getCompletion(bookingId) {
  const record = store.get(bookingId)
  if (!record) {
    throw apiError(404, 'COMPLETION_NOT_FOUND', 'The service has not been started for this booking')
  }
  return wait(toResponse(record), 150)
}

export async function startService(bookingId) {
  if (store.has(bookingId)) {
    await wait(null)
    throw apiError(409, 'SERVICE_ALREADY_STARTED', 'The service has already been started for this booking')
  }
  const now = new Date().toISOString()
  store.set(bookingId, {
    id: `comp-${bookingId}`,
    bookingId,
    startedAt: now,
    completionNote: null,
    photoUrls: [],
    markedCompletedAt: null,
    confirmedAt: null,
    confirmationType: null,
    issueRaisedAt: null,
    disputeId: null,
  })
  return wait(toResponse(store.get(bookingId)))
}

export async function markCompleted(bookingId, { completionNote, photoUrls }) {
  const record = store.get(bookingId)
  if (!record) {
    await wait(null)
    throw apiError(404, 'COMPLETION_NOT_FOUND', 'The service has not been started for this booking')
  }
  if (record.markedCompletedAt) {
    await wait(null)
    throw apiError(409, 'SERVICE_ALREADY_MARKED_COMPLETED', 'This service has already been marked as completed')
  }
  record.completionNote = completionNote?.trim() || null
  record.photoUrls = photoUrls ?? []
  record.markedCompletedAt = new Date().toISOString()
  return wait(toResponse(record))
}

export async function confirmCompletion(bookingId) {
  const record = store.get(bookingId)
  if (!record?.markedCompletedAt) {
    await wait(null)
    throw apiError(409, 'INVALID_STATUS_TRANSITION', 'Completion can only be confirmed while it is pending')
  }
  record.confirmedAt = new Date().toISOString()
  record.confirmationType = 'CUSTOMER'
  return wait(toResponse(record))
}

/**
 * Mock upload: returns a temporary browser link to the selected photo (blob:),
 * so the real image is shown in the preview. It only lasts until the page is reloaded.
 * Real uploads need the media endpoint.
 */
export async function uploadCompletionPhoto(file) {
  let url = `mock://completion-photo/${encodeURIComponent(file.name)}`
  try {
    if (typeof URL.createObjectURL === 'function') {
      url = URL.createObjectURL(file)
    }
  } catch {
    // keep the placeholder URL
  }
  return wait(url, 300)
}
